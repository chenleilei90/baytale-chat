package main

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"baychat/server/internal/config"
	"baychat/server/internal/room"
	"baychat/server/internal/static"
	"baychat/server/internal/turn"
)

func main() {
	cfg := config.Load()

	hub, err := room.NewHub(cfg)
	if err != nil {
		log.Fatalf("初始化房间服务失败: %v", err)
	}
	defer hub.Close()

	mux := http.NewServeMux()

	// 健康检查
	mux.HandleFunc("GET /api/health", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{
			"status": "ok",
			"domain": cfg.Domain,
			"time":   time.Now().UnixMilli(),
		})
	})

	// 客户端初始化配置：动态下发 STUN + 时效性 TURN 凭证、系统上限等
	mux.HandleFunc("GET /api/config", func(w http.ResponseWriter, r *http.Request) {
		clientID := strings.TrimSpace(r.URL.Query().Get("clientId"))
		iceServers, hasTurn := turn.BuildICEServers(cfg, clientID, time.Now())
		writeJSON(w, http.StatusOK, map[string]any{
			"domain":               cfg.Domain,
			"maxRoomPeers":         cfg.MaxRoomPeers,
			"maxRetentionDays":     room.MaxRetentionDays,
			"historyMaxMediaBytes": cfg.HistoryMaxMediaBytes,
			"maxAttachmentMB":      cfg.MaxAttachmentMB,
			"defaultRoomMaxFileMB": cfg.DefaultRoomMaxFileMB,
			"hasTurn":              hasTurn,
			"iceServers":           iceServers,
		})
	})

	// 创建房间（支持 fixed 固定不变 / ephemeral 每次聊完就变）
	mux.HandleFunc("POST /api/rooms", func(w http.ResponseWriter, r *http.Request) {
		var req room.CreateRoomRequest
		if err := json.NewDecoder(io.LimitReader(r.Body, 64*1024)).Decode(&req); err != nil {
			writeError(w, http.StatusBadRequest, "INVALID_JSON", "请求体 JSON 格式错误")
			return
		}
		summary, err := hub.CreateRoom(req)
		if err != nil {
			writeRoomError(w, err)
			return
		}
		writeJSON(w, http.StatusCreated, summary)
	})

	// 查询当前可进入的免密公开房间列表（发现广场）
	mux.HandleFunc("GET /api/rooms/public", func(w http.ResponseWriter, r *http.Request) {
		rooms := hub.ListPublicRooms()
		writeJSON(w, http.StatusOK, map[string]any{
			"rooms": rooms,
			"count": len(rooms),
		})
	})

	// 随机进入一间免密公开房间（支持 excludeCode 避免换一间抽到当前房）
	mux.HandleFunc("POST /api/rooms/random", func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			ExcludeCode string `json:"excludeCode"`
		}
		_ = json.NewDecoder(io.LimitReader(r.Body, 4*1024)).Decode(&body)
		summary, err := hub.PickRandomPublicRoom(body.ExcludeCode)
		if err != nil {
			writeRoomError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, summary)
	})

	// 查询房间元信息
	mux.HandleFunc("GET /api/rooms/{code}", func(w http.ResponseWriter, r *http.Request) {
		summary, err := hub.GetRoomInfo(r.PathValue("code"))
		if err != nil {
			writeRoomError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, summary)
	})

	// 校验房间密码
	mux.HandleFunc("POST /api/rooms/{code}/verify", func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Password string `json:"password"`
		}
		_ = json.NewDecoder(io.LimitReader(r.Body, 16*1024)).Decode(&body)
		summary, err := hub.VerifyAccess(r.PathValue("code"), body.Password)
		if err != nil {
			writeRoomError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, summary)
	})

	// 销毁房间（同时立即清空服务器缓存聊天记录）
	mux.HandleFunc("DELETE /api/rooms/{code}", func(w http.ResponseWriter, r *http.Request) {
		password := extractPassword(r)
		actor := extractActorName(r)
		if err := hub.DeleteRoom(r.PathValue("code"), password, actor); err != nil {
			writeRoomError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"deleted": true})
	})

	// 查询房间服务器缓存的聊天记录
	mux.HandleFunc("GET /api/rooms/{code}/history", func(w http.ResponseWriter, r *http.Request) {
		msgs, meta, err := hub.GetHistory(r.PathValue("code"), extractPassword(r))
		if err != nil {
			writeRoomError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"messages": msgs,
			"history":  meta,
		})
	})

	// 立即删除房间全部服务器缓存聊天记录与图片
	mux.HandleFunc("DELETE /api/rooms/{code}/history", func(w http.ResponseWriter, r *http.Request) {
		actor := extractActorName(r)
		meta, err := hub.ClearHistory(r.PathValue("code"), extractPassword(r), actor)
		if err != nil {
			writeRoomError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"cleared": true,
			"history": meta,
		})
	})

	// 自助修改房间聊天记录保留天数（0 表示立即删除/不缓存，1~7 表示最后聊天后保留天数）
	mux.HandleFunc("PUT /api/rooms/{code}/retention", func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			RetentionDays int    `json:"retentionDays"`
			Password      string `json:"password"`
			ActorName     string `json:"actorName"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 16*1024)).Decode(&body); err != nil {
			writeError(w, http.StatusBadRequest, "INVALID_JSON", "请求体格式错误")
			return
		}
		password := body.Password
		if password == "" {
			password = extractPassword(r)
		}
		meta, cleared, err := hub.UpdateRetention(r.PathValue("code"), password, body.RetentionDays, body.ActorName)
		if err != nil {
			writeRoomError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"history": meta,
			"cleared": cleared,
		})
	})

	// 自助调整房间设置（单文件附件大小上限 / 允许陌生人随机串门开关）
	mux.HandleFunc("PUT /api/rooms/{code}/settings", func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			MaxFileSizeMB *int   `json:"maxFileSizeMB"`
			AllowRandom   *bool  `json:"allowRandom"`
			Password      string `json:"password"`
			ActorName     string `json:"actorName"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 16*1024)).Decode(&body); err != nil || (body.MaxFileSizeMB == nil && body.AllowRandom == nil) {
			writeError(w, http.StatusBadRequest, "INVALID_JSON", "请提供有效的房间设置参数")
			return
		}
		password := body.Password
		if password == "" {
			password = extractPassword(r)
		}
		resp := map[string]any{}
		if body.MaxFileSizeMB != nil {
			updatedMB, err := hub.UpdateMaxFileSize(r.PathValue("code"), password, *body.MaxFileSizeMB, body.ActorName)
			if err != nil {
				writeRoomError(w, err)
				return
			}
			resp["maxFileSizeMB"] = updatedMB
		}
		if body.AllowRandom != nil {
			updatedAllow, err := hub.UpdateAllowRandom(r.PathValue("code"), password, *body.AllowRandom, body.ActorName)
			if err != nil {
				writeRoomError(w, err)
				return
			}
			resp["allowRandom"] = updatedAllow
		}
		writeJSON(w, http.StatusOK, resp)
	})

	// 上传聊天图片/截图到房间服务器缓存（受限于房间缓存开关与单图体积上限）
	mux.HandleFunc("POST /api/rooms/{code}/media", func(w http.ResponseWriter, r *http.Request) {
		limited := io.LimitReader(r.Body, cfg.HistoryMaxMediaBytes+1)
		raw, err := io.ReadAll(limited)
		if err != nil {
			writeError(w, http.StatusBadRequest, "READ_ERROR", "读取上传内容失败")
			return
		}
		if int64(len(raw)) > cfg.HistoryMaxMediaBytes {
			writeError(w, http.StatusRequestEntityTooLarge, "MEDIA_TOO_LARGE", "缓存图片体积超出服务端限制")
			return
		}
		mimeType := r.Header.Get("Content-Type")
		mediaID, safeMime, err := hub.SaveRoomMedia(r.PathValue("code"), extractPassword(r), mimeType, raw)
		if err != nil {
			writeRoomError(w, err)
			return
		}
		writeJSON(w, http.StatusCreated, map[string]any{
			"mediaId": mediaID,
			"mime":    safeMime,
			"size":    len(raw),
		})
	})

	// 下载房间缓存的聊天图片/截图/语音（强制防嗅探与禁脚本头，杜绝 XSS）
	mux.HandleFunc("GET /api/rooms/{code}/media/{mediaId}", func(w http.ResponseWriter, r *http.Request) {
		raw, mimeType, err := hub.LoadRoomMedia(r.PathValue("code"), extractPassword(r), r.PathValue("mediaId"))
		if err != nil {
			writeRoomError(w, err)
			return
		}
		w.Header().Set("Content-Type", mimeType)
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Content-Security-Policy", "default-src 'none'; media-src 'self'")
		w.Header().Set("Cache-Control", "private, max-age=3600")
		_, _ = w.Write(raw)
	})

	// WebSocket 信令与实时房间同步
	mux.HandleFunc("/ws", hub.ServeWS)

	// 静态前端资源与 SPA 路由回退
	mux.Handle("/", static.Handler(cfg.StaticDir))

	handler := withCORS(cfg, mux)

	srv := &http.Server{
		Addr:              ":" + cfg.Port,
		Handler:           handler,
		ReadHeaderTimeout: 10 * time.Second,
	}

	// 监听系统信号以优雅关闭服务
	go func() {
		sigCh := make(chan os.Signal, 1)
		signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
		<-sigCh
		log.Println("收到退出信号，正在优雅关闭 BayChat 服务...")
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = srv.Shutdown(ctx)
	}()

	log.Printf("BayChat 服务已启动，监听端口 :%s（域名：%s，数据目录：%s）", cfg.Port, cfg.Domain, cfg.DataDir)
	if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatalf("服务运行异常退出: %v", err)
	}
}

