package pickup

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	// BurnGracePeriod 取后即焚全部文件下载完成后保留的宽限时间（10 分钟，允许期间断点续传）
	BurnGracePeriod = 10 * time.Minute
	maxFilesPerCode = 50
)

var (
	ErrPickupNotFound         = errors.New("取件码不存在或已被提取删除")
	ErrPickupExpired          = errors.New("该取件码已过期或已被领取销毁")
	ErrPickupPasswordRequired = errors.New("该取件码设有提取密码，请输入密码")
	ErrWrongPassword          = errors.New("取件码提取密码错误")
	ErrQuotaExceeded          = errors.New("超出取件码存储配额上限")
	ErrFileTooLarge           = errors.New("单个文件大小超出取件码限制")
	ErrNotOwner               = errors.New("仅创建者可执行此操作")
	ErrFileNotFound           = errors.New("取件码中不存在该文件")
	ErrFileNotReady           = errors.New("该文件尚未上传完成")
	ErrInvalidRequest         = errors.New("请求参数无效")
	ErrSizeMismatch           = errors.New("上传文件总大小与申报大小不一致")

	pickupCodeRegex = regexp.MustCompile(`^\d{6}$`)
	safeFileIDRegex = regexp.MustCompile(`^f_[a-f0-9]{16}$`)
)

// Config 描述取件码存储配额限制
type Config struct {
	DataDir     string
	MaxFileMB   int
	MaxTotalMB  int
	DiskQuotaMB int
}

// CreateFileInput 创建取件码时声明的单个文件元信息
type CreateFileInput struct {
	Name string `json:"name"`
	Size int64  `json:"size"`
	Mime string `json:"mime"`
}

// CreateRequest 创建取件码请求体
type CreateRequest struct {
	Files         []CreateFileInput `json:"files"`
	ExpireHours   int               `json:"expireHours"` // 1 | 24 | 72
	BurnAfterRead bool              `json:"burnAfterRead"`
	Password      string            `json:"password,omitempty"`
}

// FileInfo 对外返回的文件元信息
type FileInfo struct {
	FileID string `json:"fileId"`
	Name   string `json:"name"`
	Size   int64  `json:"size"`
	Mime   string `json:"mime"`
	Ready  bool   `json:"ready,omitempty"`
}

// CreateResponse 创建取件码成功响应
type CreateResponse struct {
	Code       string     `json:"code"`
	OwnerToken string     `json:"ownerToken"`
	ExpiresAt  int64      `json:"expiresAt"`
	Files      []FileInfo `json:"files"`
}

// PickupInfo 查询取件码详情响应
type PickupInfo struct {
	Code          string     `json:"code"`
	Files         []FileInfo `json:"files"`
	ExpiresAt     int64      `json:"expiresAt"`
	BurnAfterRead bool       `json:"burnAfterRead"`
	Downloads     int        `json:"downloads"`
	HasPassword   bool       `json:"hasPassword"`
	BurnedAt      int64      `json:"burnedAt,omitempty"`
}

type fileRecord struct {
	FileID          string `json:"fileId"`
	Name            string `json:"name"`
	Size            int64  `json:"size"`
	Mime            string `json:"mime"`
	Ready           bool   `json:"ready"`
	DownloadedCount int    `json:"downloadedCount"`
}

type pickupRecord struct {
	Code           string        `json:"code"`
	OwnerTokenHash string        `json:"ownerTokenHash"`
	PasswordHash   string        `json:"passwordHash,omitempty"`
	ExpireHours    int           `json:"expireHours"`
	BurnAfterRead  bool          `json:"burnAfterRead"`
	Downloads      int           `json:"downloads"`
	CreatedAt      int64         `json:"createdAt"`
	ExpiresAt      int64         `json:"expiresAt"`
	BurnedAt       int64         `json:"burnedAt,omitempty"`
	Files          []*fileRecord `json:"files"`
}

// Store 管理取件码离线文件投递的元数据持久化、分片断点写入、下载统计与过期清理
type Store struct {
	mu             sync.RWMutex
	baseDir        string
	maxFileBytes   int64
	maxTotalBytes  int64
	diskQuotaBytes int64
	records        map[string]*pickupRecord
	expiredCodes   map[string]int64 // 记录已过期或已焚毁的取件码（保存 24 小时用于返回 PICKUP_EXPIRED）
	nowFunc        func() time.Time
	stopCh         chan struct{}
}

