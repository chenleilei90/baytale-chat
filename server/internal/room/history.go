package room

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"
)

const (
	// MaxRetentionDays 服务器聊天记录缓存的最长保留天数（严格不超过 7 天）
	MaxRetentionDays = 7
)

var (
	// ErrInvalidRetentionDays 当保留天数不在 [0, 7] 范围内时返回
	ErrInvalidRetentionDays = errors.New("聊天记录保留天数必须在 0 到 7 天之间")
	// ErrMediaTooLarge 当缓存图片超过服务端允许上限时返回
	ErrMediaTooLarge = errors.New("缓存图片体积超过限制")
	// ErrMediaNotFound 当请求的缓存媒体不存在或已过期删除时返回
	ErrMediaNotFound = errors.New("缓存媒体不存在或已被清理")
	// ErrUnsupportedMediaType 当上传的缓存媒体不是允许的安全图片/语音格式时返回
	ErrUnsupportedMediaType = errors.New("不支持缓存该媒体类型，仅支持安全图片或语音格式")
	safeMediaIDRegex        = regexp.MustCompile(`^[a-zA-Z0-9_-]{8,64}$`)
	safeRoomDirRegex        = regexp.MustCompile(`^[a-z0-9][a-z0-9_-]{2,23}$`)
)

// CachedMessage 表示一条缓存在服务端的房间聊天记录
type CachedMessage struct {
	ID             string `json:"id"`
	Kind           string `json:"kind"` // text | image | audio | file_notice
	SenderID       string `json:"senderId"`
	SenderName     string `json:"senderName"`
	SenderAvatar   string `json:"senderAvatar,omitempty"`
	Text           string `json:"text,omitempty"`
	Caption        string `json:"caption,omitempty"`
	MediaID        string `json:"mediaId,omitempty"`
	MediaMime      string `json:"mediaMime,omitempty"`
	FileName       string `json:"fileName,omitempty"`
	FileSize       int64  `json:"fileSize,omitempty"`
	IsScreenshot   bool   `json:"isScreenshot,omitempty"`
	ReplyToID      string `json:"replyToId,omitempty"`
	ReplyToSender  string `json:"replyToSender,omitempty"`
	ReplyToPreview string `json:"replyToPreview,omitempty"`
	Timestamp      int64  `json:"timestamp"` // 毫秒时间戳
}

// HistoryMeta 表示房间当前缓存状态与过期倒计时信息
type HistoryMeta struct {
	RetentionDays int   `json:"retentionDays"`           // 0 表示不缓存/立即销毁，1~7 表示最后聊天后保留天数
	MessageCount  int   `json:"messageCount"`            // 当前缓存消息数量
	LastMessageAt int64 `json:"lastMessageAt,omitempty"` // 最后一次聊天时间（毫秒时间戳）
	ExpiresAt     int64 `json:"expiresAt,omitempty"`     // 预计自动过期删除时间（毫秒时间戳）
}

type roomHistoryDisk struct {
	RoomCode      string          `json:"roomCode"`
	RetentionDays int             `json:"retentionDays"`
	LastMessageAt int64           `json:"lastMessageAt"`
	Messages      []CachedMessage `json:"messages"`
}

// HistoryStore 管理各房间的服务器端聊天记录缓存与滑动过期清理
// 核心规则：以该房间最后一次聊天时间为计时起点，最长保留 7 天，支持自助选择 0~7 天或随时立即删除
type HistoryStore struct {
	mu            sync.RWMutex
	baseDir       string
	maxMessages   int
	maxMediaBytes int64
	nowFunc       func() time.Time
	cache         map[string]*roomHistoryDisk
}

// NewHistoryStore 初始化聊天记录缓存管理器并加载磁盘已有未过期数据
func NewHistoryStore(dataDir string, maxMessages int, maxMediaBytes int64) (*HistoryStore, error) {
	baseDir := filepath.Join(dataDir, "history")
	if err := os.MkdirAll(baseDir, 0o755); err != nil {
		return nil, fmt.Errorf("创建历史缓存目录失败: %w", err)
	}
	if maxMessages <= 0 {
		maxMessages = 500
	}
	if maxMediaBytes <= 0 {
		maxMediaBytes = 5 * 1024 * 1024
	}
	hs := &HistoryStore{
		baseDir:       baseDir,
		maxMessages:   maxMessages,
		maxMediaBytes: maxMediaBytes,
		nowFunc:       time.Now,
		cache:         make(map[string]*roomHistoryDisk),
	}
	if err := hs.loadFromDisk(); err != nil {
		return nil, err
	}
	hs.PurgeExpired()
	return hs, nil
}

