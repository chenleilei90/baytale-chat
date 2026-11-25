package room

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"baychat/server/internal/config"
	"github.com/gorilla/websocket"
)

func newTestConfig(t *testing.T) *config.Config {
	t.Helper()
	return &config.Config{
		Port:                 "0",
		DataDir:              t.TempDir(),
		Domain:               "chat.baytale.com",
		MaxRoomPeers:         6,
		HistoryMaxMessages:   100,
		HistoryMaxMediaBytes: 2 * 1024 * 1024,
		HistoryMaxFileBytes:  10 * 1024 * 1024,
		MaxAttachmentMB:      0,
		DefaultRoomMaxFileMB: 500,
	}
}

func TestFixedRoomPersistenceAndPassword(t *testing.T) {
	cfg := newTestConfig(t)
	hub, err := NewHub(cfg)
	if err != nil {
		t.Fatalf("创建 Hub 失败: %v", err)
	}

	days := 7
	created, err := hub.CreateRoom(CreateRoomRequest{
		Mode:          ModeFixed,
		Code:          "My-Desk-01",
		Password:      "secret123",
		MaxPeers:      4,
		RetentionDays: &days,
	})
	if err != nil {
		t.Fatalf("创建固定房间失败: %v", err)
	}
	if created.Code != "my-desk-01" || created.Mode != ModeFixed || !created.HasPassword || created.RetentionDays != 7 {
		t.Fatalf("固定房间属性不符合预期: %+v", created)
	}

	// 错误密码应被拒绝
	if _, err := hub.VerifyAccess("my-desk-01", "wrong"); !errors.Is(err, ErrWrongPassword) {
		t.Fatalf("预期返回密码错误，实际: %v", err)
	}
	// 正确密码应通过
	if _, err := hub.VerifyAccess("my-desk-01", "secret123"); err != nil {
		t.Fatalf("预期密码校验通过，实际错误: %v", err)
	}
	hub.Close()

	// 模拟服务重启：重新初始化 Hub，固定房间必须保持不变
	hub2, err := NewHub(cfg)
	if err != nil {
		t.Fatalf("重启后初始化 Hub 失败: %v", err)
	}
	defer hub2.Close()

	info, err := hub2.GetRoomInfo("MY-DESK-01")
	if err != nil {
		t.Fatalf("重启后未找到固定房间: %v", err)
	}
	if info.Mode != ModeFixed || !info.HasPassword || info.RetentionDays != 7 {
		t.Fatalf("重启后固定房间属性异常: %+v", info)
	}
}

func TestEphemeralRoomDestroyAndBurnedCode(t *testing.T) {
	cfg := newTestConfig(t)
	hub, err := NewHub(cfg)
	if err != nil {
		t.Fatalf("创建 Hub 失败: %v", err)
	}
	defer hub.Close()

	created, err := hub.CreateRoom(CreateRoomRequest{Mode: ModeEphemeral})
	if err != nil {
		t.Fatalf("创建一次性房间失败: %v", err)
	}
	if len(created.Code) != 6 || created.Mode != ModeEphemeral {
		t.Fatalf("一次性房间码格式异常: %+v", created)
	}

	// 写入一条缓存聊天记录
	if _, _, err := hub.History().AppendMessage(created.Code, created.RetentionDays, CachedMessage{
		ID:       "m1",
		Kind:     "text",
		SenderID: "p1",
		Text:     "hello",
	}); err != nil {
		t.Fatalf("写入聊天缓存失败: %v", err)
	}

	// 销毁房间后应无法再次访问，并返回 ErrRoomExpired，且聊天记录被立即清空
	if err := hub.DeleteRoom(created.Code, created.OwnerToken, "", "测试用户"); err != nil {
		t.Fatalf("销毁一次性房间失败: %v", err)
	}
	if _, err := hub.GetRoomInfo(created.Code); !errors.Is(err, ErrRoomExpired) {
		t.Fatalf("预期已销毁的一次性房间返回 ErrRoomExpired，实际: %v", err)
	}
	msgs, _ := hub.History().GetHistory(created.Code, 1)
	if len(msgs) != 0 {
		t.Fatalf("销毁房间后聊天记录应立即清空，实际剩余 %d 条", len(msgs))
	}
}

