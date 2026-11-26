package push

import (
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
)

func TestServiceEnabledAndSkipsWhenUnconfigured(t *testing.T) {
	svc := NewService(t.TempDir(), "", "", "")
	if svc.Enabled() {
		t.Fatal("未配置密钥时 Enabled() 应返回 false")
	}
	if err := svc.Register(DeviceRegistration{
		RoomCode:       "888888",
		ClientID:       "cli_1",
		PeerID:         "peer_1",
		RegistrationID: "reg_1",
	}); err != nil {
		t.Fatalf("Register 失败: %v", err)
	}
	// 未配置密钥时应静默返回，不 panic
	svc.NotifyOfflineRoomPeers("888888", "peer_sender", "张三", "你好", map[string]bool{})
}

func TestNotifyOfflineRoomPeersFiltersOnlineAndSenderAndSendsThirdPartyChannels(t *testing.T) {
	var callCount atomic.Int32
	var capturedAuth string
	var capturedBody map[string]any

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		callCount.Add(1)
		capturedAuth = r.Header.Get("Authorization")
		if err := json.NewDecoder(r.Body).Decode(&capturedBody); err != nil {
			t.Fatalf("解析推送体失败: %v", err)
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"sendno":"0","msg_id":"123"}`))
	}))
	defer srv.Close()

	dir := t.TempDir()
	svc := NewService(dir, "app_key_1", "secret_1", srv.URL)
	if !svc.Enabled() {
		t.Fatal("配置完整密钥后 Enabled() 应返回 true")
	}

	// 注册 3 个成员：发送者、在线成员、离线成员
	_ = svc.Register(DeviceRegistration{RoomCode: "123456", ClientID: "c_sender", PeerID: "p_sender", RegistrationID: "reg_sender"})
	_ = svc.Register(DeviceRegistration{RoomCode: "123456", ClientID: "c_online", PeerID: "p_online", RegistrationID: "reg_online"})
	_ = svc.Register(DeviceRegistration{RoomCode: "123456", ClientID: "c_offline", PeerID: "p_offline", RegistrationID: "reg_offline", Brand: "Xiaomi"})

	// 模拟持久化重启恢复
	reloaded := NewService(dir, "app_key_1", "secret_1", srv.URL)
	reloaded.NotifyOfflineRoomPeers(
		"123456",
		"p_sender",
		"李四",
		"晚上一起吃饭吗",
		map[string]bool{"p_sender": true, "p_online": true},
	)

	if callCount.Load() != 1 {
		t.Fatalf("期望调用 1 次 JPush API，实际 %d", callCount.Load())
	}
	wantAuth := "Basic " + base64.StdEncoding.EncodeToString([]byte("app_key_1:secret_1"))
	if capturedAuth != wantAuth {
		t.Fatalf("Basic Auth 不匹配: got %q want %q", capturedAuth, wantAuth)
	}

	aud := capturedBody["audience"].(map[string]any)
	regIDs := aud["registration_id"].([]any)
	if len(regIDs) != 1 || regIDs[0] != "reg_offline" {
		t.Fatalf("应当仅推送离线设备 reg_offline，实际: %#v", regIDs)
	}

	notif := capturedBody["notification"].(map[string]any)["android"].(map[string]any)
	if notif["title"] != "李四" || notif["alert"] != "晚上一起吃饭吗" {
		t.Fatalf("通知标题或正文不符合预期: %#v", notif)
	}
	intentObj := notif["intent"].(map[string]any)
	if intentObj["url"] != "intent:#Intent;action=android.intent.action.VIEW;data=baychat://room/123456;end" {
		t.Fatalf("intent.url 不符合预期: %v", intentObj["url"])
	}

	opts := capturedBody["options"].(map[string]any)
	channels := opts["third_party_channel"].(map[string]any)
	for _, vendor := range []string{"xiaomi", "huawei", "oppo", "vivo", "honor", "fcm"} {
		vMap, ok := channels[vendor].(map[string]any)
		if !ok || vMap["distribution"] != "secondary_push" {
			t.Fatalf("厂商通道 %s 配置缺失或不正确: %#v", vendor, channels[vendor])
		}
	}

	// 注销离线设备后，不应再发起推送
	if err := reloaded.Unregister("123456", "c_offline"); err != nil {
		t.Fatalf("Unregister 失败: %v", err)
	}
	reloaded.NotifyOfflineRoomPeers("123456", "p_sender", "李四", "第二条", map[string]bool{"p_sender": true, "p_online": true})
	if callCount.Load() != 1 {
		t.Fatalf("注销设备后不应继续推送，实际调用次数: %d", callCount.Load())
	}
}
