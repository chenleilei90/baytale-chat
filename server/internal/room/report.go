package room

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// ErrInvalidReport 表示举报请求缺少必要信息
var ErrInvalidReport = errors.New("举报内容不能为空")

// ReportRequest 表示客户端提交的违规举报请求体
type ReportRequest struct {
	RoomCode         string `json:"roomCode"`
	ReportedName     string `json:"reportedName"`
	ReportedClientID string `json:"reportedClientId"`
	Reason           string `json:"reason"`
	MessageID        string `json:"messageId"`
	ReporterClientID string `json:"reporterClientId"`
}

type reportRecord struct {
	Timestamp        int64  `json:"timestamp"`
	CreatedAt        string `json:"createdAt"`
	IPHash           string `json:"ipHash"`
	RoomCode         string `json:"roomCode"`
	ReportedName     string `json:"reportedName,omitempty"`
	ReportedClientID string `json:"reportedClientId,omitempty"`
	Reason           string `json:"reason"`
	MessageID        string `json:"messageId,omitempty"`
	ReporterClientID string `json:"reporterClientId,omitempty"`
}

// ReportStore 负责将用户举报信息以 JSONL 格式安全追加写入磁盘（绝不落盘明文 IP）
type ReportStore struct {
	mu       sync.Mutex
	filePath string
}

// NewReportStore 创建举报记录存储器
func NewReportStore(dataDir string) (*ReportStore, error) {
	if err := os.MkdirAll(dataDir, 0o755); err != nil {
		return nil, err
	}
	return &ReportStore{
		filePath: filepath.Join(dataDir, "reports.jsonl"),
	}, nil
}

// Append 追加一条举报记录至 reports.jsonl，ip 仅存储 SHA-256 哈希
func (rs *ReportStore) Append(req ReportRequest, clientIP string, now time.Time) error {
	reason := truncateRunes(strings.TrimSpace(req.Reason), 500)
	if reason == "" {
		return ErrInvalidReport
	}
	ipSum := sha256.Sum256([]byte(strings.TrimSpace(clientIP)))
	rec := reportRecord{
		Timestamp:        now.UnixMilli(),
		CreatedAt:        now.UTC().Format(time.RFC3339),
		IPHash:           hex.EncodeToString(ipSum[:]),
		RoomCode:         truncateRunes(NormalizeCode(req.RoomCode), 32),
		ReportedName:     truncateRunes(strings.TrimSpace(req.ReportedName), 32),
		ReportedClientID: truncateRunes(strings.TrimSpace(req.ReportedClientID), 64),
		Reason:           reason,
		MessageID:        truncateRunes(strings.TrimSpace(req.MessageID), 80),
		ReporterClientID: truncateRunes(strings.TrimSpace(req.ReporterClientID), 64),
	}
	line, err := json.Marshal(rec)
	if err != nil {
		return err
	}
	line = append(line, '\n')

	rs.mu.Lock()
	defer rs.mu.Unlock()
	f, err := os.OpenFile(rs.filePath, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		return err
	}
	defer func() { _ = f.Close() }()
	_, err = f.Write(line)
	return err
}