func TestHistorySlidingTTLAndManualDelete(t *testing.T) {
	hs, err := NewHistoryStore(t.TempDir(), 50, 1024*1024)
	if err != nil {
		t.Fatalf("初始化 HistoryStore 失败: %v", err)
	}

	// 超过 7 天的保留配置必须被拒绝
	if err := ValidateRetentionDays(8); !errors.Is(err, ErrInvalidRetentionDays) {
		t.Fatalf("预期超过 7 天时校验失败，实际: %v", err)
	}

	base := time.Date(2026, 11, 24, 12, 0, 0, 0, time.UTC)
	now := base
	hs.SetNowFunc(func() time.Time { return now })

	// 第 0 天发送第一条消息（保留 2 天）
	meta, saved, err := hs.AppendMessage("room-a", 2, CachedMessage{
		ID:       "msg-1",
		Kind:     "text",
		SenderID: "u1",
		Text:     "第一条消息",
	})
	if err != nil || !saved {
		t.Fatalf("追加第一条消息失败: err=%v saved=%v", err, saved)
	}
	if meta.MessageCount != 1 {
		t.Fatalf("预期缓存消息数为 1，实际: %d", meta.MessageCount)
	}

	// 第 1.5 天发送第二条消息 -> 以最后一次聊天为计时起点，过期时间应顺延至 第 3.5 天
	now = base.Add(36 * time.Hour)
	meta, saved, err = hs.AppendMessage("room-a", 2, CachedMessage{
		ID:       "msg-2",
		Kind:     "text",
		SenderID: "u2",
		Text:     "第二条消息",
	})
	if err != nil || !saved || meta.MessageCount != 2 {
		t.Fatalf("追加第二条消息失败: err=%v saved=%v meta=%+v", err, saved, meta)
	}

	// 第 3 天（距第一条已超 2 天，但距最后一条仅 1.5 天）-> 记录必须仍然存活！
	now = base.Add(72 * time.Hour)
	msgs, _ := hs.GetHistory("room-a", 2)
	if len(msgs) != 2 {
		t.Fatalf("滑动过期计算错误：距最后一次聊天未满 2 天，预期保留 2 条，实际 %d 条", len(msgs))
	}

	// 第 3.6 天（距最后一条聊天已满 2 天）-> 记录应自动过期清空！
	now = base.Add(86 * time.Hour)
	msgs, _ = hs.GetHistory("room-a", 2)
	if len(msgs) != 0 {
		t.Fatalf("超过保留天数后应自动清空，实际剩余 %d 条", len(msgs))
	}

	// 重新写入后测试“立即删除”和“切换保留天数为 0 天（立即销毁）”
	_, _, _ = hs.AppendMessage("room-b", 7, CachedMessage{ID: "b1", Kind: "text", Text: "待清空"})
	hs.ClearRoom("room-b", 7)
	msgs, _ = hs.GetHistory("room-b", 7)
	if len(msgs) != 0 {
		t.Fatalf("立即删除后预期为 0 条，实际 %d 条", len(msgs))
	}

	_, _, _ = hs.AppendMessage("room-c", 7, CachedMessage{ID: "c1", Kind: "text", Text: "切为 0 天"})
	_, cleared, err := hs.SetRetention("room-c", 0)
	if err != nil || !cleared {
		t.Fatalf("切换为 0 天（立即销毁）应清空记录: err=%v cleared=%v", err, cleared)
	}
	msgs, _ = hs.GetHistory("room-c", 0)
	if len(msgs) != 0 {
		t.Fatalf("切换为 0 天后预期为 0 条，实际 %d 条", len(msgs))
	}
}

