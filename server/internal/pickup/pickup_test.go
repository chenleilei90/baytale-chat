package pickup

import (
	"bytes"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func newTestPickupStore(t *testing.T, maxFileMB, maxTotalMB, quotaMB int) (*Store, string) {
	t.Helper()
	dir := t.TempDir()
	st, err := NewStore(Config{
		DataDir:     dir,
		MaxFileMB:   maxFileMB,
		MaxTotalMB:  maxTotalMB,
		DiskQuotaMB: quotaMB,
	})
	if err != nil {
		t.Fatalf("初始化 Pickup Store 失败: %v", err)
	}
	t.Cleanup(st.Close)
	return st, dir
}

func TestPickupUploadResumePasswordAndDownload(t *testing.T) {
	st, dir := newTestPickupStore(t, 10, 20, 50)
	now := time.Date(2026, 11, 25, 10, 0, 0, 0, time.UTC)
	st.SetNowFunc(func() time.Time { return now })

	payload := []byte("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ")
	created, err := st.Create(CreateRequest{
		Files:         []CreateFileInput{{Name: "hello.txt", Size: int64(len(payload)), Mime: "text/plain"}},
		ExpireHours:   24,
		BurnAfterRead: false,
		Password:      "pick99",
	})
	if err != nil {
		t.Fatalf("创建取件码失败: %v", err)
	}
	if len(created.Code) != 6 || len(created.OwnerToken) != 32 || len(created.Files) != 1 {
		t.Fatalf("创建返回异常: %+v", created)
	}
	fileID := created.Files[0].FileID

	// 非房主上传被拒绝
	if err := st.UploadFile(created.Code, fileID, "wrong-owner", 0, bytes.NewReader(payload)); !errors.Is(err, ErrNotOwner) {
		t.Fatalf("预期非房主返回 ErrNotOwner，实际: %v", err)
	}

	// 大小不一致返回 ErrSizeMismatch
	if err := st.UploadFile(created.Code, fileID, created.OwnerToken, 0, bytes.NewReader(payload[:10])); !errors.Is(err, ErrSizeMismatch) {
		t.Fatalf("预期大小不符返回 ErrSizeMismatch，实际: %v", err)
	}

	// 模拟中断：传入读取中途出错的 Reader 写入前 10 字节，再查询 HEAD 并从 offset=10 断点续写剩余字节
	partialReader := io.MultiReader(bytes.NewReader(payload[:10]), errReader{})
	if err := st.UploadFile(created.Code, fileID, created.OwnerToken, 0, partialReader); err == nil {
		t.Fatalf("预期中断写入返回读取错误")
	}
	uploadedBytes, err := st.GetUploadedBytes(created.Code, fileID, created.OwnerToken)
	if err != nil || uploadedBytes != 10 {
		t.Fatalf("预期已上传 10 字节，实际=%d err=%v", uploadedBytes, err)
	}
	if err := st.UploadFile(created.Code, fileID, created.OwnerToken, 10, bytes.NewReader(payload[10:])); err != nil {
		t.Fatalf("断点续传上传失败: %v", err)
	}

	// 密码校验：无密码 -> ErrPickupPasswordRequired，错密码 -> ErrWrongPassword
	if _, err := st.GetInfo(created.Code, ""); !errors.Is(err, ErrPickupPasswordRequired) {
		t.Fatalf("预期无密码返回 ErrPickupPasswordRequired，实际: %v", err)
	}
	if _, err := st.GetInfo(created.Code, "bad"); !errors.Is(err, ErrWrongPassword) {
		t.Fatalf("预期错密码返回 ErrWrongPassword，实际: %v", err)
	}
	info, err := st.GetInfo(created.Code, "pick99")
	if err != nil || !info.HasPassword || len(info.Files) != 1 || !info.Files[0].Ready {
		t.Fatalf("查询取件码详情异常: %+v err=%v", info, err)
	}

	// 验证 ServeFileDownload 安全响应头、Range 下载与完整下载计数
	req := httptest.NewRequest(http.MethodGet, "/api/pickups/"+created.Code+"/files/"+fileID, nil)
	req.Header.Set("Range", "bytes=10-")
	rec := httptest.NewRecorder()
	if err := st.ServeFileDownload(rec, req, created.Code, fileID, "pick99"); err != nil {
		t.Fatalf("Range 下载失败: %v", err)
	}
	if rec.Code != http.StatusPartialContent || rec.Body.String() != string(payload[10:]) {
		t.Fatalf("Range 下载响应异常: code=%d body=%q", rec.Code, rec.Body.String())
	}
	if rec.Header().Get("X-Content-Type-Options") != "nosniff" || rec.Header().Get("Content-Security-Policy") != "sandbox" {
		t.Fatalf("缺少安全下载响应头: %+v", rec.Header())
	}
	if !strings.Contains(rec.Header().Get("Content-Disposition"), "attachment;") {
		t.Fatalf("缺少 attachment Content-Disposition: %q", rec.Header().Get("Content-Disposition"))
	}

	infoAfter, _ := st.GetInfo(created.Code, "pick99")
	if infoAfter.Downloads != 1 {
		t.Fatalf("尾部 Range 完整下载应记 downloads=1，实际=%d", infoAfter.Downloads)
	}

	// 模拟服务重启，从磁盘恢复取件码
	st.Close()
	st2, err := NewStore(Config{DataDir: dir, MaxFileMB: 10, MaxTotalMB: 20, DiskQuotaMB: 50})
	if err != nil {
		t.Fatalf("重启 Store 失败: %v", err)
	}
	defer st2.Close()
	info2, err := st2.GetInfo(created.Code, "pick99")
	if err != nil || info2.Downloads != 1 || !info2.Files[0].Ready {
		t.Fatalf("重启后恢复状态异常: %+v err=%v", info2, err)
	}
}

func TestPickupBurnAfterReadAndQuotaAndDelete(t *testing.T) {
	st, _ := newTestPickupStore(t, 2, 3, 4)
	now := time.Date(2026, 11, 25, 10, 0, 0, 0, time.UTC)
	st.SetNowFunc(func() time.Time { return now })

	// 超过单文件限制 (2MB)
	if _, err := st.Create(CreateRequest{
		Files:       []CreateFileInput{{Name: "huge.bin", Size: 3 * 1024 * 1024}},
		ExpireHours: 1,
	}); !errors.Is(err, ErrFileTooLarge) {
		t.Fatalf("预期返回 ErrFileTooLarge，实际: %v", err)
	}

	// 创建 2 个文件的取后即焚取件码
	d1 := []byte("file-one-content")
	d2 := []byte("file-two-content")
	created, err := st.Create(CreateRequest{
		Files: []CreateFileInput{
			{Name: "a.bin", Size: int64(len(d1))},
			{Name: "b.bin", Size: int64(len(d2))},
		},
		ExpireHours:   1,
		BurnAfterRead: true,
	})
	if err != nil {
		t.Fatalf("创建取后即焚失败: %v", err)
	}
	f1, f2 := created.Files[0].FileID, created.Files[1].FileID
	if err := st.UploadFile(created.Code, f1, created.OwnerToken, 0, bytes.NewReader(d1)); err != nil {
		t.Fatalf("上传文件1失败: %v", err)
	}
	if err := st.UploadFile(created.Code, f2, created.OwnerToken, 0, bytes.NewReader(d2)); err != nil {
		t.Fatalf("上传文件2失败: %v", err)
	}

	// 只下载第 1 个文件时，不应触发 burnedAt
	r1 := httptest.NewRequest(http.MethodGet, "/", nil)
	w1 := httptest.NewRecorder()
	_ = st.ServeFileDownload(w1, r1, created.Code, f1, "")
	info1, _ := st.GetInfo(created.Code, "")
	if info1.BurnedAt != 0 {
		t.Fatalf("仅下载部分文件不应设置 burnedAt: %+v", info1)
	}

	// 下载第 2 个文件后，所有文件都已下载，应设置 burnedAt，但 10 分钟宽限期内仍可续传下载
	r2 := httptest.NewRequest(http.MethodGet, "/", nil)
	w2 := httptest.NewRecorder()
	_ = st.ServeFileDownload(w2, r2, created.Code, f2, "")
	info2, err := st.GetInfo(created.Code, "")
	if err != nil || info2.BurnedAt == 0 {
		t.Fatalf("全部文件下载后应设置 burnedAt: info=%+v err=%v", info2, err)
	}

	now = now.Add(5 * time.Minute)
	if purged := st.PurgeExpired(); purged != 0 {
		t.Fatalf("宽限期内不应删除，实际删除 %d 个", purged)
	}

	now = now.Add(6 * time.Minute) // 超过 10 分钟宽限
	if purged := st.PurgeExpired(); purged != 1 {
		t.Fatalf("超过 10 分钟宽限应清理 1 个，实际清理 %d 个", purged)
	}
	if _, err := st.GetInfo(created.Code, ""); !errors.Is(err, ErrPickupExpired) {
		t.Fatalf("清理后预期返回 ErrPickupExpired，实际: %v", err)
	}

	// 测试全局配额（4MB）与主动撤销 Delete
	c2, err := st.Create(CreateRequest{
		Files: []CreateFileInput{
			{Name: "p1.bin", Size: 2 * 1024 * 1024},
			{Name: "p2.bin", Size: 1 * 1024 * 1024},
		},
		ExpireHours: 24,
	})
	if err != nil {
		t.Fatalf("创建 3MB 取件码失败: %v", err)
	}
	if _, err := st.Create(CreateRequest{
		Files:       []CreateFileInput{{Name: "p3.bin", Size: 2 * 1024 * 1024}},
		ExpireHours: 24,
	}); !errors.Is(err, ErrQuotaExceeded) {
		t.Fatalf("超过全局配额预期返回 ErrQuotaExceeded，实际: %v", err)
	}
	if err := st.Delete(c2.Code, "bad-token"); !errors.Is(err, ErrNotOwner) {
		t.Fatalf("非创建者撤销预期返回 ErrNotOwner，实际: %v", err)
	}
	if err := st.Delete(c2.Code, c2.OwnerToken); err != nil {
		t.Fatalf("撤销取件码失败: %v", err)
	}
	if _, err := st.GetInfo(c2.Code, ""); !errors.Is(err, ErrPickupNotFound) {
		t.Fatalf("主动撤销后预期返回 ErrPickupNotFound，实际: %v", err)
	}
}

type errReader struct{}

func (errReader) Read(_ []byte) (int, error) {
	return 0, errors.New("模拟网络中断")
}
