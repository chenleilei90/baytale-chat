package static

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestSPARoutesServeIndexHTML(t *testing.T) {
	handler := Handler("")

	for _, path := range []string{"/", "/p/123456", "/r/my-desk-01"} {
		req := httptest.NewRequest(http.MethodGet, path, nil)
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, req)

		if rec.Code != http.StatusOK {
			t.Fatalf("路径 %s 预期返回 200，实际=%d", path, rec.Code)
		}
		ct := rec.Header().Get("Content-Type")
		if !strings.Contains(ct, "text/html") {
			t.Fatalf("路径 %s 预期返回 text/html，实际 Content-Type=%q", path, ct)
		}
	}
}