func TestMediaXSSProtectionAndSafeImage(t *testing.T) {
	hs, err := NewHistoryStore(t.TempDir(), 50, 1024*1024)
	if err != nil {
		t.Fatalf("初始化 HistoryStore 失败: %v", err)
	}

	// 伪装成图片的 HTML / SVG 脚本必须被魔数校验拒绝
	xssPayload := []byte(`<html><body><script>alert(1)</script></body></html>`)
	if _, _, err := hs.SaveMedia("room-xss", 3, "image/png", xssPayload); !errors.Is(err, ErrUnsupportedMediaType) {
		t.Fatalf("预期拦截 HTML XSS 载荷返回 ErrUnsupportedMediaType，实际: %v", err)
	}
	svgPayload := []byte(`<svg xmlns="http://www.w3.org/2000/svg" onload="alert(1)"></svg>`)
	if _, _, err := hs.SaveMedia("room-xss", 3, "image/svg+xml", svgPayload); !errors.Is(err, ErrUnsupportedMediaType) {
		t.Fatalf("预期拦截 SVG XSS 载荷返回 ErrUnsupportedMediaType，实际: %v", err)
	}

	// 合法 PNG 魔数头应正常放行并返回 image/png
	validPNG := []byte{0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d}
	mediaID, safeMime, err := hs.SaveMedia("room-xss", 3, "application/octet-stream", validPNG)
	if err != nil || mediaID == "" || safeMime != "image/png" {
		t.Fatalf("合法 PNG 保存异常: id=%s mime=%s err=%v", mediaID, safeMime, err)
	}
}

func TestPeerConnConcurrentEnqueueAndClose(t *testing.T) {
	p := &peerConn{
		id:   "p-race",
		send: make(chan []byte, 4),
	}
	done := make(chan struct{})
	for i := 0; i < 16; i++ {
		go func() {
			for j := 0; j < 200; j++ {
				p.enqueue([]byte(`{"type":"pong"}`))
			}
			done <- struct{}{}
		}()
	}
	// 并发关闭通道，确保绝不会出现 send on closed channel panic
	p.closeSend()
	p.closeSend()
	for i := 0; i < 16; i++ {
		<-done
	}
}

func TestAdjustableAttachmentLimitAndRecall(t *testing.T) {
	cfg := newTestConfig(t)
	cfg.MaxAttachmentMB = 2048 // 服务端最高允许 2GB

	hub, err := NewHub(cfg)
	if err != nil {
		t.Fatalf("初始化 Hub 失败: %v", err)
	}

	// 超过服务端硬上限应被拒绝
	over := 4096
	if _, err := hub.CreateRoom(CreateRoomRequest{
		Mode:          ModeFixed,
		Code:          "size-room",
		MaxFileSizeMB: &over,
	}); !errors.Is(err, ErrInvalidMaxFileSize) {
		t.Fatalf("预期超过服务端硬上限返回 ErrInvalidMaxFileSize，实际: %v", err)
	}

	// 合法上限 1024MB 创建成功并可动态调整
	initLimit := 1024
	created, err := hub.CreateRoom(CreateRoomRequest{
		Mode:          ModeFixed,
		Code:          "size-room",
		MaxFileSizeMB: &initLimit,
	})
	if err != nil || created.MaxFileSizeMB != 1024 {
		t.Fatalf("创建房间附件上限异常: room=%+v err=%v", created, err)
	}

	updated, err := hub.UpdateMaxFileSize("size-room", created.OwnerToken, "", 256, "小美")
	if err != nil || updated != 256 {
		t.Fatalf("动态修改房间附件上限失败: updated=%d err=%v", updated, err)
	}
	hub.Close()

	// 重启后固定房间的附件大小上限必须持久化保留
	hub2, err := NewHub(cfg)
	if err != nil {
		t.Fatalf("重启 Hub 失败: %v", err)
	}
	defer hub2.Close()

	info, err := hub2.GetRoomInfo("size-room")
	if err != nil || info.MaxFileSizeMB != 256 {
		t.Fatalf("重启后固定房间附件上限未持久保留: info=%+v err=%v", info, err)
	}

	// 测试单条消息撤回权限与关联媒体删除
	validPNG := []byte{0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d}
	mediaID, _, err := hub2.History().SaveMedia("size-room", 3, "image/png", validPNG)
	if err != nil {
		t.Fatalf("保存图片失败: %v", err)
	}
	_, _, _ = hub2.History().AppendMessage("size-room", 3, CachedMessage{
		ID:       "img-recall-1",
		Kind:     "image",
		SenderID: "peer-a",
		MediaID:  mediaID,
	})
	// 非本人尝试撤回必须被拦截且标记越权（forbidden == true）
	if _, deleted, forbidden := hub2.History().DeleteMessage("size-room", "img-recall-1", "peer-b", 3); deleted || !forbidden {
		t.Fatalf("非消息发送者不应能删除他人消息: deleted=%v forbidden=%v", deleted, forbidden)
	}
	// 本人撤回成功且关联媒体物理删除
	if _, deleted, forbidden := hub2.History().DeleteMessage("size-room", "img-recall-1", "peer-a", 3); !deleted || forbidden {
		t.Fatalf("本人撤回消息失败: deleted=%v forbidden=%v", deleted, forbidden)
	}
	if _, _, err := hub2.History().LoadMedia("size-room", mediaID); !errors.Is(err, ErrMediaNotFound) {
		t.Fatalf("撤回后关联媒体应已被物理清除，实际: %v", err)
	}
	// 头像过滤：拒绝非图片协议
	if sanitizeAvatar("javascript:alert(1)") != "" {
		t.Fatalf("非法头像协议未被拦截")
	}
	if sanitizeAvatar("preset:aurora") != "preset:aurora" {
		t.Fatalf("合法预设头像应被放行")
	}
}