// ValidateRetentionDays 校验保留天数是否合法（0~7 天）
func ValidateRetentionDays(days int) error {
	if days < 0 || days > MaxRetentionDays {
		return ErrInvalidRetentionDays
	}
	return nil
}

// SetNowFunc 仅供单元测试注入可控时钟
func (hs *HistoryStore) SetNowFunc(fn func() time.Time) {
	hs.mu.Lock()
	defer hs.mu.Unlock()
	hs.nowFunc = fn
}

func (hs *HistoryStore) now() time.Time {
	if hs.nowFunc != nil {
		return hs.nowFunc()
	}
	return time.Now()
}

func (hs *HistoryStore) roomDir(code string) string {
	return filepath.Join(hs.baseDir, code)
}

func (hs *HistoryStore) mediaDir(code string) string {
	return filepath.Join(hs.roomDir(code), "media")
}

func (hs *HistoryStore) metaFile(code string) string {
	return filepath.Join(hs.roomDir(code), "history.json")
}

func (hs *HistoryStore) loadFromDisk() error {
	entries, err := os.ReadDir(hs.baseDir)
	if err != nil {
		return err
	}
	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}
		code := entry.Name()
		raw, err := os.ReadFile(hs.metaFile(code))
		if err != nil {
			continue
		}
		var data roomHistoryDisk
		if err := json.Unmarshal(raw, &data); err != nil {
			continue
		}
		if data.RoomCode == "" {
			data.RoomCode = code
		}
		if data.RetentionDays > MaxRetentionDays {
			data.RetentionDays = MaxRetentionDays
		}
		if data.RetentionDays < 0 {
			data.RetentionDays = 0
		}
		hs.cache[code] = &data
	}
	return nil
}

func (hs *HistoryStore) computeExpiresAt(retentionDays int, lastMessageAt int64) int64 {
	if retentionDays <= 0 || lastMessageAt <= 0 {
		return 0
	}
	if retentionDays > MaxRetentionDays {
		retentionDays = MaxRetentionDays
	}
	return lastMessageAt + int64(retentionDays)*int64(24*time.Hour/time.Millisecond)
}

func (hs *HistoryStore) isExpiredLocked(data *roomHistoryDisk, nowMs int64) bool {
	if data == nil {
		return false
	}
	if data.RetentionDays <= 0 {
		return len(data.Messages) > 0
	}
	if data.LastMessageAt <= 0 {
		return false
	}
	expiresAt := hs.computeExpiresAt(data.RetentionDays, data.LastMessageAt)
	return nowMs >= expiresAt
}

func (hs *HistoryStore) saveRoomLocked(data *roomHistoryDisk) error {
	dir := hs.roomDir(data.RoomCode)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	payload, err := json.Marshal(data)
	if err != nil {
		return err
	}
	tmpPath := hs.metaFile(data.RoomCode) + ".tmp"
	if err := os.WriteFile(tmpPath, payload, 0o644); err != nil {
		return err
	}
	return os.Rename(tmpPath, hs.metaFile(data.RoomCode))
}

func (hs *HistoryStore) clearRoomDiskLocked(code string) {
	delete(hs.cache, code)
	_ = os.RemoveAll(hs.roomDir(code))
}

// GetHistory 获取房间当前的未过期聊天记录与缓存元数据
func (hs *HistoryStore) GetHistory(code string, defaultRetentionDays int) ([]CachedMessage, HistoryMeta) {
	hs.mu.Lock()
	defer hs.mu.Unlock()

	nowMs := hs.now().UnixMilli()
	data, ok := hs.cache[code]
	if !ok {
		return []CachedMessage{}, HistoryMeta{
			RetentionDays: defaultRetentionDays,
			MessageCount:  0,
		}
	}

	if hs.isExpiredLocked(data, nowMs) {
		retention := data.RetentionDays
		hs.clearRoomDiskLocked(code)
		return []CachedMessage{}, HistoryMeta{
			RetentionDays: retention,
			MessageCount:  0,
		}
	}

	// 复制切片避免并发修改风险
	msgs := make([]CachedMessage, len(data.Messages))
	copy(msgs, data.Messages)

	return msgs, HistoryMeta{
		RetentionDays: data.RetentionDays,
		MessageCount:  len(msgs),
		LastMessageAt: data.LastMessageAt,
		ExpiresAt:     hs.computeExpiresAt(data.RetentionDays, data.LastMessageAt),
	}
}

