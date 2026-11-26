package static

import (
	"embed"
	"io/fs"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"strings"
)

//go:embed all:dist
var embeddedDist embed.FS

// Handler 返回前端静态资源与 SPA 路由回退处理器
// 若指定了 externalDir 且目录存在，优先从外部目录读取（便于本地开发调试）；否则直接从二进制内嵌资源提供服务
func Handler(externalDir string) http.Handler {
	if externalDir != "" {
		if info, err := os.Stat(externalDir); err == nil && info.IsDir() {
			return spaHandler(os.DirFS(externalDir))
		}
	}
	sub, err := fs.Sub(embeddedDist, "dist")
	if err != nil {
		return http.NotFoundHandler()
	}
	return spaHandler(sub)
}

func spaHandler(root fs.FS) http.Handler {
	fileServer := http.FileServer(http.FS(root))
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		cleanPath := strings.TrimPrefix(path.Clean(r.URL.Path), "/")
		if cleanPath == "" || cleanPath == "." {
			cleanPath = "index.html"
		}

		// 检查目标静态文件是否存在
		if f, err := root.Open(cleanPath); err == nil {
			stat, _ := f.Stat()
			_ = f.Close()
			if stat != nil && !stat.IsDir() {
				// 带哈希指纹的静态构建资源可长期缓存，入口 HTML 禁止强缓存以确保版本及时更新
				if strings.HasPrefix(cleanPath, "assets/") {
					w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
				} else if cleanPath == "index.html" {
					w.Header().Set("Cache-Control", "no-cache, no-store, must-revalidate")
				}
				fileServer.ServeHTTP(w, r)
				return
			}
		}

		// 若不存在且无文件后缀，则回退到 SPA index.html
		if filepath.Ext(cleanPath) == "" {
			indexBytes, err := fs.ReadFile(root, "index.html")
			if err == nil {
				w.Header().Set("Content-Type", "text/html; charset=utf-8")
				w.Header().Set("Cache-Control", "no-cache, no-store, must-revalidate")
				_, _ = w.Write(indexBytes)
				return
			}
		}

		http.NotFound(w, r)
	})
}