func TestRoomStatsAndStreakPersistence(t *testing.T) {
	cfg := newTestConfig(t)
	hub, err := NewHub(cfg)
	if err != nil {
		t.Fatalf("初始化 Hub 失败: %v", err)
	}

	_, err = hub.CreateRoom(CreateRoomRequest{
		Mode: ModeFixed,
		Code: "hangout-01",
	})
	if err != nil {
		t.Fatalf("创建固定房失败: %v", err)
	}

	day1 := time.Date(2026, 11, 25, 10, 0, 0, 0, time.Local)
	day2 := day1.AddDate(0, 0, 1)

	// 第 1 天：记录消息、文件与番茄钟
	hub.RecordRoomActivity("hangout-01", "message", 0, day1)
	hub.RecordRoomActivity("hangout-01", "file", 2048, day1)
	stats, _, ok := hub.RecordRoomActivity("hangout-01", "pomodoro", 0, day1)
	if !ok || stats.TotalMessages != 1 || stats.TotalFiles != 1 || stats.TotalBytes != 2048 || stats.TotalPomodoros != 1 {
		t.Fatalf("第 1 天统计不符合预期: %+v", stats)
	}

	// 第 2 天连续互动：火花天数应自动递增
	stats2, _, ok := hub.RecordRoomActivity("hangout-01", "message", 0, day2)
	if !ok || stats2.StreakDays < 2 || stats2.TotalMessages != 2 {
		t.Fatalf("连续两天互动火花天数未递增: %+v", stats2)
	}
	hub.Close()

	// 重启后验证固定房基地成长统计持久化保留
	hub2, err := NewHub(cfg)
	if err != nil {
		t.Fatalf("重启 Hub 失败: %v", err)
	}
	defer hub2.Close()

	info, err := hub2.GetRoomInfo("hangout-01")
	if err != nil {
		t.Fatalf("重启后未找到固定房: %v", err)
	}
	if info.Stats.TotalFiles != 1 || info.Stats.TotalBytes != 2048 || info.Stats.TotalPomodoros != 1 || info.Stats.StreakDays != stats2.StreakDays {
		t.Fatalf("重启后成长统计丢失: %+v", info.Stats)
	}
}

