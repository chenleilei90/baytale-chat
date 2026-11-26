package plane

import (
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"testing"
	"time"

	"baychat/server/internal/config"
	"baychat/server/internal/room"
)

func newTestPlaneService(t *testing.T) (*Service, *room.Hub) {
	t.Helper()
	cfg := &config.Config{
		Port:                 "0",
		DataDir:              t.TempDir(),
		Domain:               "chat.baytale.com",
		MaxRoomPeers:         6,
		HistoryMaxMessages:   50,
		HistoryMaxMediaBytes: 1024 * 1024,
		HistoryMaxFileBytes:  2 * 1024 * 1024,
		DefaultRoomMaxFileMB: 500,
		FixedRoomIdleDays:    60,
	}
	hub, err := room.NewHub(cfg)
	if err != nil {
		t.Fatalf("创建 Hub 失败: %v", err)
	}
	svc := NewService(hub)
	t.Cleanup(func() {
		svc.Close()
		hub.Close()
	})
	return svc, hub
}

func boolPtr(v bool) *bool { return &v }

func TestPlaneCreateListCooldownLimitsAndExpiry(t *testing.T) {
	svc, hub := newTestPlaneService(t)
	now := time.Date(2026, 11, 26, 9, 0, 0, 0, time.UTC)
	svc.SetNowFunc(func() time.Time { return now })

	rm, err := hub.CreateRoom(room.CreateRoomRequest{Mode: room.ModeEphemeral})
	if err != nil {
		t.Fatalf("创建房间失败: %v", err)
	}

	// 空内容（无留言且无文件）应拒绝
	if _, err := svc.Create(CreateRequest{
		ClientID: "c1",
		RoomCode: rm.Code,
	}); !errors.Is(err, ErrInvalidPlane) {
		t.Fatalf("空纸飞机应返回 ErrInvalidPlane，实际: %v", err)
	}

	// 不存在的房间应拒绝
	if _, err := svc.Create(CreateRequest{
		ClientID: "c1",
		RoomCode: "999999",
		Message:  "hello",
	}); !errors.Is(err, room.ErrRoomNotFound) {
		t.Fatalf("不存在房间应返回 ErrRoomNotFound，实际: %v", err)
	}

	// 正常放飞（附带文件元数据 + 留言）
	p1, err := svc.Create(CreateRequest{
		ClientID:   "c1",
		RoomCode:   rm.Code,
		SenderName: "旅人一号",
		Message:    "安装包已备好",
		Files: []FileMeta{
			{Name: "BayChat.apk", Size: 1234567, Kind: "apk"},
		},
	})
	if err != nil {
		t.Fatalf("创建纸飞机失败: %v", err)
	}
	if !p1.AllowEntry || p1.HasPassword || len(p1.Files) != 1 || p1.Files[0].Kind != "apk" {
		t.Fatalf("纸飞机字段异常: %+v", p1)
	}

	// 60 秒内同一客户端再次放飞应被冷却拦截
	now = now.Add(30 * time.Second)
	if _, err := svc.Create(CreateRequest{
		ClientID: "c1",
		RoomCode: rm.Code,
		Message:  "second",
	}); !errors.Is(err, ErrPlaneCooldown) {
		t.Fatalf("60s 冷却期内应返回 ErrPlaneCooldown，实际: %v", err)
	}

	// 超过 60 秒后可放飞第 2、第 3 架；第 4 架触发单用户最多 3 架上限
	now = now.Add(35 * time.Second)
	if _, err := svc.Create(CreateRequest{ClientID: "c1", RoomCode: rm.Code, Message: "p2"}); err != nil {
		t.Fatalf("第 2 架失败: %v", err)
	}
	now = now.Add(65 * time.Second)
	if _, err := svc.Create(CreateRequest{ClientID: "c1", RoomCode: rm.Code, Message: "p3"}); err != nil {
		t.Fatalf("第 3 架失败: %v", err)
	}
	now = now.Add(65 * time.Second)
	if _, err := svc.Create(CreateRequest{ClientID: "c1", RoomCode: rm.Code, Message: "p4"}); !errors.Is(err, ErrPlaneLimitSender) {
		t.Fatalf("第 4 架应触发 ErrPlaneLimitSender，实际: %v", err)
	}

	list := svc.List(10)
	if len(list) != 3 || list[0].Message != "p3" {
		t.Fatalf("List 排序或数量异常: %+v", list)
	}

	// 超过 24 小时后过期清除
	now = now.Add(PlaneTTL + time.Minute)
	if got := svc.List(10); len(got) != 0 {
		t.Fatalf("超过 24h 后应自动过期清空，实际剩余: %d", len(got))
	}
}

