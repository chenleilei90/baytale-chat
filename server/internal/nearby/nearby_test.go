package nearby

import (
	"errors"
	"fmt"
	"testing"
	"time"

	"baychat/server/internal/config"
	"baychat/server/internal/room"
)

func newTestNearbyService(t *testing.T) (*Service, *room.Hub) {
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

func TestNearbyHeartbeatMatchingAndCellularExclusion(t *testing.T) {
	svc, _ := newTestNearbyService(t)
	now := time.Date(2026, 11, 25, 14, 0, 0, 0, time.UTC)
	svc.SetNowFunc(func() time.Time { return now })

	// 同 IP 下 Wi-Fi 设备 A、B、蜂窝设备 C，以及不同 IP 设备 D
	_, err := svc.Heartbeat(HeartbeatRequest{ClientID: "c_a", Name: "电脑A", Device: "web", Network: "wifi"}, "198.51.100.10")
	if err != nil {
		t.Fatalf("A 心跳失败: %v", err)
	}
	now = now.Add(2 * time.Second)
	_, _ = svc.Heartbeat(HeartbeatRequest{ClientID: "c_b", Name: "手机B", Device: "android", Network: "wifi"}, "198.51.100.10")
	_, _ = svc.Heartbeat(HeartbeatRequest{ClientID: "c_c", Name: "蜂窝C", Device: "android", Network: "cellular"}, "198.51.100.10")
	_, _ = svc.Heartbeat(HeartbeatRequest{ClientID: "c_d", Name: "异地D", Device: "web", Network: "wifi"}, "203.0.113.20")

	// A 再次心跳，应仅看到 B（不含自己、不含 cellular C、不含异地 D）
	resA, err := svc.Heartbeat(HeartbeatRequest{ClientID: "c_a", Name: "电脑A", Device: "web", Network: "wifi"}, "198.51.100.10")
	if err != nil {
		t.Fatalf("A 二次心跳失败: %v", err)
	}
	if len(resA.Devices) != 1 || resA.Devices[0].ClientID != "c_b" || resA.Devices[0].Device != "android" {
		t.Fatalf("A 附近设备匹配异常: %+v", resA.Devices)
	}

	// 蜂窝设备 C 心跳必须返回空 devices
	resC, err := svc.Heartbeat(HeartbeatRequest{ClientID: "c_c", Name: "蜂窝C", Device: "android", Network: "cellular"}, "198.51.100.10")
	if err != nil || len(resC.Devices) != 0 {
		t.Fatalf("蜂窝网络设备不应发现任何人: %+v err=%v", resC, err)
	}

	// 验证最多返回 12 个且按 lastSeen 倒序
	for i := 1; i <= 15; i++ {
		now = now.Add(100 * time.Millisecond)
		_, _ = svc.Heartbeat(HeartbeatRequest{
			ClientID: fmt.Sprintf("extra_%02d", i),
			Name:     fmt.Sprintf("设备%02d", i),
			Device:   "web",
			Network:  "wifi",
		}, "198.51.100.10")
	}
	resCap, _ := svc.Heartbeat(HeartbeatRequest{ClientID: "c_a", Name: "电脑A", Device: "web", Network: "wifi"}, "198.51.100.10")
	if len(resCap.Devices) != 12 || resCap.Devices[0].ClientID != "extra_15" {
		t.Fatalf("预期最多返回 12 台且最新在前，实际 count=%d first=%+v", len(resCap.Devices), resCap.Devices[0])
	}

	// 超过 45 秒未活跃应下线
	now = now.Add(46 * time.Second)
	resExpired, _ := svc.Heartbeat(HeartbeatRequest{ClientID: "c_a", Name: "电脑A", Device: "web", Network: "wifi"}, "198.51.100.10")
	if len(resExpired.Devices) != 0 {
		t.Fatalf("45 秒超时后预期无在线设备，实际: %+v", resExpired.Devices)
	}
}

func TestNearbyInviteFlowAcceptDeclineAndExpire(t *testing.T) {
	svc, _ := newTestNearbyService(t)
	now := time.Date(2026, 11, 25, 15, 0, 0, 0, time.UTC)
	svc.SetNowFunc(func() time.Time { return now })

	_, _ = svc.Heartbeat(HeartbeatRequest{ClientID: "u1", Name: "发起方", Device: "web", Network: "wifi"}, "10.1.1.5")
	_, _ = svc.Heartbeat(HeartbeatRequest{ClientID: "u2", Name: "接收方", Device: "android", Network: "wifi"}, "10.1.1.5")

	inv, err := svc.CreateInvite(InviteRequest{
		FromClientID: "u1",
		ToClientID:   "u2",
		FromName:     "发起方",
	}, "10.1.1.5")
	if err != nil {
		t.Fatalf("发起附近邀请失败: %v", err)
	}
	if inv.Room == nil || inv.Room.AllowRandom || inv.Room.MaxPeers != 2 || inv.Room.RetentionDays != 1 || len(inv.OwnerToken) != 32 {
		t.Fatalf("自动创建的私密房参数异常: %+v", inv)
	}

	// u2 心跳应收到该待处理邀请
	hbU2, _ := svc.Heartbeat(HeartbeatRequest{ClientID: "u2", Name: "接收方", Device: "android", Network: "wifi"}, "10.1.1.5")
	if len(hbU2.Invites) != 1 || hbU2.Invites[0].InviteID != inv.InviteID {
		t.Fatalf("u2 未收到邀请通知: %+v", hbU2.Invites)
	}

	// 非目标客户端不能响应
	if _, err := svc.RespondInvite(inv.InviteID, RespondRequest{ClientID: "u1", Accept: true}); !errors.Is(err, ErrInviteNotTarget) {
		t.Fatalf("预期非目标方返回 ErrInviteNotTarget，实际: %v", err)
	}

	// u2 接受邀请
	resp, err := svc.RespondInvite(inv.InviteID, RespondRequest{ClientID: "u2", Accept: true})
	if err != nil || resp.RoomCode != inv.Room.Code {
		t.Fatalf("接受邀请失败: resp=%+v err=%v", resp, err)
	}
	status, err := svc.GetInviteStatus(inv.InviteID)
	if err != nil || status != "accepted" {
		t.Fatalf("预期状态 accepted，实际=%q err=%v", status, err)
	}

	// 第二次邀请测试拒绝与 60 秒过期
	inv2, err := svc.CreateInvite(InviteRequest{FromClientID: "u1", ToClientID: "u2"}, "10.1.1.5")
	if err != nil {
		t.Fatalf("发起第二次邀请失败: %v", err)
	}
	if _, err := svc.RespondInvite(inv2.InviteID, RespondRequest{ClientID: "u2", Accept: false}); err != nil {
		t.Fatalf("拒绝邀请失败: %v", err)
	}
	if st2, _ := svc.GetInviteStatus(inv2.InviteID); st2 != "declined" {
		t.Fatalf("预期状态 declined，实际=%q", st2)
	}

	inv3, err := svc.CreateInvite(InviteRequest{FromClientID: "u1", ToClientID: "u2"}, "10.1.1.5")
	if err != nil {
		t.Fatalf("发起第三次邀请失败: %v", err)
	}
	now = now.Add(61 * time.Second)
	if st3, _ := svc.GetInviteStatus(inv3.InviteID); st3 != "expired" {
		t.Fatalf("预期 60 秒后状态 expired，实际=%q", st3)
	}
	if _, err := svc.RespondInvite(inv3.InviteID, RespondRequest{ClientID: "u2", Accept: true}); !errors.Is(err, ErrInviteExpired) {
		t.Fatalf("过期后响应预期返回 ErrInviteExpired，实际: %v", err)
	}
}