// AppendMessage 追加一条聊天记录并刷新最后一次聊天时间（重置滑动过期计时器）
func (hs *HistoryStore) AppendMessage(code string, retentionDays int, msg CachedMessage) (HistoryMeta, bool, error) {
	if err := ValidateRetentionDays(retentionDays); err != nil {
		return HistoryMeta{}, false, err
	}

	hs.mu.Lock()
	defer hs.mu.Unlock()

	// 保留天数为 0 表示“立即删除/不缓存”，服务端不写入任何记录
	if retentionDays == 0 {
		if _, exists := hs.cache[code]; exists {
			hs.clearRoomDiskLocked(code)
		}
		return HistoryMeta{
			RetentionDays: 0,
			MessageCount:  0,
		}, false, nil
	}

	nowMs := hs.now().UnixMilli()
	if msg.Timestamp <= 0 {
		msg.Timestamp = nowMs
	}
	if msg.ID == "" {
		msg.ID = randomHex(12)
	}

	data, ok := hs.cache[code]
	if !ok || hs.isExpiredLocked(data, nowMs) {
		if ok {
			hs.clearRoomDiskLocked(code)
		}
		data = &roomHistoryDisk{
			RoomCode:      code,
			RetentionDays: retentionDays,
			Messages:      make([]CachedMessage, 0, 16),
		}
		hs.cache[code] = data
	}

	data.RetentionDays = retentionDays

	// 按消息 ID 去重，避免双端同时上报导致重复记录
	for _, existing := range data.Messages {
		if existing.ID == msg.ID {
			return HistoryMeta{
				RetentionDays: data.RetentionDays,
				MessageCount:  len(data.Messages),
				LastMessageAt: data.LastMessageAt,
				ExpiresAt:     hs.computeExpiresAt(data.RetentionDays, data.LastMessageAt),
			}, false, nil
		}
	}

	data.Messages = append(data.Messages, msg)
	// 若超出单房间最大缓存条数，裁剪最早的消息并删除其对应缓存媒体/附件与元数据副文件
	if len(data.Messages) > hs.maxMessages {
		overflow := len(data.Messages) - hs.maxMessages
		for i := 0; i < overflow; i++ {
			if data.Messages[i].MediaID != "" {
				mediaFile := filepath.Join(hs.mediaDir(code), data.Messages[i].MediaID)
				_ = os.Remove(mediaFile)
				_ = os.Remove(mediaFile + ".mime")
				_ = os.Remove(mediaFile + ".meta.json")
			}
		}
		data.Messages = data.Messages[overflow:]
	}

	// 以最后一次聊天时间为新的计时起点
	if nowMs > data.LastMessageAt {
		data.LastMessageAt = nowMs
	}

	if err := hs.saveRoomLocked(data); err != nil {
		return HistoryMeta{}, false, err
	}

	return HistoryMeta{
		RetentionDays: data.RetentionDays,
		MessageCount:  len(data.Messages),
		LastMessageAt: data.LastMessageAt,
		ExpiresAt:     hs.computeExpiresAt(data.RetentionDays, data.LastMessageAt),
	}, true, nil
}

// SetRetention 更新房间的聊天记录保留策略；若设置为 0 或调整后已过期，则立即彻底清空缓存
func (hs *HistoryStore) SetRetention(code string, retentionDays int) (HistoryMeta, bool, error) {
	if err := ValidateRetentionDays(retentionDays); err != nil {
		return HistoryMeta{}, false, err
	}

	hs.mu.Lock()
	defer hs.mu.Unlock()

	if retentionDays == 0 {
		cleared := false
		if data, exists := hs.cache[code]; exists && len(data.Messages) > 0 {
			cleared = true
		}
		hs.clearRoomDiskLocked(code)
		return HistoryMeta{
			RetentionDays: 0,
			MessageCount:  0,
		}, cleared, nil
	}

	data, ok := hs.cache[code]
	if !ok {
		return HistoryMeta{
			RetentionDays: retentionDays,
			MessageCount:  0,
		}, false, nil
	}

	data.RetentionDays = retentionDays
	nowMs := hs.now().UnixMilli()
	if hs.isExpiredLocked(data, nowMs) {
		hs.clearRoomDiskLocked(code)
		return HistoryMeta{
			RetentionDays: retentionDays,
			MessageCount:  0,
		}, true, nil
	}

	if err := hs.saveRoomLocked(data); err != nil {
		return HistoryMeta{}, false, err
	}

	return HistoryMeta{
		RetentionDays: data.RetentionDays,
		MessageCount:  len(data.Messages),
		LastMessageAt: data.LastMessageAt,
		ExpiresAt:     hs.computeExpiresAt(data.RetentionDays, data.LastMessageAt),
	}, false, nil
}

