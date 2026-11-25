package room

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"math/big"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

const (
	// ModeFixed 固定房间模式：房间码永久不变，成员离开后可随时用同房间码再次进入
	ModeFixed = "fixed"
	// ModeEphemeral 一次性房间模式：聊完即销毁，下次自动生成全新房间码
	ModeEphemeral = "ephemeral"
)

var (
	roomCodeRegex = regexp.MustCompile(`^[a-z0-9][a-z0-9_-]{2,23}$`)

	ErrInvalidRoomCode    = errors.New("房间码需为 3~24 位字母、数字、短横线或下划线")
	ErrRoomExists         = errors.New("该房间码已被占用，请更换一个")
	ErrRoomNotFound       = errors.New("房间不存在，请检查房间码是否正确")
	ErrRoomExpired        = errors.New("该一次性房间已结束并销毁，请向对方获取新房间码或新建房间")
	ErrWrongPassword      = errors.New("房间密码错误")
	ErrRoomFull           = errors.New("房间人数已满")
	ErrInvalidMaxFileSize = errors.New("附件大小上限设置无效")
	ErrNoPublicRoom       = errors.New("当前暂无可随机进入的免密公开房间，不妨先建一间等伙伴来串门")
)

const (
	// MaxConfigurableFileSizeMB 单房间可配置的最大有限附件上限（100GB，0 表示不限制）
	MaxConfigurableFileSizeMB = 102400
)

// RoomStats 记录秘密基地的累计互动与成长数据（固定房间持久化，赋予长期留存成就感）
type RoomStats struct {
	TotalMessages  int    `json:"totalMessages"`            // 累计发送消息数
	TotalFiles     int    `json:"totalFiles"`               // 累计闪传文件数
	TotalBytes     int64  `json:"totalBytes"`               // 累计闪传字节总量
	TotalPomodoros int    `json:"totalPomodoros"`           // 累计共同完成的番茄钟专注次数
	StreakDays     int    `json:"streakDays"`               // 连续互动火花天数
	LastActiveDate string `json:"lastActiveDate,omitempty"` // 最近一次互动日期 YYYY-MM-DD
}

// TouchActivity 按自然日刷新基地的连续互动火花天数
func (s *RoomStats) TouchActivity(now time.Time) {
	today := now.Format("2006-01-02")
	if s.LastActiveDate == today {
		if s.StreakDays <= 0 {
			s.StreakDays = 1
		}
		return
	}
	yesterday := now.AddDate(0, 0, -1).Format("2006-01-02")
	if s.LastActiveDate == yesterday {
		s.StreakDays++
	} else {
		s.StreakDays = 1
	}
	s.LastActiveDate = today
}

// FixedRoomRecord 表示持久化在磁盘上的固定房间元数据
type FixedRoomRecord struct {
	Code          string    `json:"code"`
	Mode          string    `json:"mode"`
	PasswordHash  string    `json:"passwordHash,omitempty"`
	MaxPeers      int       `json:"maxPeers"`
	RetentionDays int       `json:"retentionDays"`           // 服务器聊天缓存保留天数（0~7）
	MaxFileSizeMB *int      `json:"maxFileSizeMB,omitempty"` // 单文件附件上限（MB），0 表示不限制，nil 兼容旧版数据
	AllowRandom   *bool     `json:"allowRandom,omitempty"`   // 允许陌生人随机串门（仅免密房有效，默认 true）
	Topic         string    `json:"topic,omitempty"`         // 可选公开房间主题标签
	Stats         RoomStats `json:"stats,omitempty"`         // 基地成长与连续火花统计
	CreatedAt     int64     `json:"createdAt"`
	LastActiveAt  int64     `json:"lastActiveAt"`
}

// ValidateMaxFileSizeMB 校验房间单附件大小上限（0 表示不限制，或 1 ~ MaxConfigurableFileSizeMB MB）
func ValidateMaxFileSizeMB(mb int, serverCapMB int) error {
	if mb < 0 || mb > MaxConfigurableFileSizeMB {
		return fmt.Errorf("%w：需在 0（不限制）到 %d MB 之间", ErrInvalidMaxFileSize, MaxConfigurableFileSizeMB)
	}
	if serverCapMB > 0 && (mb == 0 || mb > serverCapMB) {
		return fmt.Errorf("%w：不可超过服务端配置的最大上限 %d MB", ErrInvalidMaxFileSize, serverCapMB)
	}
	return nil
}

// NormalizeCode 规范化房间码（去空白并转为小写）
func NormalizeCode(code string) string {
	return strings.ToLower(strings.TrimSpace(code))
}

// ValidateCode 校验自定义房间码格式
func ValidateCode(code string) error {
	if !roomCodeRegex.MatchString(code) {
		return ErrInvalidRoomCode
	}
	return nil
}

// HashPassword 使用随机盐对房间密码进行 SHA-256 哈希
func HashPassword(password string) string {
	password = strings.TrimSpace(password)
	if password == "" {
		return ""
	}
	salt := randomHex(12)
	sum := sha256.Sum256([]byte(salt + ":" + password))
	return salt + ":" + hex.EncodeToString(sum[:])
}

// VerifyPassword 使用常量时间比较验证房间密码
func VerifyPassword(storedHash, candidate string) bool {
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

// GenerateSixDigitCode 生成加密安全的 6 位随机数字房间码（100000~999999）
func GenerateSixDigitCode() string {
	n, err := rand.Int(rand.Reader, big.NewInt(900000))
	if err != nil {
		return fmt.Sprintf("%06d", 100000+(time.Now().UnixNano()%900000))
	}
	return fmt.Sprintf("%06d", 100000+n.Int64())
}

// FixedStore 负责将固定房间配置原子写入磁盘 JSON，保障服务端重启后固定房间码不丢失
type FixedStore struct {
	filePath string
}

// NewFixedStore 创建固定房间存储管理器
func NewFixedStore(dataDir string) (*FixedStore, error) {
	if err := os.MkdirAll(dataDir, 0o755); err != nil {
		return nil, fmt.Errorf("创建数据目录失败: %w", err)
	}
	return &FixedStore{
		filePath: filepath.Join(dataDir, "fixed_rooms.json"),
	}, nil
}

// LoadAll 从磁盘加载所有固定房间记录
func (fs *FixedStore) LoadAll() (map[string]*FixedRoomRecord, error) {
	result := make(map[string]*FixedRoomRecord)
	raw, err := os.ReadFile(fs.filePath)
	if err != nil {
		if os.IsNotExist(err) {
			return result, nil
		}
		return nil, err
	}
	var list []*FixedRoomRecord
	if err := json.Unmarshal(raw, &list); err != nil {
		return nil, err
	}
	for _, item := range list {
		if item == nil || item.Code == "" {
			continue
		}
		item.Mode = ModeFixed
		if item.RetentionDays < 0 {
			item.RetentionDays = 0
		}
		if item.RetentionDays > MaxRetentionDays {
			item.RetentionDays = MaxRetentionDays
		}
		result[item.Code] = item
	}
	return result, nil
}

// SaveAll 将固定房间记录通过临时文件原子覆盖写入磁盘
func (fs *FixedStore) SaveAll(records map[string]*FixedRoomRecord) error {
	list := make([]*FixedRoomRecord, 0, len(records))
	for _, r := range records {
		list = append(list, r)
	}
	payload, err := json.MarshalIndent(list, "", "  ")
	if err != nil {
		return err
	}
	tmpPath := fs.filePath + ".tmp"
	if err := os.WriteFile(tmpPath, payload, 0o644); err != nil {
		return err
	}
	return os.Rename(tmpPath, fs.filePath)
}