func TestRandomAndPublicPasswordlessRooms(t *testing.T) {
	cfg := newTestConfig(t)
	hub, err := NewHub(cfg)
	if err != nil {
		t.Fatalf("初始化 Hub 失败: %v", err)
	}
	defer hub.Close()

	// 1. 设了密码的房间即使传 AllowRandom=true 也绝不允许被随机进入
	yes := true
	locked, err := hub.CreateRoom(CreateRoomRequest{
		Mode:        ModeEphemeral,
		Password:    "pass888",
		AllowRandom: &yes,
	})
	if err != nil {
		t.Fatalf("创建加密房失败: %v", err)
	}
	if locked.AllowRandom {
		t.Fatalf("加密房绝不允许 AllowRandom=true: %+v", locked)
	}

	// 2. 无密码房间关闭 AllowRandom=false 也不会出现在公开随机池
	no := false
	privateOpen, err := hub.CreateRoom(CreateRoomRequest{
		Mode:        ModeEphemeral,
		AllowRandom: &no,
	})
	if err != nil {
		t.Fatalf("创建关闭随机串门的免密房失败: %v", err)
	}
	if privateOpen.AllowRandom {
		t.Fatalf("预期 AllowRandom=false: %+v", privateOpen)
	}

	if _, err := hub.PickRandomPublicRoom(""); !errors.Is(err, ErrNoPublicRoom) {
		t.Fatalf("仅有加密房和关闭随机房时应返回 ErrNoPublicRoom，实际: %v", err)
	}

	// 3. 无密码房未传 AllowRandom 时默认打开（AllowRandom=true）
	pub1, err := hub.CreateRoom(CreateRoomRequest{
		Mode:  ModeEphemeral,
		Topic: "☕ 闲聊摸鱼",
	})
	if err != nil {
		t.Fatalf("创建免密公开房1失败: %v", err)
	}
	if !pub1.AllowRandom || pub1.HasPassword || pub1.Topic != "☕ 闲聊摸鱼" {
		t.Fatalf("免密公开房1属性异常: %+v", pub1)
	}

	pub2, err := hub.CreateRoom(CreateRoomRequest{
		Mode:  ModeEphemeral,
		Topic: "⚡ 互传文件",
	})
	if err != nil {
		t.Fatalf("创建免密公开房2失败: %v", err)
	}

	list := hub.ListPublicRooms()
	if len(list) != 2 {
		t.Fatalf("公开列表预期 2 间，实际 %d: %+v", len(list), list)
	}

	// 4. 排除 pub1 时必须精准抽中 pub2
	picked, err := hub.PickRandomPublicRoom(pub1.Code)
	if err != nil || picked.Code != pub2.Code {
		t.Fatalf("排除 %s 后预期抽中 %s，实际 picked=%+v err=%v", pub1.Code, pub2.Code, picked, err)
	}

	// 5. 动态关闭 pub2 的随机串门开关后，再次排除 pub1 必须无房可抽
	updatedAllow, err := hub.UpdateAllowRandom(pub2.Code, pub2.OwnerToken, "", false, "房主")
	if err != nil || updatedAllow {
		t.Fatalf("关闭随机串门失败: allow=%v err=%v", updatedAllow, err)
	}
	if _, err := hub.PickRandomPublicRoom(pub1.Code); !errors.Is(err, ErrNoPublicRoom) {
		t.Fatalf("关闭后应无法抽到 pub2，实际: %v", err)
	}
}

func TestCachedFileAttachmentLifecycle(t *testing.T) {
	cfg := newTestConfig(t)
	cfg.HistoryMaxFileBytes = 1024 // 仅测试 1KB 上限
	hub, err := NewHub(cfg)
	if err != nil {
		t.Fatalf("初始化 Hub 失败: %v", err)
	}
	defer hub.Close()

	days := 7
	rm, err := hub.CreateRoom(CreateRoomRequest{
		Mode:          ModeFixed,
		Code:          "file-box",
		Password:      "p123",
		RetentionDays: &days,
	})
	if err != nil {
		t.Fatalf("创建房间失败: %v", err)
	}

	// 超限附件应被拒绝
	tooBig := bytes.Repeat([]byte("A"), 2048)
	if _, _, err := hub.SaveRoomFile(rm.Code, "p123", "big.apk", "application/vnd.android.package-archive", bytes.NewReader(tooBig)); !errors.Is(err, ErrMediaTooLarge) {
		t.Fatalf("预期超限附件返回 ErrMediaTooLarge，实际: %v", err)
	}

	// 密码错误应被拒绝
	payload := []byte("hello baychat cached attachment")
	if _, _, err := hub.SaveRoomFile(rm.Code, "wrong", "notes.txt", "text/plain", bytes.NewReader(payload)); !errors.Is(err, ErrWrongPassword) {
		t.Fatalf("预期错误密码返回 ErrWrongPassword，实际: %v", err)
	}

	// 正常上传并读取校验
	fileID, meta, err := hub.SaveRoomFile(rm.Code, "p123", "测试 附件.apk", "application/vnd.android.package-archive", bytes.NewReader(payload))
	if err != nil || fileID == "" || meta.Size != int64(len(payload)) || meta.FileName != "测试 附件.apk" {
		t.Fatalf("保存历史附件失败: id=%s meta=%+v err=%v", fileID, meta, err)
	}

	f, openedMeta, _, err := hub.OpenRoomFile(rm.Code, "p123", fileID)
	if err != nil {
		t.Fatalf("打开历史附件失败: %v", err)
	}
	gotBytes, _ := io.ReadAll(f)
	_ = f.Close()
	if !bytes.Equal(gotBytes, payload) || openedMeta.FileName != "测试 附件.apk" {
		t.Fatalf("读取历史附件内容不匹配: meta=%+v got=%q", openedMeta, string(gotBytes))
	}

	// 清空历史记录后文件必须被物理删除
	if _, err := hub.ClearHistory(rm.Code, rm.OwnerToken, "p123", "tester"); err != nil {
		t.Fatalf("清空历史失败: %v", err)
	}
	if _, _, _, err := hub.OpenRoomFile(rm.Code, "p123", fileID); !errors.Is(err, ErrMediaNotFound) {
		t.Fatalf("清空历史后预期附件返回 ErrMediaNotFound，实际: %v", err)
	}
}