// NewStore 初始化取件码存储管理器并从磁盘恢复未过期的取件码
func NewStore(cfg Config) (*Store, error) {
	baseDir := filepath.Join(cfg.DataDir, "pickups")
	if err := os.MkdirAll(baseDir, 0o755); err != nil {
		return nil, fmt.Errorf("创建取件码存储目录失败: %w", err)
	}
	maxFileMB := cfg.MaxFileMB
	if maxFileMB <= 0 {
		maxFileMB = 200
	}
	maxTotalMB := cfg.MaxTotalMB
	if maxTotalMB <= 0 {
		maxTotalMB = 500
	}
	diskQuotaMB := cfg.DiskQuotaMB
	if diskQuotaMB <= 0 {
		diskQuotaMB = 5120
	}

	s := &Store{
		baseDir:        baseDir,
		maxFileBytes:   int64(maxFileMB) * 1024 * 1024,
		maxTotalBytes:  int64(maxTotalMB) * 1024 * 1024,
		diskQuotaBytes: int64(diskQuotaMB) * 1024 * 1024,
		records:        make(map[string]*pickupRecord),
		expiredCodes:   make(map[string]int64),
		nowFunc:        time.Now,
		stopCh:         make(chan struct{}),
	}
	if err := s.loadFromDisk(); err != nil {
		return nil, err
	}
	// 启动时立即清扫一次过期或已超过焚毁宽限期的取件码
	s.PurgeExpired()
	go s.maintenanceLoop()
	return s, nil
}

// Close 停止后台定时清理协程
func (s *Store) Close() {
	select {
	case <-s.stopCh:
	default:
		close(s.stopCh)
	}
}

// SetNowFunc 仅供单元测试注入可控时钟
func (s *Store) SetNowFunc(fn func() time.Time) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.nowFunc = fn
}

func (s *Store) now() time.Time {
	if s.nowFunc != nil {
		return s.nowFunc()
	}
	return time.Now()
}

func (s *Store) maintenanceLoop() {
	ticker := time.NewTicker(1 * time.Minute)
	defer ticker.Stop()
	for {
		select {
		case <-s.stopCh:
			return
		case <-ticker.C:
			s.PurgeExpired()
		}
	}
}

func (s *Store) pickupDir(code string) string {
	return filepath.Join(s.baseDir, code)
}

func (s *Store) metaPath(code string) string {
	return filepath.Join(s.pickupDir(code), "meta.json")
}

func (s *Store) dataPath(code, fileID string) string {
	return filepath.Join(s.pickupDir(code), fileID+".data")
}

func (s *Store) loadFromDisk() error {
	entries, err := os.ReadDir(s.baseDir)
	if err != nil {
		return err
	}
	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}
		code := entry.Name()
		if !pickupCodeRegex.MatchString(code) {
			continue
		}
		raw, err := os.ReadFile(s.metaPath(code))
		if err != nil {
			continue
		}
		var rec pickupRecord
		if err := json.Unmarshal(raw, &rec); err != nil || rec.Code == "" {
			continue
		}
		s.records[rec.Code] = &rec
	}
	return nil
}

func (s *Store) saveRecordLocked(rec *pickupRecord) error {
	dir := s.pickupDir(rec.Code)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	payload, err := json.MarshalIndent(rec, "", "  ")
	if err != nil {
		return err
	}
	tmpPath := s.metaPath(rec.Code) + ".tmp"
	if err := os.WriteFile(tmpPath, payload, 0o644); err != nil {
		return err
	}
	return os.Rename(tmpPath, s.metaPath(rec.Code))
}

func (s *Store) isExpiredLocked(rec *pickupRecord, nowMs int64) bool {
	if rec == nil {
		return true
	}
	if nowMs >= rec.ExpiresAt {
		return true
	}
	if rec.BurnAfterRead && rec.BurnedAt > 0 && nowMs >= rec.BurnedAt+BurnGracePeriod.Milliseconds() {
		return true
	}
	return false
}

func (s *Store) totalReservedDiskBytesLocked() int64 {
	var total int64
	for _, rec := range s.records {
		for _, f := range rec.Files {
			total += f.Size
		}
	}
	return total
}