// ClearRoom 立即物理删除指定房间的全部服务器缓存聊天记录与图片文件
func (hs *HistoryStore) ClearRoom(code string, retentionDays int) HistoryMeta {
	hs.mu.Lock()
	defer hs.mu.Unlock()

	hs.clearRoomDiskLocked(code)
	return HistoryMeta{
		RetentionDays: retentionDays,
		MessageCount:  0,
	}
}

// DeleteMessage 撤回并物理删除指定发送者的单条缓存消息（同步删除其关联媒体与 .mime 副文件）
// 返回值依次为：最新缓存元数据、是否实际删除、是否因非本人消息而越权被拒
func (hs *HistoryStore) DeleteMessage(code, msgID, senderID string, defaultRetentionDays int) (HistoryMeta, bool, bool) {
	if msgID == "" {
		return HistoryMeta{RetentionDays: defaultRetentionDays}, false, false
	}

	hs.mu.Lock()
	defer hs.mu.Unlock()

	data, ok := hs.cache[code]
	if !ok {
		return HistoryMeta{RetentionDays: defaultRetentionDays, MessageCount: 0}, false, false
	}

	idx := -1
	for i, m := range data.Messages {
		if m.ID == msgID {
			if senderID != "" && m.SenderID != senderID {
				return HistoryMeta{
					RetentionDays: data.RetentionDays,
					MessageCount:  len(data.Messages),
					LastMessageAt: data.LastMessageAt,
					ExpiresAt:     hs.computeExpiresAt(data.RetentionDays, data.LastMessageAt),
				}, false, true
			}
			idx = i
			break
		}
	}
	if idx < 0 {
		return HistoryMeta{
			RetentionDays: data.RetentionDays,
			MessageCount:  len(data.Messages),
			LastMessageAt: data.LastMessageAt,
			ExpiresAt:     hs.computeExpiresAt(data.RetentionDays, data.LastMessageAt),
		}, false, false
	}

	removed := data.Messages[idx]
	if removed.MediaID != "" {
		mediaFile := filepath.Join(hs.mediaDir(code), removed.MediaID)
		_ = os.Remove(mediaFile)
		_ = os.Remove(mediaFile + ".mime")
		_ = os.Remove(mediaFile + ".meta.json")
	}

	data.Messages = append(data.Messages[:idx], data.Messages[idx+1:]...)
	if len(data.Messages) == 0 {
		retention := data.RetentionDays
		hs.clearRoomDiskLocked(code)
		return HistoryMeta{
			RetentionDays: retention,
			MessageCount:  0,
		}, true, false
	}

	_ = hs.saveRoomLocked(data)
	return HistoryMeta{
		RetentionDays: data.RetentionDays,
		MessageCount:  len(data.Messages),
		LastMessageAt: data.LastMessageAt,
		ExpiresAt:     hs.computeExpiresAt(data.RetentionDays, data.LastMessageAt),
	}, true, false
}

// SaveMedia 将聊天图片/截图/语音二进制数据保存到房间缓存目录（严格基于魔数校验，阻断 HTML/SVG 存储型 XSS）
func (hs *HistoryStore) SaveMedia(code string, retentionDays int, declaredMime string, content []byte) (string, string, error) {
	if !safeRoomDirRegex.MatchString(code) {
		return "", "", ErrInvalidRoomCode
	}
	if retentionDays <= 0 {
		return "", "", errors.New("当前房间已关闭服务器缓存")
	}
	if len(content) == 0 {
		return "", "", errors.New("上传内容为空")
	}
	if int64(len(content)) > hs.maxMediaBytes {
		return "", "", ErrMediaTooLarge
	}

	safeMime, ok := detectSafeMediaMIME(content, declaredMime)
	if !ok {
		return "", "", ErrUnsupportedMediaType
	}

	hs.mu.Lock()
	defer hs.mu.Unlock()

	dir := hs.mediaDir(code)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", "", err
	}

	mediaID := randomHex(16)
	mediaPath := filepath.Join(dir, mediaID)
	metaPath := mediaPath + ".mime"

	if err := os.WriteFile(mediaPath, content, 0o644); err != nil {
		return "", "", err
	}
	_ = os.WriteFile(metaPath, []byte(safeMime), 0o644)
	return mediaID, safeMime, nil
}