func TestOwnerTokenAndRoomProfileAndWebSocketAuth(t *testing.T) {
	cfg := newTestConfig(t)
	hub, err := NewHub(cfg)
	if err != nil {
		t.Fatalf("初始化 Hub 失败: %v", err)
	}
	defer hub.Close()

	// 创建房间必须返回 32 位十六进制 ownerToken 且 hasOwner=true
	yes := true
	created, err := hub.CreateRoom(CreateRoomRequest{
		Mode:        ModeFixed,
		Code:        "owner-room-1",
		AllowRandom: &yes,
	})
	if err != nil {
		t.Fatalf("创建房间失败: %v", err)
	}
	if !created.HasOwner || len(created.OwnerToken) != 32 {
		t.Fatalf("预期返回 32 位 ownerToken 且 hasOwner=true，实际: %+v", created)
	}
	// GetRoomInfo 绝不能泄露 ownerToken
	info, err := hub.GetRoomInfo(created.Code)
	if err != nil || !info.HasOwner || info.OwnerToken != "" {
		t.Fatalf("GetRoomInfo 不应返回 ownerToken: %+v err=%v", info, err)
	}

	// 非房主 REST 管理操作必须返回 ErrNotOwner
	if _, err := hub.UpdateMaxFileSize(created.Code, "wrong-token", "", 100, "路人"); !errors.Is(err, ErrNotOwner) {
		t.Fatalf("预期非房主修改上限返回 ErrNotOwner，实际: %v", err)
	}
	if _, err := hub.UpdateAllowRandom(created.Code, "", "", false, "路人"); !errors.Is(err, ErrNotOwner) {
		t.Fatalf("预期非房主修改随机开关返回 ErrNotOwner，实际: %v", err)
	}
	if _, _, err := hub.UpdateRetention(created.Code, "", "", 1, "路人"); !errors.Is(err, ErrNotOwner) {
		t.Fatalf("预期非房主修改保留天数返回 ErrNotOwner，实际: %v", err)
	}
	if _, err := hub.ClearHistory(created.Code, "", "", "路人"); !errors.Is(err, ErrNotOwner) {
		t.Fatalf("预期非房主清空历史返回 ErrNotOwner，实际: %v", err)
	}
	if err := hub.DeleteRoom(created.Code, "", "", "路人"); !errors.Is(err, ErrNotOwner) {
		t.Fatalf("预期非房主销毁房间返回 ErrNotOwner，实际: %v", err)
	}

	// 房主调用 UpdateRoomProfile 设置密码与主题后，allowRandom 必须强制为 false
	newPwd := "roompass123"
	newTopic := "工作传文件"
	updatedProfile, err := hub.UpdateRoomProfile(created.Code, created.OwnerToken, "", &newPwd, &newTopic, "房主")
	if err != nil {
		t.Fatalf("房主更新房间资料失败: %v", err)
	}
	if !updatedProfile.HasPassword || updatedProfile.AllowRandom || updatedProfile.Topic != "工作传文件" {
		t.Fatalf("更新资料后状态异常: %+v", updatedProfile)
	}

	// 启动 WS 测试服务器校验 join 的 ownerToken、isOwner、clientId 透传及非房主 WS 操作拦截
	srv := httptest.NewServer(http.HandlerFunc(hub.ServeWS))
	defer srv.Close()
	wsURL := "ws" + strings.TrimPrefix(srv.URL, "http")

	// 1) 房主连入
	ownerConn, _, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err != nil {
		t.Fatalf("房主 WS 连接失败: %v", err)
	}
	defer func() { _ = ownerConn.Close() }()
	_ = ownerConn.WriteJSON(SignalEnvelope{
		Type:       "join",
		RoomCode:   created.Code,
		PeerID:     "p_owner",
		Password:   "roompass123",
		OwnerToken: created.OwnerToken,
		Profile:    &PeerProfile{Name: "房主A", ClientID: "cid_owner_1"},
	})
	var ownerJoined SignalEnvelope
	if err := ownerConn.ReadJSON(&ownerJoined); err != nil {
		t.Fatalf("读取房主 joined 失败: %v", err)
	}
	if ownerJoined.Type != "joined" || ownerJoined.IsOwner == nil || !*ownerJoined.IsOwner {
		t.Fatalf("房主 joined 应标记 isOwner=true: %+v", ownerJoined)
	}

	// 2) 访客连入（不带 ownerToken）
	guestConn, _, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err != nil {
		t.Fatalf("访客 WS 连接失败: %v", err)
	}
	defer func() { _ = guestConn.Close() }()
	_ = guestConn.WriteJSON(SignalEnvelope{
		Type:     "join",
		RoomCode: created.Code,
		PeerID:   "p_guest",
		Password: "roompass123",
		Profile:  &PeerProfile{Name: "访客B", ClientID: "cid_guest_2"},
	})
	var guestJoined SignalEnvelope
	if err := guestConn.ReadJSON(&guestJoined); err != nil {
		t.Fatalf("读取访客 joined 失败: %v", err)
	}
	if guestJoined.IsOwner == nil || *guestJoined.IsOwner {
		t.Fatalf("访客 joined 应标记 isOwner=false: %+v", guestJoined)
	}
	if len(guestJoined.Peers) != 1 || guestJoined.Peers[0].ClientID != "cid_owner_1" {
		t.Fatalf("PeerProfile.ClientID 未透传给其他成员: %+v", guestJoined.Peers)
	}

	// 访客尝试发送 destroy_room 与 clear_history 必须收到 NOT_OWNER 错误，房间不能被销毁
	_ = guestConn.WriteJSON(SignalEnvelope{Type: "destroy_room"})
	var errEnv SignalEnvelope
	if err := guestConn.ReadJSON(&errEnv); err != nil {
		t.Fatalf("读取 NOT_OWNER 错误回包失败: %v", err)
	}
	if errEnv.Type != "error" || errEnv.ErrorCode != "NOT_OWNER" {
		t.Fatalf("预期 WS 返回 NOT_OWNER，实际: %+v", errEnv)
	}
	if _, err := hub.GetRoomInfo(created.Code); err != nil {
		t.Fatalf("非房主操作后房间不应被销毁: %v", err)
	}

	// 房主移除密码（传空字符串）
	emptyPwd := ""
	clearedPwd, err := hub.UpdateRoomProfile(created.Code, created.OwnerToken, "", &emptyPwd, nil, "房主")
	if err != nil || clearedPwd.HasPassword {
		t.Fatalf("移除密码失败: %+v err=%v", clearedPwd, err)
	}
}