// Create 创建新的取件码元数据与目录，返回 6 位取件码与 32 位 ownerToken
func (s *Store) Create(req CreateRequest) (*CreateResponse, error) {
	if len(req.Files) == 0 || len(req.Files) > maxFilesPerCode {
		return nil, fmt.Errorf("%w：文件数量必须在 1 到 %d 之间", ErrInvalidRequest, maxFilesPerCode)
	}
	switch req.ExpireHours {
	case 1, 24, 72:
	default:
		return nil, fmt.Errorf("%w：有效期必须为 1、24 或 72 小时", ErrInvalidRequest)
	}

	var totalSize int64
	fileRecs := make([]*fileRecord, 0, len(req.Files))
	respFiles := make([]FileInfo, 0, len(req.Files))
	for _, item := range req.Files {
		if item.Size <= 0 {
			return nil, fmt.Errorf("%w：文件大小必须大于 0", ErrInvalidRequest)
		}
		if item.Size > s.maxFileBytes {
			return nil, ErrFileTooLarge
		}
		totalSize += item.Size
		if totalSize > s.maxTotalBytes {
			return nil, ErrQuotaExceeded
		}
		fid := "f_" + randomHex(8)
		name := sanitizeFileName(item.Name)
		mime := strings.TrimSpace(strings.Split(item.Mime, ";")[0])
		if mime == "" {
			mime = "application/octet-stream"
		}
		fileRecs = append(fileRecs, &fileRecord{
			FileID: fid,
			Name:   name,
			Size:   item.Size,
			Mime:   mime,
			Ready:  false,
		})
		respFiles = append(respFiles, FileInfo{
			FileID: fid,
			Name:   name,
			Size:   item.Size,
			Mime:   mime,
		})
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	// 先顺手清理过期记录以释放磁盘配额
	s.purgeExpiredLocked()

	if s.totalReservedDiskBytesLocked()+totalSize > s.diskQuotaBytes {
		return nil, ErrQuotaExceeded
	}

	code := s.allocateUniqueCodeLocked()
	ownerToken := randomHex(16)
	now := s.now()
	nowMs := now.UnixMilli()
	expiresAt := now.Add(time.Duration(req.ExpireHours) * time.Hour).UnixMilli()

	rec := &pickupRecord{
		Code:           code,
		OwnerTokenHash: sha256Hex(ownerToken),
		PasswordHash:   hashPassword(req.Password),
		ExpireHours:    req.ExpireHours,
		BurnAfterRead:  req.BurnAfterRead,
		CreatedAt:      nowMs,
		ExpiresAt:      expiresAt,
		Files:          fileRecs,
	}
	if err := s.saveRecordLocked(rec); err != nil {
		return nil, err
	}
	s.records[code] = rec
	delete(s.expiredCodes, code)

	return &CreateResponse{
		Code:       code,
		OwnerToken: ownerToken,
		ExpiresAt:  expiresAt,
		Files:      respFiles,
	}, nil
}

func (s *Store) allocateUniqueCodeLocked() string {
	for i := 0; i < 128; i++ {
		n, err := rand.Int(rand.Reader, big.NewInt(900000))
		var candidate string
		if err != nil {
			candidate = fmt.Sprintf("%06d", 100000+(time.Now().UnixNano()%900000))
		} else {
			candidate = fmt.Sprintf("%06d", 100000+n.Int64())
		}
		if _, exists := s.records[candidate]; exists {
			continue
		}
		if _, expired := s.expiredCodes[candidate]; expired {
			continue
		}
		return candidate
	}
	// 极端情况下退避：仍生成 6 位数字
	return fmt.Sprintf("%06d", 100000+(time.Now().UnixNano()%900000))
}

func (s *Store) lookupActiveLocked(code string) (*pickupRecord, error) {
	clean := strings.TrimSpace(code)
	if !pickupCodeRegex.MatchString(clean) {
		return nil, ErrPickupNotFound
	}
	rec, ok := s.records[clean]
	if !ok {
		if _, wasExpired := s.expiredCodes[clean]; wasExpired {
			return nil, ErrPickupExpired
		}
		return nil, ErrPickupNotFound
	}
	if s.isExpiredLocked(rec, s.now().UnixMilli()) {
		s.deleteRecordLocked(clean, true)
		return nil, ErrPickupExpired
	}
	return rec, nil
}

func (s *Store) deleteRecordLocked(code string, markExpired bool) {
	delete(s.records, code)
	_ = os.RemoveAll(s.pickupDir(code))
	if markExpired {
		s.expiredCodes[code] = s.now().UnixMilli()
	}
}

func (s *Store) purgeExpiredLocked() int {
	nowMs := s.now().UnixMilli()
	purged := 0
	for code, rec := range s.records {
		if s.isExpiredLocked(rec, nowMs) {
			s.deleteRecordLocked(code, true)
			purged++
		}
	}
	cutoff := nowMs - int64(24*time.Hour/time.Millisecond)
	for code, ts := range s.expiredCodes {
		if ts < cutoff {
			delete(s.expiredCodes, code)
		}
	}
	return purged
}

// PurgeExpired 清理所有已过期或超过焚毁宽限期的取件码
func (s *Store) PurgeExpired() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.purgeExpiredLocked()
}