// detectSafeMediaMIME 通过文件内容魔数校验安全 MIME，彻底拒绝 text/html 与 image/svg+xml
func detectSafeMediaMIME(content []byte, declaredMime string) (string, bool) {
	detected := http.DetectContentType(content)
	switch detected {
	case "image/png", "image/jpeg", "image/webp", "image/gif":
		return detected, true
	case "audio/ogg", "audio/mpeg", "audio/wav", "audio/webm":
		return detected, true
	}
	// WebM / Ogg / MP4 语音流在部分浏览器中 DetectContentType 可能返回 video/webm 或 application/octet-stream
	lower := strings.ToLower(strings.TrimSpace(strings.Split(declaredMime, ";")[0]))
	if (detected == "video/webm" || detected == "application/octet-stream") &&
		(lower == "audio/webm" || lower == "audio/ogg" || lower == "audio/mp4") {
		// 二次核验常见容器魔数（EBML 0x1A45DFA3 / OggS / ftyp）
		if len(content) >= 8 {
			if (content[0] == 0x1a && content[1] == 0x45 && content[2] == 0xdf && content[3] == 0xa3) ||
				string(content[:4]) == "OggS" ||
				string(content[4:8]) == "ftyp" {
				return lower, true
			}
		}
	}
	return "", false
}

// LoadMedia 读取指定房间的缓存媒体文件，读取前自动校验房间缓存是否已过期
func (hs *HistoryStore) LoadMedia(code, mediaID string) ([]byte, string, error) {
	if !safeRoomDirRegex.MatchString(code) || !safeMediaIDRegex.MatchString(mediaID) {
		return nil, "", ErrMediaNotFound
	}

	hs.mu.Lock()
	defer hs.mu.Unlock()

	nowMs := hs.now().UnixMilli()
	if data, ok := hs.cache[code]; ok && hs.isExpiredLocked(data, nowMs) {
		hs.clearRoomDiskLocked(code)
		return nil, "", ErrMediaNotFound
	}

	mediaPath := filepath.Join(hs.mediaDir(code), mediaID)
	raw, err := os.ReadFile(mediaPath)
	if err != nil {
		return nil, "", ErrMediaNotFound
	}
	mimeRaw, err := os.ReadFile(mediaPath + ".mime")
	mimeType := "application/octet-stream"
	if err == nil && len(mimeRaw) > 0 {
		mimeType = string(mimeRaw)
	}
	return raw, mimeType, nil
}

// CachedFileMeta 表示服务器限时缓存的普通历史附件元信息（始终以 attachment 下载，杜绝 XSS）
type CachedFileMeta struct {
	FileName string `json:"fileName"`
	MimeType string `json:"mimeType"`
	Size     int64  `json:"size"`
}

// SaveCachedFile 流式保存普通历史附件至房间限时缓存目录（受 maxBytes 限制，到期或清房自动物理清除）
func (hs *HistoryStore) SaveCachedFile(code string, retentionDays int, fileName, mimeType string, body io.Reader, maxBytes int64) (string, CachedFileMeta, error) {
	if !safeRoomDirRegex.MatchString(code) {
		return "", CachedFileMeta{}, ErrInvalidRoomCode
	}
	if retentionDays <= 0 {
		return "", CachedFileMeta{}, errors.New("当前房间已关闭服务器缓存")
	}
	if maxBytes <= 0 {
		maxBytes = 100 * 1024 * 1024
	}
	cleanName := sanitizeAttachmentFileName(fileName)
	cleanMime := strings.TrimSpace(strings.Split(mimeType, ";")[0])
	if cleanMime == "" {
		cleanMime = "application/octet-stream"
	}

	dir := hs.mediaDir(code)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", CachedFileMeta{}, err
	}

	fileID := randomHex(16)
	targetPath := filepath.Join(dir, fileID)
	tmpPath := targetPath + ".tmp"

	f, err := os.OpenFile(tmpPath, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644)
	if err != nil {
		return "", CachedFileMeta{}, err
	}
	n, copyErr := io.Copy(f, io.LimitReader(body, maxBytes+1))
	closeErr := f.Close()
	if copyErr != nil {
		_ = os.Remove(tmpPath)
		return "", CachedFileMeta{}, copyErr
	}
	if closeErr != nil {
		_ = os.Remove(tmpPath)
		return "", CachedFileMeta{}, closeErr
	}
	if n == 0 {
		_ = os.Remove(tmpPath)
		return "", CachedFileMeta{}, errors.New("上传附件为空")
	}
	if n > maxBytes {
		_ = os.Remove(tmpPath)
		return "", CachedFileMeta{}, ErrMediaTooLarge
	}

	meta := CachedFileMeta{
		FileName: cleanName,
		MimeType: cleanMime,
		Size:     n,
	}
	metaBytes, _ := json.Marshal(meta)

	hs.mu.Lock()
	defer hs.mu.Unlock()
	if err := os.Rename(tmpPath, targetPath); err != nil {
		_ = os.Remove(tmpPath)
		return "", CachedFileMeta{}, err
	}
	_ = os.WriteFile(targetPath+".meta.json", metaBytes, 0o644)
	return fileID, meta, nil
}