func extractPassword(r *http.Request) string {
	if pw := r.Header.Get("X-Room-Password"); pw != "" {
		return pw
	}
	return r.URL.Query().Get("password")
}

func extractActorName(r *http.Request) string {
	raw := strings.TrimSpace(r.Header.Get("X-Actor-Name"))
	if raw == "" {
		return ""
	}
	if decoded, err := url.QueryUnescape(raw); err == nil {
		return decoded
	}
	return raw
}

func withCORS(cfg *config.Config, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		origin := r.Header.Get("Origin")
		if origin != "" {
			if cfg.AllowedOrigin == "" || strings.EqualFold(origin, cfg.AllowedOrigin) {
				w.Header().Set("Access-Control-Allow-Origin", origin)
				w.Header().Set("Vary", "Origin")
				w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
				w.Header().Set("Access-Control-Allow-Headers", "Content-Type, X-Room-Password, X-Actor-Name")
			}
		}
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func writeJSON(w http.ResponseWriter, status int, data any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(data)
}

func writeError(w http.ResponseWriter, status int, code, msg string) {
	writeJSON(w, status, map[string]any{
		"errorCode": code,
		"error":     msg,
	})
}

func writeRoomError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, room.ErrInvalidRoomCode):
		writeError(w, http.StatusBadRequest, "INVALID_ROOM_CODE", err.Error())
	case errors.Is(err, room.ErrInvalidRetentionDays):
		writeError(w, http.StatusBadRequest, "INVALID_RETENTION_DAYS", err.Error())
	case errors.Is(err, room.ErrInvalidMaxFileSize):
		writeError(w, http.StatusBadRequest, "INVALID_MAX_FILE_SIZE", err.Error())
	case errors.Is(err, room.ErrRoomExists):
		writeError(w, http.StatusConflict, "ROOM_EXISTS", err.Error())
	case errors.Is(err, room.ErrRoomNotFound):
		writeError(w, http.StatusNotFound, "ROOM_NOT_FOUND", err.Error())
	case errors.Is(err, room.ErrRoomExpired):
		writeError(w, http.StatusGone, "ROOM_EXPIRED", err.Error())
	case errors.Is(err, room.ErrWrongPassword):
		writeError(w, http.StatusForbidden, "WRONG_PASSWORD", err.Error())
	case errors.Is(err, room.ErrRoomFull):
		writeError(w, http.StatusConflict, "ROOM_FULL", err.Error())
	case errors.Is(err, room.ErrNoPublicRoom):
		writeError(w, http.StatusNotFound, "NO_PUBLIC_ROOM", err.Error())
	case errors.Is(err, room.ErrMediaTooLarge):
		writeError(w, http.StatusRequestEntityTooLarge, "MEDIA_TOO_LARGE", err.Error())
	case errors.Is(err, room.ErrMediaNotFound):
		writeError(w, http.StatusNotFound, "MEDIA_NOT_FOUND", err.Error())
	case errors.Is(err, room.ErrUnsupportedMediaType):
		writeError(w, http.StatusUnsupportedMediaType, "UNSUPPORTED_MEDIA_TYPE", err.Error())
	default:
		writeError(w, http.StatusBadRequest, "BAD_REQUEST", err.Error())
	}
}