func TestPlaneGlobalLimitAndDestroyedRoomCleanup(t *testing.T) {
	svc, hub := newTestPlaneService(t)
	now := time.Date(2026, 11, 26, 10, 0, 0, 0, time.UTC)
	svc.SetNowFunc(func() time.Time { return now })

	rmKeep, err := hub.CreateRoom(room.CreateRoomRequest{Mode: room.ModeEphemeral})
	if err != nil {
		t.Fatalf("创建房间失败: %v", err)
	}
	rmGone, err := hub.CreateRoom(room.CreateRoomRequest{Mode: room.ModeEphemeral})
	if err != nil {
		t.Fatalf("创建待销毁房间失败: %v", err)
	}

	if _, err := svc.Create(CreateRequest{ClientID: "owner_gone", RoomCode: rmGone.Code, Message: "即将销毁"}); err != nil {
		t.Fatalf("放飞失败: %v", err)
	}
	if err := hub.DeleteRoom(rmGone.Code, rmGone.OwnerToken, "", "owner"); err != nil {
		t.Fatalf("销毁房间失败: %v", err)
	}
	// List 应自动剔除已销毁房间的纸飞机
	if got := svc.List(20); len(got) != 0 {
		t.Fatalf("房间销毁后 List 不应再展示对应纸飞机: %+v", got)
	}

	// 验证全局 500 上限
	for i := 0; i < MaxGlobalPlanes; i++ {
		if _, err := svc.Create(CreateRequest{
			ClientID: fmt.Sprintf("u_%d", i),
			RoomCode: rmKeep.Code,
			Message:  fmt.Sprintf("item-%d", i),
		}); err != nil {
			t.Fatalf("填充第 %d 架失败: %v", i, err)
		}
	}
	if _, err := svc.Create(CreateRequest{
		ClientID: "u_overflow",
		RoomCode: rmKeep.Code,
		Message:  "overflow",
	}); !errors.Is(err, ErrPlaneLimitGlobal) {
		t.Fatalf("超过全局上限应返回 ErrPlaneLimitGlobal，实际: %v", err)
	}
}

func TestPlaneReportThresholdAndGuards(t *testing.T) {
	svc, hub := newTestPlaneService(t)
	rm, _ := hub.CreateRoom(room.CreateRoomRequest{Mode: room.ModeEphemeral})

	p, err := svc.Create(CreateRequest{
		ClientID: "sender_1",
		RoomCode: rm.Code,
		Message:  "待核查内容",
	})
	if err != nil {
		t.Fatalf("创建纸飞机失败: %v", err)
	}

	if _, err := svc.Report(p.ID, "sender_1"); !errors.Is(err, ErrPlaneSelfReport) {
		t.Fatalf("自举报应返回 ErrPlaneSelfReport，实际: %v", err)
	}
	res1, err := svc.Report(p.ID, "rep_1")
	if err != nil || res1.Deleted || res1.ReportCount != 1 {
		t.Fatalf("第 1 次举报异常: %+v err=%v", res1, err)
	}
	if _, err := svc.Report(p.ID, "rep_1"); !errors.Is(err, ErrPlaneAlreadyReported) {
		t.Fatalf("重复举报应返回 ErrPlaneAlreadyReported，实际: %v", err)
	}
	res2, err := svc.Report(p.ID, "rep_2")
	if err != nil || res2.Deleted || res2.ReportCount != 2 {
		t.Fatalf("第 2 次举报异常: %+v err=%v", res2, err)
	}
	res3, err := svc.Report(p.ID, "rep_3")
	if err != nil || !res3.Deleted || res3.ReportCount != 3 {
		t.Fatalf("第 3 次独立举报应触发自动删除: %+v err=%v", res3, err)
	}
	if _, err := svc.Get(p.ID); !errors.Is(err, ErrPlaneNotFound) {
		t.Fatalf("自动下架后应无法获取，实际: %v", err)
	}
}