func TestLegacyFixedRoomWithoutOwnerFallback(t *testing.T) {
	cfg := newTestConfig(t)
	// 手动写入一个无 ownerTokenHash 的老版本常驻房
	fs, err := NewFixedStore(cfg.DataDir)
	if err != nil {
		t.Fatalf("创建 FixedStore 失败: %v", err)
	}
	nowMs := time.Now().UnixMilli()
	err = fs.SaveAll(map[string]*FixedRoomRecord{
		"legacy-room": {
			Code:          "legacy-room",
			Mode:          ModeFixed,
			PasswordHash:  HashPassword("oldpass"),
			MaxPeers:      4,
			RetentionDays: 3,
			CreatedAt:     nowMs,
			LastActiveAt:  nowMs,
		},
	})
	if err != nil {
		t.Fatalf("写入老房间失败: %v", err)
	}

	hub, err := NewHub(cfg)
	if err != nil {
		t.Fatalf("加载 Hub 失败: %v", err)
	}
	defer hub.Close()

	info, err := hub.GetRoomInfo("legacy-room")
	if err != nil || info.HasOwner {
		t.Fatalf("老房间应标记 hasOwner=false: %+v err=%v", info, err)
	}
	// 错误密码被拒
	if _, err := hub.UpdateMaxFileSize("legacy-room", "", "wrong", 100, "老用户"); !errors.Is(err, ErrWrongPassword) {
		t.Fatalf("老房间错误密码预期返回 ErrWrongPassword，实际: %v", err)
	}
	// 正确密码放行
	if mb, err := hub.UpdateMaxFileSize("legacy-room", "", "oldpass", 100, "老用户"); err != nil || mb != 100 {
		t.Fatalf("老房间正确密码应允许修改: mb=%d err=%v", mb, err)
	}
}