// GetUploadedBytes 查询指定文件当前已上传落盘的字节数（供断点续传 HEAD 使用，需校验 ownerToken）
func (s *Store) GetUploadedBytes(code, fileID, ownerToken string) (int64, error) {
	if !safeFileIDRegex.MatchString(fileID) {
		return 0, ErrFileNotFound
	}
	s.mu.Lock()
	defer s.mu.Unlock()

	rec, err := s.lookupActiveLocked(code)
	if err != nil {
		return 0, err
	}
	if !verifyOwnerToken(rec.OwnerTokenHash, ownerToken) {
		return 0, ErrNotOwner
	}
	var target *fileRecord
	for _, f := range rec.Files {
		if f.FileID == fileID {
			target = f
			break
		}
	}
	if target == nil {
		return 0, ErrFileNotFound
	}
	info, statErr := os.Stat(s.dataPath(rec.Code, fileID))
	if statErr != nil {
		return 0, nil
	}
	return info.Size(), nil
}

// UploadFile 上传或断点续传取件码内的单个文件流
func (s *Store) UploadFile(code, fileID, ownerToken string, offset int64, body io.Reader) error {
	if !safeFileIDRegex.MatchString(fileID) {
		return ErrFileNotFound
	}
	if offset < 0 {
		return fmt.Errorf("%w：X-Upload-Offset 不能为负数", ErrInvalidRequest)
	}

	s.mu.Lock()
	rec, err := s.lookupActiveLocked(code)
	if err != nil {
		s.mu.Unlock()
		return err
	}
	if !verifyOwnerToken(rec.OwnerTokenHash, ownerToken) {
		s.mu.Unlock()
		return ErrNotOwner
	}
	var target *fileRecord
	for _, f := range rec.Files {
		if f.FileID == fileID {
			target = f
			break
		}
	}
	if target == nil {
		s.mu.Unlock()
		return ErrFileNotFound
	}
	declaredSize := target.Size
	dataFilePath := s.dataPath(rec.Code, fileID)
	dir := s.pickupDir(rec.Code)
	s.mu.Unlock()

	if offset > declaredSize {
		return fmt.Errorf("%w：续传偏移超出文件申报大小", ErrInvalidRequest)
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}

	var currentSize int64
	if st, statErr := os.Stat(dataFilePath); statErr == nil {
		currentSize = st.Size()
	}
	if offset > currentSize {
		return fmt.Errorf("%w：续传偏移 %d 大于服务端已接收字节 %d", ErrInvalidRequest, offset, currentSize)
	}

	flags := os.O_CREATE | os.O_WRONLY
	if offset == 0 {
		flags |= os.O_TRUNC
	}
	f, err := os.OpenFile(dataFilePath, flags, 0o644)
	if err != nil {
		return err
	}
	if offset > 0 {
		if err := f.Truncate(offset); err != nil {
			_ = f.Close()
			return err
		}
		if _, err := f.Seek(offset, io.SeekStart); err != nil {
			_ = f.Close()
			return err
		}
	}

	remainingAllowed := declaredSize - offset
	n, copyErr := io.Copy(f, io.LimitReader(body, remainingAllowed+1))
	closeErr := f.Close()
	if copyErr != nil {
		// 网络中断等读取失败时保留已写入的断点字节，供后续 HEAD + X-Upload-Offset 续传
		return copyErr
	}
	if closeErr != nil {
		return closeErr
	}

	finalSize := offset + n
	if finalSize > declaredSize {
		_ = os.Remove(dataFilePath)
		return ErrSizeMismatch
	}
	if finalSize != declaredSize {
		return ErrSizeMismatch
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	rec, err = s.lookupActiveLocked(code)
	if err != nil {
		return err
	}
	for _, fr := range rec.Files {
		if fr.FileID == fileID {
			fr.Ready = true
			break
		}
	}
	return s.saveRecordLocked(rec)
}

func checkPickupPassword(rec *pickupRecord, password string) error {
	if rec.PasswordHash == "" {
		return nil
	}
	if strings.TrimSpace(password) == "" {
		return ErrPickupPasswordRequired
	}
	if !verifyPassword(rec.PasswordHash, password) {
		return ErrWrongPassword
	}
	return nil
}

// GetInfo 查询取件码元信息（含可选密码校验）
func (s *Store) GetInfo(code, password string) (*PickupInfo, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	rec, err := s.lookupActiveLocked(code)
	if err != nil {
		return nil, err
	}
	if err := checkPickupPassword(rec, password); err != nil {
		return nil, err
	}

	files := make([]FileInfo, 0, len(rec.Files))
	for _, f := range rec.Files {
		files = append(files, FileInfo{
			FileID: f.FileID,
			Name:   f.Name,
			Size:   f.Size,
			Mime:   f.Mime,
			Ready:  f.Ready,
		})
	}
	return &PickupInfo{
		Code:          rec.Code,
		Files:         files,
		ExpiresAt:     rec.ExpiresAt,
		BurnAfterRead: rec.BurnAfterRead,
		Downloads:     rec.Downloads,
		HasPassword:   rec.PasswordHash != "",
		BurnedAt:      rec.BurnedAt,
	}, nil
}

// OpenFile 打开指定文件用于下载（校验密码与文件就绪状态）
func (s *Store) OpenFile(code, fileID, password string) (*os.File, FileInfo, time.Time, error) {
	if !safeFileIDRegex.MatchString(fileID) {
		return nil, FileInfo{}, time.Time{}, ErrFileNotFound
	}

	s.mu.Lock()
	rec, err := s.lookupActiveLocked(code)
	if err != nil {
		s.mu.Unlock()
		return nil, FileInfo{}, time.Time{}, err
	}
	if err := checkPickupPassword(rec, password); err != nil {
		s.mu.Unlock()
		return nil, FileInfo{}, time.Time{}, err
	}
	var target *fileRecord
	for _, f := range rec.Files {
		if f.FileID == fileID {
			target = f
			break
		}
	}
	if target == nil {
		s.mu.Unlock()
		return nil, FileInfo{}, time.Time{}, ErrFileNotFound
	}
	if !target.Ready {
		s.mu.Unlock()
		return nil, FileInfo{}, time.Time{}, ErrFileNotReady
	}
	info := FileInfo{
		FileID: target.FileID,
		Name:   target.Name,
		Size:   target.Size,
		Mime:   target.Mime,
		Ready:  target.Ready,
	}
	path := s.dataPath(rec.Code, fileID)
	s.mu.Unlock()

	f, err := os.Open(path)
	if err != nil {
		return nil, FileInfo{}, time.Time{}, ErrFileNotFound
	}
	stat, err := f.Stat()
	if err != nil || stat.IsDir() {
		_ = f.Close()
		return nil, FileInfo{}, time.Time{}, ErrFileNotFound
	}
	return f, info, stat.ModTime(), nil
}

// MarkFileDownloaded 在文件完整下载后递增计数，并在开启取后即焚且全部文件均至少完整下载一次后记录 burnedAt
func (s *Store) MarkFileDownloaded(code, fileID string) {
	s.mu.Lock()
	defer s.mu.Unlock()

	rec, err := s.lookupActiveLocked(code)
	if err != nil {
		return
	}
	found := false
	allDownloaded := len(rec.Files) > 0
	for _, f := range rec.Files {
		if f.FileID == fileID {
			f.DownloadedCount++
			found = true
		}
		if f.DownloadedCount <= 0 {
			allDownloaded = false
		}
	}
	if !found {
		return
	}
	rec.Downloads++
	if rec.BurnAfterRead && allDownloaded && rec.BurnedAt == 0 {
		rec.BurnedAt = s.now().UnixMilli()
	}
	_ = s.saveRecordLocked(rec)
}

// Delete 创建者主动撤销删除取件码及磁盘文件
func (s *Store) Delete(code, ownerToken string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	rec, err := s.lookupActiveLocked(code)
	if err != nil {
		return err
	}
	if !verifyOwnerToken(rec.OwnerTokenHash, ownerToken) {
		return ErrNotOwner
	}
	s.deleteRecordLocked(rec.Code, false)
	return nil
}

// ServeFileDownload 使用 http.ServeContent 提供支持 Range 的安全附件下载，并精准统计完整下载次数
func (s *Store) ServeFileDownload(w http.ResponseWriter, r *http.Request, code, fileID, password string) error {
	f, meta, modTime, err := s.OpenFile(code, fileID, password)
	if err != nil {
		return err
	}
	defer func() { _ = f.Close() }()

	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Content-Security-Policy", "sandbox")
	w.Header().Set(
		"Content-Disposition",
		`attachment; filename="download.bin"; filename*=UTF-8''`+url.PathEscape(meta.Name),
	)

	tw := &downloadTrackingWriter{ResponseWriter: w, statusCode: http.StatusOK}
	http.ServeContent(tw, r, meta.Name, modTime, f)

	if r.Method == http.MethodGet && tw.writeErr == nil && isCompleteDownload(tw.statusCode, tw.bytesWritten, tw.Header().Get("Content-Range"), meta.Size) {
		s.MarkFileDownloaded(code, fileID)
	}
	return nil
}

type downloadTrackingWriter struct {
	http.ResponseWriter
	statusCode   int
	bytesWritten int64
	writeErr     error
}

func (tw *downloadTrackingWriter) WriteHeader(code int) {
	tw.statusCode = code
	tw.ResponseWriter.WriteHeader(code)
}

func (tw *downloadTrackingWriter) Write(p []byte) (int, error) {
	n, err := tw.ResponseWriter.Write(p)
	tw.bytesWritten += int64(n)
	if err != nil {
		tw.writeErr = err
	}
	return n, err
}

func isCompleteDownload(statusCode int, written int64, contentRange string, totalSize int64) bool {
	if totalSize <= 0 {
		return false
	}
	if statusCode == http.StatusOK {
		return written == totalSize
	}
	if statusCode == http.StatusPartialContent {
		// Content-Range 格式：bytes start-end/total
		rest := strings.TrimPrefix(contentRange, "bytes ")
		slashIdx := strings.IndexByte(rest, '/')
		if slashIdx <= 0 {
			return false
		}
		dashIdx := strings.IndexByte(rest[:slashIdx], '-')
		if dashIdx <= 0 {
			return false
		}
		start, err1 := strconv.ParseInt(rest[:dashIdx], 10, 64)
		end, err2 := strconv.ParseInt(rest[dashIdx+1:slashIdx], 10, 64)
		if err1 != nil || err2 != nil {
			return false
		}
		return end == totalSize-1 && written == (end-start+1)
	}
	return false
}

func sha256Hex(s string) string {
	sum := sha256.Sum256([]byte(strings.TrimSpace(s)))
	return hex.EncodeToString(sum[:])
}

func hashPassword(password string) string {
	pw := strings.TrimSpace(password)
	if pw == "" {
		return ""
	}
	salt := randomHex(12)
	sum := sha256.Sum256([]byte(salt + ":" + pw))
	return salt + ":" + hex.EncodeToString(sum[:])
}

func verifyPassword(storedHash, candidate string) bool {
	if storedHash == "" {
		return true
	}
	parts := strings.SplitN(storedHash, ":", 2)
	if len(parts) != 2 {
		return false
	}
	sum := sha256.Sum256([]byte(parts[0] + ":" + strings.TrimSpace(candidate)))
	expected := hex.EncodeToString(sum[:])
	return subtle.ConstantTimeCompare([]byte(parts[1]), []byte(expected)) == 1
}

func verifyOwnerToken(storedHash, candidate string) bool {
	if storedHash == "" {
		return false
	}
	clean := strings.TrimSpace(candidate)
	if clean == "" {
		return false
	}
	expected := sha256Hex(clean)
	return subtle.ConstantTimeCompare([]byte(storedHash), []byte(expected)) == 1
}

func sanitizeFileName(raw string) string {
	s := strings.TrimSpace(raw)
	s = strings.ReplaceAll(s, "\\", "_")
	s = strings.ReplaceAll(s, "/", "_")
	s = strings.ReplaceAll(s, "\r", "")
	s = strings.ReplaceAll(s, "\n", "")
	s = strings.ReplaceAll(s, "\"", "_")
	if s == "" || s == "." || s == ".." {
		return "file.bin"
	}
	r := []rune(s)
	if len(r) > 140 {
		return string(r[:140])
	}
	return s
}

func randomHex(nBytes int) string {
	buf := make([]byte, nBytes)
	if _, err := rand.Read(buf); err != nil {
		return fmt.Sprintf("%016x", time.Now().UnixNano())
	}
	return hex.EncodeToString(buf)
}
