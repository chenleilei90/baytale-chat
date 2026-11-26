package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"

	"baychat/server/internal/config"
	"baychat/server/internal/nearby"
	"baychat/server/internal/pickup"
	"baychat/server/internal/plane"
	"baychat/server/internal/ratelimit"
	"baychat/server/internal/room"
	"baychat/server/internal/static"
	"baychat/server/internal/turn"
	"github.com/gorilla/websocket"
)

func main() {
	cfg := config.Load()

	hub, err := room.NewHub(cfg)
	if err != nil {
		log.Fatalf("初始化房间服务失败: %v", err)
	}
	defer hub.Close()

	reportStore, err := room.NewReportStore(cfg.DataDir)
	if err != nil {
		log.Fatalf("初始化举报存储失败: %v", err)
	}

	pickupStore, err := pickup.NewStore(pickup.Config{
		DataDir:     cfg.DataDir,
		MaxFileMB:   cfg.PickupMaxFileMB,
		MaxTotalMB:  cfg.PickupMaxTotalMB,
		DiskQuotaMB: cfg.PickupDiskQuotaMB,
	})
	if err != nil {
		log.Fatalf("初始化取件码存储失败: %v", err)
	}
	defer pickupStore.Close()

	nearbySvc := nearby.NewService(hub)
	defer nearbySvc.Close()

	planeSvc := plane.NewService(hub)
	defer planeSvc.Close()

	limiter := ratelimit.New(map[string]ratelimit.Rule{
		"POST /api/rooms":               {Capacity: 30, Window: 10 * time.Minute},
		"GET /api/rooms/{code}":         {Capacity: 120, Window: time.Minute},
		"POST /api/rooms/{code}/verify": {Capacity: 30, Window: time.Minute},
		"POST /api/pickups":             {Capacity: 10, Window: 10 * time.Minute},
		"GET /api/pickups/{code}":       {Capacity: 40, Window: time.Minute},
		"POST /api/nearby/heartbeat":    {Capacity: 40, Window: time.Minute},
		"POST /api/nearby/invite":       {Capacity: 20, Window: time.Minute},
		"GET /api/planes":               {Capacity: 60, Window: time.Minute},
		"POST /api/planes":              {Capacity: 10, Window: 10 * time.Minute},
		"POST /api/planes/{id}/join":    {Capacity: 30, Window: time.Minute},
		"POST /api/planes/{id}/report":  {Capacity: 10, Window: time.Hour},
		"POST /api/report":              {Capacity: 10, Window: time.Hour},
		"POST /api/asr":                 {Capacity: 30, Window: time.Minute},
	})

	// 定期清理限流器的空闲令牌桶
	stopLimiterSweep := make(chan struct{})
	defer close(stopLimiterSweep)
	go func() {
		ticker := time.NewTicker(10 * time.Minute)
		defer ticker.Stop()
		for {
			select {
			case <-stopLimiterSweep:
				return
			case <-ticker.C:
				limiter.PurgeIdle(2 * time.Hour)
			}
		}
	}()

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
			"historyMaxFileBytes":  cfg.HistoryMaxFileBytes,
			"maxAttachmentMB":      cfg.MaxAttachmentMB,
			"defaultRoomMaxFileMB": cfg.DefaultRoomMaxFileMB,
			"pickupMaxFileMB":      cfg.PickupMaxFileMB,
			"pickupMaxTotalMB":     cfg.PickupMaxTotalMB,
			"hasTurn":              hasTurn,
			"iceServers":           iceServers,
		})
	})

	// 创建房间（支持 fixed 固定不变 / ephemeral 每次聊完就变）
	mux.HandleFunc("POST /api/rooms", func(w http.ResponseWriter, r *http.Request) {
		if !allowRate(limiter, "POST /api/rooms", w, r) {
			return
		}
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
		if !allowRate(limiter, "GET /api/rooms/{code}", w, r) {
			return
		}
		summary, err := hub.GetRoomInfo(r.PathValue("code"))
		if err != nil {
			writeRoomError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, summary)
	})

	// 校验房间密码
	mux.HandleFunc("POST /api/rooms/{code}/verify", func(w http.ResponseWriter, r *http.Request) {
		if !allowRate(limiter, "POST /api/rooms/{code}/verify", w, r) {
			return
		}
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
		ownerToken := extractOwnerToken(r)
		password := extractPassword(r)
		actor := extractActorName(r)
		if err := hub.DeleteRoom(r.PathValue("code"), ownerToken, password, actor); err != nil {
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
		ownerToken := extractOwnerToken(r)
		actor := extractActorName(r)
		meta, err := hub.ClearHistory(r.PathValue("code"), ownerToken, extractPassword(r), actor)
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
		actor := body.ActorName
		if actor == "" {
			actor = extractActorName(r)
		}
		meta, cleared, err := hub.UpdateRetention(r.PathValue("code"), extractOwnerToken(r), password, body.RetentionDays, actor)
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
		actor := body.ActorName
		if actor == "" {
			actor = extractActorName(r)
		}
		ownerToken := extractOwnerToken(r)
		resp := map[string]any{}
		if body.MaxFileSizeMB != nil {
			updatedMB, err := hub.UpdateMaxFileSize(r.PathValue("code"), ownerToken, password, *body.MaxFileSizeMB, actor)
			if err != nil {
				writeRoomError(w, err)
				return
			}
			resp["maxFileSizeMB"] = updatedMB
		}
		if body.AllowRandom != nil {
			updatedAllow, err := hub.UpdateAllowRandom(r.PathValue("code"), ownerToken, password, *body.AllowRandom, actor)
			if err != nil {
				writeRoomError(w, err)
				return
			}
			resp["allowRandom"] = updatedAllow
		}
		writeJSON(w, http.StatusOK, resp)
	})

	// 房主修改房间密码与主题（设置密码后强制关闭 allowRandom 并广播）
	mux.HandleFunc("PUT /api/rooms/{code}/profile", func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Password  *string `json:"password"`
			Topic     *string `json:"topic"`
			ActorName string  `json:"actorName"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 16*1024)).Decode(&body); err != nil || (body.Password == nil && body.Topic == nil) {
			writeError(w, http.StatusBadRequest, "INVALID_JSON", "请提供有效的房间资料参数")
			return
		}
		actor := body.ActorName
		if actor == "" {
			actor = extractActorName(r)
		}
		summary, err := hub.UpdateRoomProfile(r.PathValue("code"), extractOwnerToken(r), extractPassword(r), body.Password, body.Topic, actor)
		if err != nil {
			writeRoomError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, summary)
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

	// 上传普通历史附件到房间限时缓存（受 HISTORY_MAX_FILE_MB 限制，默认 100MB；到期或清房自动清除）
	mux.HandleFunc("POST /api/rooms/{code}/files", func(w http.ResponseWriter, r *http.Request) {
		rawName := strings.TrimSpace(r.Header.Get("X-File-Name"))
		if decoded, err := url.QueryUnescape(rawName); err == nil && decoded != "" {
			rawName = decoded
		}
		if rawName == "" {
			rawName = strings.TrimSpace(r.URL.Query().Get("name"))
		}
		mimeType := r.Header.Get("Content-Type")
		fileID, meta, err := hub.SaveRoomFile(r.PathValue("code"), extractPassword(r), rawName, mimeType, r.Body)
		if err != nil {
			writeRoomError(w, err)
			return
		}
		writeJSON(w, http.StatusCreated, map[string]any{
			"fileId":   fileID,
			"mediaId":  fileID,
			"fileName": meta.FileName,
			"mime":     meta.MimeType,
			"size":     meta.Size,
		})
	})

	// 下载房间限时缓存的历史附件（强制 attachment 下载 + 防嗅探 + 沙盒 CSP，支持 HTTP Range 断点下载）
	mux.HandleFunc("GET /api/rooms/{code}/files/{fileId}", func(w http.ResponseWriter, r *http.Request) {
		f, meta, modTime, err := hub.OpenRoomFile(r.PathValue("code"), extractPassword(r), r.PathValue("fileId"))
		if err != nil {
			writeRoomError(w, err)
			return
		}
		defer func() { _ = f.Close() }()

		w.Header().Set("Content-Type", "application/octet-stream")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Content-Security-Policy", "default-src 'none'; sandbox")
		w.Header().Set("Cache-Control", "private, max-age=3600")
		w.Header().Set(
			"Content-Disposition",
			`attachment; filename="download.bin"; filename*=UTF-8''`+url.PathEscape(meta.FileName),
		)
		http.ServeContent(w, r, meta.FileName, modTime, f)
	})

	// 违规举报接口（追加写入 reports.jsonl，不存明文 IP）
	mux.HandleFunc("POST /api/report", func(w http.ResponseWriter, r *http.Request) {
		if !allowRate(limiter, "POST /api/report", w, r) {
			return
		}
		var req room.ReportRequest
		if err := json.NewDecoder(io.LimitReader(r.Body, 32*1024)).Decode(&req); err != nil {
			writeError(w, http.StatusBadRequest, "INVALID_JSON", "请求体 JSON 格式错误")
			return
		}
		if err := reportStore.Append(req, ratelimit.ClientIP(r), time.Now()); err != nil {
			writeRoomError(w, err)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	})

	// ================= 取件码离线投递接口 =================

	// 创建取件码
	mux.HandleFunc("POST /api/pickups", func(w http.ResponseWriter, r *http.Request) {
		if !allowRate(limiter, "POST /api/pickups", w, r) {
			return
		}
		var req pickup.CreateRequest
		if err := json.NewDecoder(io.LimitReader(r.Body, 128*1024)).Decode(&req); err != nil {
			writeError(w, http.StatusBadRequest, "INVALID_JSON", "请求体 JSON 格式错误")
			return
		}
		resp, err := pickupStore.Create(req)
		if err != nil {
			writePickupError(w, err)
			return
		}
		writeJSON(w, http.StatusCreated, resp)
	})

	// 查询取件码内单文件已上传字节数（断点续传）
	mux.HandleFunc("HEAD /api/pickups/{code}/files/{fileId}", func(w http.ResponseWriter, r *http.Request) {
		uploaded, err := pickupStore.GetUploadedBytes(r.PathValue("code"), r.PathValue("fileId"), extractOwnerToken(r))
		if err != nil {
			writePickupError(w, err)
			return
		}
		w.Header().Set("X-Uploaded-Bytes", strconv.FormatInt(uploaded, 10))
		w.WriteHeader(http.StatusOK)
	})

	// 上传或断点续传取件码文件流
	mux.HandleFunc("PUT /api/pickups/{code}/files/{fileId}", func(w http.ResponseWriter, r *http.Request) {
		var offset int64
		if rawOffset := strings.TrimSpace(r.Header.Get("X-Upload-Offset")); rawOffset != "" {
			parsed, err := strconv.ParseInt(rawOffset, 10, 64)
			if err != nil || parsed < 0 {
				writeError(w, http.StatusBadRequest, "INVALID_OFFSET", "X-Upload-Offset 格式无效")
				return
			}
			offset = parsed
		}
		if err := pickupStore.UploadFile(r.PathValue("code"), r.PathValue("fileId"), extractOwnerToken(r), offset, r.Body); err != nil {
			writePickupError(w, err)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	})

	// 查询取件码元信息（取件码不存在时额外消耗令牌防枚举）
	mux.HandleFunc("GET /api/pickups/{code}", func(w http.ResponseWriter, r *http.Request) {
		if !allowRate(limiter, "GET /api/pickups/{code}", w, r) {
			return
		}
		info, err := pickupStore.GetInfo(r.PathValue("code"), extractPickupPassword(r))
		if err != nil {
			if errors.Is(err, pickup.ErrPickupNotFound) {
				limiter.Penalize("GET /api/pickups/{code}", ratelimit.ClientIP(r), 4)
			}
			writePickupError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, info)
	})

	// 下载取件码文件（支持 Range、强制 attachment、防嗅探与沙盒 CSP）
	mux.HandleFunc("GET /api/pickups/{code}/files/{fileId}", func(w http.ResponseWriter, r *http.Request) {
		if err := pickupStore.ServeFileDownload(w, r, r.PathValue("code"), r.PathValue("fileId"), extractPickupPassword(r)); err != nil {
			writePickupError(w, err)
		}
	})

	// 创建者主动撤销删除取件码
	mux.HandleFunc("DELETE /api/pickups/{code}", func(w http.ResponseWriter, r *http.Request) {
		if err := pickupStore.Delete(r.PathValue("code"), extractOwnerToken(r)); err != nil {
			writePickupError(w, err)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	})

	// ================= 附近设备（同 IP）发现与邀请接口 =================

	mux.HandleFunc("POST /api/nearby/heartbeat", func(w http.ResponseWriter, r *http.Request) {
		if !allowRate(limiter, "POST /api/nearby/heartbeat", w, r) {
			return
		}
		var req nearby.HeartbeatRequest
		if err := json.NewDecoder(io.LimitReader(r.Body, 128*1024)).Decode(&req); err != nil {
			writeError(w, http.StatusBadRequest, "INVALID_JSON", "请求体 JSON 格式错误")
			return
		}
		resp, err := nearbySvc.Heartbeat(req, ratelimit.ClientIP(r))
		if err != nil {
			writeNearbyError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, resp)
	})

	mux.HandleFunc("POST /api/nearby/invite", func(w http.ResponseWriter, r *http.Request) {
		if !allowRate(limiter, "POST /api/nearby/invite", w, r) {
			return
		}
		var req nearby.InviteRequest
		if err := json.NewDecoder(io.LimitReader(r.Body, 128*1024)).Decode(&req); err != nil {
			writeError(w, http.StatusBadRequest, "INVALID_JSON", "请求体 JSON 格式错误")
			return
		}
		resp, err := nearbySvc.CreateInvite(req, ratelimit.ClientIP(r))
		if err != nil {
			writeNearbyError(w, err)
			return
		}
		writeJSON(w, http.StatusCreated, resp)
	})

	mux.HandleFunc("POST /api/nearby/invite/{inviteId}/respond", func(w http.ResponseWriter, r *http.Request) {
		var req nearby.RespondRequest
		if err := json.NewDecoder(io.LimitReader(r.Body, 16*1024)).Decode(&req); err != nil {
			writeError(w, http.StatusBadRequest, "INVALID_JSON", "请求体 JSON 格式错误")
			return
		}
		resp, err := nearbySvc.RespondInvite(r.PathValue("inviteId"), req)
		if err != nil {
			writeNearbyError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, resp)
	})

	mux.HandleFunc("GET /api/nearby/invite/{inviteId}", func(w http.ResponseWriter, r *http.Request) {
		status, err := nearbySvc.GetInviteStatus(r.PathValue("inviteId"))
		if err != nil {
			writeNearbyError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"status": status})
	})

	// ================= 纸飞机（公开房间文件/消息分享卡片）接口 =================

	mux.HandleFunc("GET /api/planes", func(w http.ResponseWriter, r *http.Request) {
		if !allowRate(limiter, "GET /api/planes", w, r) {
			return
		}
		limit := plane.DefaultListLimit
		if raw := strings.TrimSpace(r.URL.Query().Get("limit")); raw != "" {
			if parsed, err := strconv.Atoi(raw); err == nil && parsed > 0 {
				limit = parsed
			}
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"planes": planeSvc.List(limit),
		})
	})

	mux.HandleFunc("GET /api/planes/{id}", func(w http.ResponseWriter, r *http.Request) {
		item, err := planeSvc.Get(r.PathValue("id"))
		if err != nil {
			writePlaneError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, item)
	})

	mux.HandleFunc("POST /api/planes", func(w http.ResponseWriter, r *http.Request) {
		if !allowRate(limiter, "POST /api/planes", w, r) {
			return
		}
		var req plane.CreateRequest
		if err := json.NewDecoder(io.LimitReader(r.Body, 32*1024)).Decode(&req); err != nil {
			writeError(w, http.StatusBadRequest, "INVALID_JSON", "请求体 JSON 格式错误")
			return
		}
		if req.OwnerToken == "" {
			req.OwnerToken = extractOwnerToken(r)
		}
		if req.Password == "" {
			req.Password = extractPassword(r)
		}
		created, err := planeSvc.Create(req)
		if err != nil {
			writePlaneError(w, err)
			return
		}
		writeJSON(w, http.StatusCreated, created)
	})

	mux.HandleFunc("POST /api/planes/{id}/join", func(w http.ResponseWriter, r *http.Request) {
		if !allowRate(limiter, "POST /api/planes/{id}/join", w, r) {
			return
		}
		res, err := planeSvc.Join(r.PathValue("id"))
		if err != nil {
			writePlaneError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, res)
	})

	mux.HandleFunc("POST /api/planes/{id}/report", func(w http.ResponseWriter, r *http.Request) {
		if !allowRate(limiter, "POST /api/planes/{id}/report", w, r) {
			return
		}
		var req plane.ReportRequest
		if err := json.NewDecoder(io.LimitReader(r.Body, 16*1024)).Decode(&req); err != nil {
			writeError(w, http.StatusBadRequest, "INVALID_JSON", "请求体 JSON 格式错误")
			return
		}
		res, err := planeSvc.Report(r.PathValue("id"), req.ClientID)
		if err != nil {
			writePlaneError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"ok":      true,
			"deleted": res.Deleted,
		})
	})

	// 自托管中文语音转文字接口（转发至本机 sherpa-onnx ASR 服务）
	asrEndpoint := strings.TrimSpace(os.Getenv("ASR_ENDPOINT"))
	if asrEndpoint == "" {
		asrEndpoint = "http://127.0.0.1:18190/transcribe"
	}
	asrClient := &http.Client{Timeout: 25 * time.Second}
	mux.HandleFunc("POST /api/asr", func(w http.ResponseWriter, r *http.Request) {
		if !allowRate(limiter, "POST /api/asr", w, r) {
			return
		}
		audioBytes, err := io.ReadAll(io.LimitReader(r.Body, 6*1024*1024+1))
		if err != nil {
			writeError(w, http.StatusBadRequest, "INVALID_AUDIO", "读取音频数据失败")
			return
		}
		if len(audioBytes) == 0 {
			writeError(w, http.StatusBadRequest, "EMPTY_AUDIO", "音频内容为空")
			return
		}
		if len(audioBytes) > 6*1024*1024 {
			writeError(w, http.StatusRequestEntityTooLarge, "AUDIO_TOO_LARGE", "语音文件超过 6MB 上限")
			return
		}
		upstreamReq, err := http.NewRequestWithContext(r.Context(), http.MethodPost, asrEndpoint, bytes.NewReader(audioBytes))
		if err != nil {
			writeError(w, http.StatusServiceUnavailable, "ASR_UNAVAILABLE", "语音识别服务暂不可用")
			return
		}
		if ct := strings.TrimSpace(r.Header.Get("Content-Type")); ct != "" {
			upstreamReq.Header.Set("Content-Type", ct)
		} else {
			upstreamReq.Header.Set("Content-Type", "audio/mp4")
		}
		resp, err := asrClient.Do(upstreamReq)
		if err != nil {
			writeError(w, http.StatusServiceUnavailable, "ASR_UNAVAILABLE", "语音识别服务暂不可用，请稍后重试")
			return
		}
		defer resp.Body.Close()
		body, err := io.ReadAll(io.LimitReader(resp.Body, 256*1024))
		if err != nil || resp.StatusCode >= 400 {
			writeError(w, http.StatusServiceUnavailable, "ASR_FAILED", "语音转文字处理失败")
			return
		}
		var result struct {
			Text string `json:"text"`
		}
		if err := json.Unmarshal(body, &result); err != nil {
			writeError(w, http.StatusServiceUnavailable, "ASR_FAILED", "语音识别结果解析失败")
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"text": strings.TrimSpace(result.Text),
		})
	})

	// 实时流式语音识别 WebSocket 透传代理（转发至本机 sherpa-onnx 流式服务）
	asrWsEndpoint := strings.TrimSpace(os.Getenv("ASR_WS_ENDPOINT"))
	if asrWsEndpoint == "" {
		asrWsEndpoint = "ws://127.0.0.1:18191"
	}
	asrUpgrader := websocket.Upgrader{
		ReadBufferSize:  8192,
		WriteBufferSize: 8192,
		CheckOrigin: func(r *http.Request) bool {
			return true
		},
	}
	mux.HandleFunc("/ws/asr", func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), 90*time.Second)
		defer cancel()
		upstreamConn, _, err := websocket.DefaultDialer.DialContext(ctx, asrWsEndpoint, nil)
		if err != nil {
			writeError(w, http.StatusServiceUnavailable, "ASR_WS_UNAVAILABLE", "实时语音识别服务暂不可用")
			return
		}
		clientConn, err := asrUpgrader.Upgrade(w, r, nil)
		if err != nil {
			_ = upstreamConn.Close()
			return
		}
		defer func() {
			_ = clientConn.Close()
			_ = upstreamConn.Close()
		}()

		clientConn.SetReadLimit(2 * 1024 * 1024)
		done := make(chan struct{}, 2)

		// 客户端 -> 上游流式 ASR（二进制 PCM / "eof" 控制帧）
		go func() {
			defer func() { done <- struct{}{} }()
			for {
				_ = clientConn.SetReadDeadline(time.Now().Add(90 * time.Second))
				msgType, data, err := clientConn.ReadMessage()
				if err != nil {
					return
				}
				_ = upstreamConn.SetWriteDeadline(time.Now().Add(10 * time.Second))
				if err := upstreamConn.WriteMessage(msgType, data); err != nil {
					return
				}
			}
		}()

		// 上游流式 ASR -> 客户端（JSON 识别结果）
		go func() {
			defer func() { done <- struct{}{} }()
			for {
				_ = upstreamConn.SetReadDeadline(time.Now().Add(90 * time.Second))
				msgType, data, err := upstreamConn.ReadMessage()
				if err != nil {
					return
				}
				_ = clientConn.SetWriteDeadline(time.Now().Add(10 * time.Second))
				if err := clientConn.WriteMessage(msgType, data); err != nil {
					return
				}
			}
		}()

		select {
		case <-ctx.Done():
		case <-done:
		}
	})

	// WebSocket 信令与实时房间同步
	mux.HandleFunc("/ws", hub.ServeWS)

	// 静态前端资源与 SPA 路由回退（含 /p/{code}、/r/{code}）
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

func allowRate(limiter *ratelimit.Limiter, route string, w http.ResponseWriter, r *http.Request) bool {
	if limiter.Allow(route, ratelimit.ClientIP(r)) {
		return true
	}
	writeError(w, http.StatusTooManyRequests, "RATE_LIMITED", "请求过于频繁，请稍后再试")
	return false
}

func extractPassword(r *http.Request) string {
	if pw := r.Header.Get("X-Room-Password"); pw != "" {
		return pw
	}
	return r.URL.Query().Get("password")
}

func extractOwnerToken(r *http.Request) string {
	return strings.TrimSpace(r.Header.Get("X-Owner-Token"))
}

func extractPickupPassword(r *http.Request) string {
	if pw := r.Header.Get("X-Pickup-Password"); pw != "" {
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
				w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, HEAD, DELETE, OPTIONS")
				w.Header().Set("Access-Control-Allow-Headers", "Content-Type, X-Room-Password, X-Owner-Token, X-Pickup-Password, X-Upload-Offset, X-Actor-Name, X-File-Name")
				w.Header().Set("Access-Control-Expose-Headers", "X-Uploaded-Bytes")
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
	case errors.Is(err, room.ErrCodeReserved):
		writeError(w, http.StatusBadRequest, "CODE_RESERVED", err.Error())
	case errors.Is(err, room.ErrInvalidRoomCode):
		writeError(w, http.StatusBadRequest, "INVALID_ROOM_CODE", err.Error())
	case errors.Is(err, room.ErrInvalidRetentionDays):
		writeError(w, http.StatusBadRequest, "INVALID_RETENTION_DAYS", err.Error())
	case errors.Is(err, room.ErrInvalidMaxFileSize):
		writeError(w, http.StatusBadRequest, "INVALID_MAX_FILE_SIZE", err.Error())
	case errors.Is(err, room.ErrInvalidReport):
		writeError(w, http.StatusBadRequest, "INVALID_REPORT", err.Error())
	case errors.Is(err, room.ErrRoomExists):
		writeError(w, http.StatusConflict, "ROOM_EXISTS", err.Error())
	case errors.Is(err, room.ErrRoomNotFound):
		writeError(w, http.StatusNotFound, "ROOM_NOT_FOUND", err.Error())
	case errors.Is(err, room.ErrRoomExpired):
		writeError(w, http.StatusGone, "ROOM_EXPIRED", err.Error())
	case errors.Is(err, room.ErrNotOwner):
		writeError(w, http.StatusForbidden, "NOT_OWNER", err.Error())
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

func writePickupError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, pickup.ErrPickupNotFound), errors.Is(err, pickup.ErrFileNotFound):
		writeError(w, http.StatusNotFound, "PICKUP_NOT_FOUND", err.Error())
	case errors.Is(err, pickup.ErrPickupExpired):
		writeError(w, http.StatusGone, "PICKUP_EXPIRED", err.Error())
	case errors.Is(err, pickup.ErrPickupPasswordRequired):
		writeError(w, http.StatusForbidden, "PICKUP_PASSWORD_REQUIRED", err.Error())
	case errors.Is(err, pickup.ErrWrongPassword):
		writeError(w, http.StatusForbidden, "WRONG_PASSWORD", err.Error())
	case errors.Is(err, pickup.ErrNotOwner):
		writeError(w, http.StatusForbidden, "NOT_OWNER", err.Error())
	case errors.Is(err, pickup.ErrFileTooLarge):
		writeError(w, http.StatusRequestEntityTooLarge, "FILE_TOO_LARGE", err.Error())
	case errors.Is(err, pickup.ErrQuotaExceeded):
		writeError(w, http.StatusInsufficientStorage, "QUOTA_EXCEEDED", err.Error())
	case errors.Is(err, pickup.ErrFileNotReady):
		writeError(w, http.StatusConflict, "FILE_NOT_READY", err.Error())
	case errors.Is(err, pickup.ErrSizeMismatch):
		writeError(w, http.StatusBadRequest, "SIZE_MISMATCH", err.Error())
	default:
		writeError(w, http.StatusBadRequest, "BAD_REQUEST", err.Error())
	}
}

func writeNearbyError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, nearby.ErrDeviceOffline):
		writeError(w, http.StatusConflict, "DEVICE_OFFLINE", err.Error())
	case errors.Is(err, nearby.ErrCellularBlocked):
		writeError(w, http.StatusBadRequest, "CELLULAR_BLOCKED", err.Error())
	case errors.Is(err, nearby.ErrInviteNotFound):
		writeError(w, http.StatusNotFound, "INVITE_NOT_FOUND", err.Error())
	case errors.Is(err, nearby.ErrInviteExpired):
		writeError(w, http.StatusGone, "INVITE_EXPIRED", err.Error())
	case errors.Is(err, nearby.ErrInviteNotTarget):
		writeError(w, http.StatusForbidden, "FORBIDDEN", err.Error())
	case errors.Is(err, nearby.ErrInviteAlreadyDone):
		writeError(w, http.StatusConflict, "INVITE_ALREADY_HANDLED", err.Error())
	default:
		writeError(w, http.StatusBadRequest, "BAD_REQUEST", err.Error())
	}
}

func writePlaneError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, plane.ErrPlaneNotFound), errors.Is(err, room.ErrRoomNotFound):
		writeError(w, http.StatusNotFound, "PLANE_NOT_FOUND", err.Error())
	case errors.Is(err, plane.ErrPlaneExpired), errors.Is(err, room.ErrRoomExpired):
		writeError(w, http.StatusGone, "PLANE_EXPIRED", err.Error())
	case errors.Is(err, plane.ErrPlaneCooldown):
		writeError(w, http.StatusTooManyRequests, "PLANE_COOLDOWN", err.Error())
	case errors.Is(err, plane.ErrPlaneLimitSender):
		writeError(w, http.StatusConflict, "PLANE_LIMIT_SENDER", err.Error())
	case errors.Is(err, plane.ErrPlaneLimitGlobal):
		writeError(w, http.StatusTooManyRequests, "PLANE_LIMIT_GLOBAL", err.Error())
	case errors.Is(err, plane.ErrPlaneEntryClosed):
		writeError(w, http.StatusForbidden, "PLANE_ENTRY_CLOSED", err.Error())
	case errors.Is(err, plane.ErrPlaneEntryExpired):
		writeError(w, http.StatusConflict, "PLANE_ENTRY_EXPIRED", err.Error())
	case errors.Is(err, plane.ErrPlaneOwnerRequired):
		writeError(w, http.StatusForbidden, "PLANE_OWNER_REQUIRED", err.Error())
	case errors.Is(err, plane.ErrPlanePasswordRequired):
		writeError(w, http.StatusForbidden, "PLANE_PASSWORD_REQUIRED", err.Error())
	case errors.Is(err, plane.ErrPlaneSelfReport):
		writeError(w, http.StatusBadRequest, "PLANE_SELF_REPORT", err.Error())
	case errors.Is(err, plane.ErrPlaneAlreadyReported):
		writeError(w, http.StatusConflict, "PLANE_ALREADY_REPORTED", err.Error())
	default:
		writeError(w, http.StatusBadRequest, "INVALID_PLANE", err.Error())
	}
}