func TestFixedRoomSixDigitReservedAndIdleEviction(t *testing.T) {
	cfg := newTestConfig(t)
	cfg.FixedRoomIdleDays = 60
	now := time.Date(2026, 1, 1, 10, 0, 0, 0, time.UTC)

	hub, err := NewHub(cfg)
	if err != nil {
		t.Fatalf("初始化 Hub 失败: %v", err)
	}
	defer hub.Close()
	hub.SetNowFunc(func() time.Time { return now })

	// 1. 6 位纯数字禁止创建常驻房
	if _, err := hub.CreateRoom(CreateRoomRequest{
		Mode: ModeFixed,
		Code: "123456",
	}); !errors.Is(err, ErrCodeReserved) {
		t.Fatalf("预期 6 位纯数字常驻房返回 ErrCodeReserved，实际: %v", err)
	}

	// 2. 创建一个合法常驻房与一个较新常驻房，验证 60 天无人活跃回收
	if _, err := hub.CreateRoom(CreateRoomRequest{
		Mode: ModeFixed,
		Code: "idle-room",
	}); err != nil {
		t.Fatalf("创建 idle-room 失败: %v", err)
	}
	now = now.Add(30 * 24 * time.Hour)
	if _, err := hub.CreateRoom(CreateRoomRequest{
		Mode: ModeFixed,
		Code: "active-room",
	}); err != nil {
		t.Fatalf("创建 active-room 失败: %v", err)
	}

	// 推进到第 61 天：idle-room 已空闲 61 天应被回收，active-room 仅 31 天应保留
	now = now.Add(31 * 24 * time.Hour)
	purged := hub.PurgeIdleFixedRooms(now)
	if purged != 1 {
		t.Fatalf("预期回收 1 个空闲常驻房，实际回收 %d 个", purged)
	}
	if _, err := hub.GetRoomInfo("idle-room"); !errors.Is(err, ErrRoomNotFound) {
		t.Fatalf("idle-room 应已被回收，实际: %v", err)
	}
	if _, err := hub.GetRoomInfo("active-room"); err != nil {
		t.Fatalf("active-room 不应被回收: %v", err)
	}
}

func TestReportStoreAppendAndNoPlaintextIP(t *testing.T) {
	dir := t.TempDir()
	rs, err := NewReportStore(dir)
	if err != nil {
		t.Fatalf("创建 ReportStore 失败: %v", err)
	}

	now := time.Date(2026, 11, 25, 18, 0, 0, 0, time.UTC)
	if err := rs.Append(ReportRequest{
		RoomCode:         "BAY-8888",
		ReportedName:     "恶意访客",
		ReportedClientID: "u_bad_1",
		Reason:           "发送垃圾广告",
		MessageID:        "msg_123",
		ReporterClientID: "u_good_1",
	}, "203.0.113.88", now); err != nil {
		t.Fatalf("写入举报失败: %v", err)
	}

	raw, err := os.ReadFile(filepath.Join(dir, "reports.jsonl"))
	if err != nil {
		t.Fatalf("读取 reports.jsonl 失败: %v", err)
	}
	text := string(raw)
	if strings.Contains(text, "203.0.113.88") {
		t.Fatalf("reports.jsonl 绝不可包含明文 IP: %s", text)
	}
	var parsed map[string]any
	if err := json.Unmarshal([]byte(strings.TrimSpace(text)), &parsed); err != nil {
		t.Fatalf("reports.jsonl JSON 格式无效: %v", err)
	}
	if parsed["roomCode"] != "bay-8888" || parsed["reason"] != "发送垃圾广告" || parsed["ipHash"] == "" {
		t.Fatalf("举报记录字段异常: %+v", parsed)
	}
}