// OpenCachedFile 返回服务器限时缓存历史附件的文件句柄与元信息（供 http.ServeContent 支持断点续传下载）
func (hs *HistoryStore) OpenCachedFile(code, fileID string) (*os.File, CachedFileMeta, time.Time, error) {
	if !safeRoomDirRegex.MatchString(code) || !safeMediaIDRegex.MatchString(fileID) {
		return nil, CachedFileMeta{}, time.Time{}, ErrMediaNotFound
	}

	hs.mu.Lock()
	nowMs := hs.now().UnixMilli()
	if data, ok := hs.cache[code]; ok && hs.isExpiredLocked(data, nowMs) {
		hs.clearRoomDiskLocked(code)
		hs.mu.Unlock()
		return nil, CachedFileMeta{}, time.Time{}, ErrMediaNotFound
	}
	hs.mu.Unlock()

	targetPath := filepath.Join(hs.mediaDir(code), fileID)
	f, err := os.Open(targetPath)
	if err != nil {
		return nil, CachedFileMeta{}, time.Time{}, ErrMediaNotFound
	}
	stat, err := f.Stat()
	if err != nil || stat.IsDir() {
		_ = f.Close()
		return nil, CachedFileMeta{}, time.Time{}, ErrMediaNotFound
	}

	meta := CachedFileMeta{
		FileName: fileID,
		MimeType: "application/octet-stream",
		Size:     stat.Size(),
	}
	if rawMeta, err := os.ReadFile(targetPath + ".meta.json"); err == nil {
		var parsed CachedFileMeta
		if json.Unmarshal(rawMeta, &parsed) == nil {
			if parsed.FileName != "" {
				meta.FileName = sanitizeAttachmentFileName(parsed.FileName)
			}
			if parsed.MimeType != "" {
				meta.MimeType = parsed.MimeType
			}
		}
	}
	return f, meta, stat.ModTime(), nil
}

func sanitizeAttachmentFileName(raw string) string {
	s := strings.TrimSpace(raw)
	s = strings.ReplaceAll(s, "\\", "_")
	s = strings.ReplaceAll(s, "/", "_")
	s = strings.ReplaceAll(s, "\r", "")
	s = strings.ReplaceAll(s, "\n", "")
	s = strings.ReplaceAll(s, "\"", "_")
	if s == "" || s == "." || s == ".." {
		return "attachment.bin"
	}
	return truncateRunes(s, 140)
}

// PurgeExpired 扫描并彻底清理所有已超过保留期限的房间缓存及孤儿媒体目录
func (hs *HistoryStore) PurgeExpired() int {
	hs.mu.Lock()
	defer hs.mu.Unlock()

	now := hs.now()
	nowMs := now.UnixMilli()
	purged := 0
	for code, data := range hs.cache {
		if hs.isExpiredLocked(data, nowMs) {
			hs.clearRoomDiskLocked(code)
			purged++
		}
	}

	// 清理没有 history.json 且超过 1 小时未活跃的孤儿媒体目录（例如图片传完但页面中断未发消息）
	if entries, err := os.ReadDir(hs.baseDir); err == nil {
		for _, entry := range entries {
			if !entry.IsDir() {
				continue
			}
			code := entry.Name()
			if _, tracked := hs.cache[code]; tracked {
				continue
			}
			if info, err := entry.Info(); err == nil && now.Sub(info.ModTime()) > time.Hour {
				_ = os.RemoveAll(hs.roomDir(code))
				purged++
			}
		}
	}
	return purged
}

func randomHex(nBytes int) string {
	buf := make([]byte, nBytes)
	if _, err := rand.Read(buf); err != nil {
		return fmt.Sprintf("%d", time.Now().UnixNano())
	}
	return hex.EncodeToString(buf)
}