func TestPlaneEncryptedRoomOwnerBypassAndPasswordChangeInvalidation(t *testing.T) {
	svc, hub := newTestPlaneService(t)
	secret := "top-secret-9527"
	rm, err := hub.CreateRoom(room.CreateRoomRequest{
		Mode:     room.ModeFixed,
		Code:     "vault-9527",
		Password: secret,
	})
	if err != nil {
		t.Fatalf("创建加密房间失败: %v", err)
	}

	// 1. 非房主试图开启 allowEntry=true -> 必须拒绝
	if _, err := svc.Create(CreateRequest{
		ClientID:   "guest_1",
		RoomCode:   rm.Code,
		Message:    "非房主尝试带密放飞",
		AllowEntry: boolPtr(true),
		OwnerToken: "wrong-owner-token",
		Password:   secret,
	}); !errors.Is(err, ErrPlaneOwnerRequired) {
		t.Fatalf("非房主应返回 ErrPlaneOwnerRequired，实际: %v", err)
	}

	// 2. 非房主但仅展示 (allowEntry=false) -> 允许，且 Join 提示未开放进入
	closedPlane, err := svc.Create(CreateRequest{
		ClientID:   "guest_1",
		RoomCode:   rm.Code,
		Message:    "仅展示房间文件清单",
		AllowEntry: boolPtr(false),
		Files:      []FileMeta{{Name: "manual.pdf", Size: 4096, Kind: "doc"}},
	})
	if err != nil {
		t.Fatalf("仅展示纸飞机创建失败: %v", err)
	}
	if closedPlane.AllowEntry {
		t.Fatalf("仅展示纸飞机 AllowEntry 应为 false")
	}
	if _, err := svc.Join(closedPlane.ID); !errors.Is(err, ErrPlaneEntryClosed) {
		t.Fatalf("未开放进入应返回 ErrPlaneEntryClosed，实际: %v", err)
	}

	// 3. 房主开启 allowEntry=true 但未提供明文密码 -> 拒绝
	if _, err := svc.Create(CreateRequest{
		ClientID:   "owner_1",
		RoomCode:   rm.Code,
		Message:    "漏传密码",
		AllowEntry: boolPtr(true),
		OwnerToken: rm.OwnerToken,
		Password:   "",
	}); !errors.Is(err, ErrPlanePasswordRequired) {
		t.Fatalf("缺少密码应返回 ErrPlanePasswordRequired，实际: %v", err)
	}

	// 4. 房主传入正确 ownerToken 与密码开启 allowEntry=true -> 成功放飞，且列表/详情 JSON 绝不泄露密码
	openPlane, err := svc.Create(CreateRequest{
		ClientID:   "owner_1",
		RoomCode:   rm.Code,
		SenderName: "房主本人",
		Message:    "安装包免密直领",
		Files:      []FileMeta{{Name: "installer.exe", Size: 2048000, Kind: "file"}},
		AllowEntry: boolPtr(true),
		OwnerToken: rm.OwnerToken,
		Password:   secret,
	})
	if err != nil {
		t.Fatalf("房主带密放飞失败: %v", err)
	}
	if !openPlane.HasPassword || !openPlane.AllowEntry {
		t.Fatalf("房主免密纸飞机状态异常: %+v", openPlane)
	}

	listJSON, err := json.Marshal(svc.List(10))
	if err != nil {
		t.Fatalf("序列化列表失败: %v", err)
	}
	getObj, _ := svc.Get(openPlane.ID)
	getJSON, _ := json.Marshal(getObj)
	if strings.Contains(string(listJSON), secret) || strings.Contains(string(getJSON), secret) {
		t.Fatalf("公开接口 JSON 绝对不能包含明文密码！list=%s get=%s", string(listJSON), string(getJSON))
	}
	if strings.Contains(string(listJSON), "owner_1") || strings.Contains(string(getJSON), "owner_1") {
		t.Fatalf("公开接口 JSON 不应泄露 SenderID！")
	}

	// 5. 访客点击 Join -> 获取 roomCode + 免密口令
	joinRes, err := svc.Join(openPlane.ID)
	if err != nil {
		t.Fatalf("免密直通 Join 失败: %v", err)
	}
	if joinRes.RoomCode != rm.Code || !joinRes.HasPassword || joinRes.Password != secret {
		t.Fatalf("Join 返回免密结果异常: %+v", joinRes)
	}

	// 6. 房主修改房间密码 -> Join 返回 ErrPlaneEntryExpired 且自动关闭 AllowEntry
	nextSecret := "changed-9999"
	if _, err := hub.UpdateRoomProfile(rm.Code, rm.OwnerToken, secret, &nextSecret, nil, "房主"); err != nil {
		t.Fatalf("修改房间密码失败: %v", err)
	}
	if _, err := svc.Join(openPlane.ID); !errors.Is(err, ErrPlaneEntryExpired) {
		t.Fatalf("改密后应返回 ErrPlaneEntryExpired，实际: %v", err)
	}
	afterInvalid, err := svc.Get(openPlane.ID)
	if err != nil {
		t.Fatalf("改密后卡片应仍可读: %v", err)
	}
	if afterInvalid.AllowEntry {
		t.Fatalf("改密失效后应自动清空 AllowEntry")
	}
}
