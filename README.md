# BayChat — 跨公网 WebRTC P2P 文件闪传与实时互动空间

> 免注册、打开网页即用：把**跨公网 GB 级文件直传、即时聊天、语音连麦、实时投屏、多人同屏白板、同步番茄自习室**浓缩在一个仅 ~6.5MB 的 Go 单二进制服务里，**1 核 1GB 服务器实测仅占 ~28 MB 内存即可流畅运行**。

![Go](https://img.shields.io/badge/Backend-Go%201.23%20Single%20Binary-00ADD8?style=flat-square&logo=go)
![React](https://img.shields.io/badge/Frontend-React%20%2B%20TypeScript%20%2B%20Vite-61DAFB?style=flat-square&logo=react)
![WebRTC](https://img.shields.io/badge/Transport-WebRTC%20P2P%20%2B%20coturn%20TURN-10B981?style=flat-square)
![RAM](https://img.shields.io/badge/Memory-Only%20~28MB%20RAM-f43f5e?style=flat-square)

---

## 这是干什么用的？

你是否经常遇到这些场景：
- **跨电脑 / 手机互传大文件或整个文件夹**：微信限制大小且压缩画质、网盘限速还要登录、AirDrop 只能同局域网苹果设备？
- **和朋友 / 同事临时传东西顺便聊两句**：想快速甩个截图、验证码、代码片段或文档，聊完即焚不留痕迹？
- **异地搭子 / 远程协作**：想顺手开麦聊两句、共享屏幕看代码、一起在白板上画草图贴便签、或开个番茄钟放点雨声一起自习？

**BayChat 就是为此而生的自托管轻量级网页应用**：
1. 打开浏览器即可建房，把 **6 位口令 / 二维码 / 邀请链接**（支持 `/?room=123456` 或 `/r/123456`）发给对方，**无需注册账号、无需安装客户端**；
2. 浏览器之间通过 **WebRTC P2P 直接打洞互连**，文件、语音、投屏、白板全部点对点直传，**不经过服务器中转、跑满双方带宽**；
3. 遇到严格公司防火墙或对称 NAT 无法直连时，自动无缝切换到自建 **coturn STUN/TURN/TURNS** 中继兜底，保证 100% 连通。

---

## 核心功能一览

### 1. 跨公网高速 P2P 传输引擎
- **P2P 优先直连 + TURN 智能兜底**：基于 WebRTC Perfect Negotiation 完美协商协议，实时检测并显示 `局域网直连` / `跨公网 P2P 直连` / `服务器中转` 与毫秒级 RTT 延迟。
- **双通道分离不卡顿**：`chat` 通道与 `file` 通道分离，传几个 GB 大文件时文字聊天、发表情、画白板依然丝滑流畅。
- **GB 级低内存流式直传**：48KB 分片 + 背压流控 + 增量 **SHA-256 完整性校验**；Chrome / Edge 支持 **File System Access API 流式直写硬盘**，接收大文件不爆浏览器内存。
- **文件夹拖拽打包直传**：桌面端直接拖拽整个文件夹到聊天窗口，前端自动打包标准 UTF-8 `.zip`（中文路径零乱码）后走 P2P 直传。
- **动态可调单附件上限**：大厅与房间内均可随时调节单文件大小上限（50MB / 200MB / 500MB / 1GB / 2GB / 不限制 / 自定义 MB），实时全房同步，同时支持自定义自动免确认接收阈值与「原图无损 / 智能压缩」图片模式。

### 2. IM 级聊天体验与安全隐私控制
- **双模式房间**：
  - **闪传盲盒房（一次性）**：随机 6 位口令，支持在邀请弹窗内一键换码；全员退出或点击销毁后口令永久作废。
  - **专属秘密基地（常驻固定房）**：支持自定义房间号、可选暗号密码、服务端持久化保存与本地收藏一键空降。
- **可控的服务器滑动缓存（0 ~ 7 天）**：支持自助切换 **阅后即焚（零缓存）** 或 **最后一次聊天后保留 1~7 天**，到期自动物理清理，随时支持「一键清空」与「单条消息撤回」（前后端双重校验发送者归属，并同步物理删除关联缓存图片/语音）。
- **轻量高效输入区**：`Ctrl+V` 直接粘贴系统截图或任意文件、语音对讲泡泡、引用回复、消息搜索筛选（`Ctrl+F`）与 `.txt` 聊天记录导出，手机端与桌面端界面同样简洁清爽。

### 3. 实用多人实时协作能力
- **🎧 一键语音连麦 & 实时屏幕共享**：WebRTC 动态挂载音视频轨，侧边栏实时声纹说话光环，顶部可折叠/全屏高清投屏舞台，边看屏幕边传文件聊天。
- **🎨 多人实时同屏协作白板**：支持荧光笔 / 彩虹笔 / 细画笔 / 橡皮、星空/奶油画布、实时彩色署名协同光标、4 色可拖拽编辑共享便签、迟到自动同步画布，支持一键合图 PNG 发进聊天室。
- **🍅 陪伴自习室**：侧边栏可折叠同步番茄钟（25m / 45m / 5m）与纯前端零依赖 Web Audio 合成环境白噪音（细雨 / 海浪 / 壁炉 / 书房），常驻基地自动记录累计文件与番茄专注统计。

---

## 系统架构与资源占用

```text
                    +-------------------------------------------+
                    |        公网服务器 (1核 1GB RAM 即可)        |
                    |                                           |
  浏览器 A <----WSS/HTTPS----> Caddy / Nginx ---> BayChat (Go)  |  ~10 MB RAM
     ^              |          (自动 HTTPS)       信令/房间/静态  |
     |              |                                           |
  WebRTC P2P        |                                           |
  直连 (优先)        |                                           |
     |              |                                           |
     v              |                                           |
  浏览器 B <----UDP/TCP/TLS (仅穿透失败兜底)---> coturn STUN/TURN |  ~18 MB RAM
                    +-------------------------------------------+
```

- **极低配置要求**：实测生产环境 `baychat`（Go 单二进制，前端已通过 `go:embed` 内嵌）占约 **10 MB 内存**，`coturn` 占约 **18 MB 内存**，合计仅 **~28 MB RAM**，**1 核 1GB 内存 VPS** 即可稳定运行。
- **安全加固**：`coturn` 采用 TURN REST API 动态 HMAC-SHA1 临时凭证防盗刷、内置内网/回环 IP 禁连防 SSRF 与带宽配额限速；媒体缓存基于文件内容魔数校验 MIME，严格拒绝 HTML/SVG 杜绝存储型 XSS。

---

## 快速部署指南（两种方案任选）

### 方案一：Docker Compose 一键部署（推荐，3 分钟上线）

适合干净的 Linux 公网服务器（Ubuntu / Debian / CentOS 等），**自动包含 BayChat + Caddy 自动 HTTPS + coturn STUN/TURN**。

#### 第 1 步：配置域名 DNS

在你的域名解析控制台（如 Cloudflare、阿里云、腾讯云）添加一条 `A` 记录：

| 类型 | 主机记录 | 记录值 | 代理状态 |
| :--- | :--- | :--- | :--- |
| `A` | `chat`（示例：`chat.example.com`） | 你的服务器公网 IPv4 | **仅 DNS（关闭 CDN / 灰色云）** |

#### 第 2 步：放行服务器防火墙 / 云安全组端口

| 端口 | 协议 | 用途 |
| :--- | :--- | :--- |
| `80` | TCP | Let's Encrypt HTTPS 证书自动校验与 HTTP→HTTPS 跳转 |
| `443` | TCP / UDP | HTTPS 网页访问与 `wss://` 信令 |
| `3478` | TCP / UDP | STUN 打洞与 TURN 中转主端口 |
| `49152-49300` | UDP | TURN 媒体中继端口范围 |

#### 第 3 步：拉取代码并一键启动

```bash
# 1. 克隆项目
git clone https://github.com/chenleilei90/baytale-chat.git
cd baytale-chat

# 2. 复制环境变量模板
cp .env.example .env

# 3. 生成一个强随机 TURN 密钥（复制输出结果）
openssl rand -hex 32

# 4. 编辑 .env，填入你的 DOMAIN、PUBLIC_IP、TURN_HOST 和刚生成的 TURN_SECRET
nano .env

# 5. 一键构建并后台启动全部服务
docker compose up -d --build

# 6. 检查运行状态与健康接口
docker compose ps
curl -s https://你的域名/api/health
```

稍等 10~30 秒待 Caddy 自动签发完免费 HTTPS 证书，打开 `https://你的域名` 即可直接开始使用！

---

### 方案二：轻量原生单二进制部署（适合 1 核 1GB 小内存或已有 Nginx 的服务器）

如果服务器内存只有 1GB、不想装 Docker，或服务器已运行 Nginx 与其他站点，推荐此方案（实测总增量内存仅 **~28 MB**）：

#### 1. 编译单二进制（可在任意带 Go 1.23+ 与 Node 20+ 的机器交叉编译）

```bash
# 构建前端并拷贝到 Go 内嵌目录
cd web && npm install && npm run build
rm -rf ../server/internal/static/dist
cp -r dist ../server/internal/static/dist

# 编译 Linux amd64 单文件（仅约 6.5 MB）
cd ../server
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o ../baychat .
```

#### 2. 安装并配置原生 `coturn`

```bash
sudo apt-get update && sudo apt-get install -y coturn
sudo sed -i 's/^#\?TURNSERVER_ENABLED=.*/TURNSERVER_ENABLED=1/' /etc/default/coturn
```

生成随机密钥 `TURN_SECRET=$(openssl rand -hex 32)`，写入 `/etc/turnserver.conf`（将 `<PUBLIC_IP>`、`<TURN_HOST>`、`<TURN_SECRET>` 替换为真实值）：

```ini
listening-port=3478
listening-ip=0.0.0.0
external-ip=<PUBLIC_IP>
realm=<TURN_HOST>
fingerprint
use-auth-secret
static-auth-secret=<TURN_SECRET>
stale-nonce=600
min-port=49152
max-port=49300
total-quota=100
max-bps=2000000
no-cli
no-loopback-peers
no-multicast-peers
denied-peer-ip=10.0.0.0-10.255.255.255
denied-peer-ip=127.0.0.0-127.255.255.255
denied-peer-ip=172.16.0.0-172.31.255.255
denied-peer-ip=192.168.0.0-192.168.255.255
denied-peer-ip=::1
```

重启并设为开机自启：`sudo systemctl enable --now coturn`

#### 3. 配置 `baychat.service`（systemd 守护 + 内存上限保护）

将编译好的 `baychat` 上传到 `/opt/baychat/baychat`，创建低权限用户与 `/etc/baychat.env`：

```bash
sudo useradd --system --home /opt/baychat --shell /usr/sbin/nologin baychat
sudo mkdir -p /opt/baychat/data && sudo chown -R baychat:baychat /opt/baychat
sudo chmod 755 /opt/baychat/baychat
```

`/etc/baychat.env`（`chmod 640`，属主 `root:baychat`）：

```ini
PORT=8787
DATA_DIR=/opt/baychat/data
DOMAIN=chat.example.com
TURN_HOST=turn.example.com
TURN_PORT=3478
TURN_TLS_PORT=0
TURN_SECRET=<与 turnserver.conf 相同的密钥>
MAX_ROOM_PEERS=6
MAX_ATTACHMENT_MB=0
DEFAULT_ROOM_MAX_FILE_MB=500
HISTORY_MAX_MEDIA_MB=10
GOMEMLIMIT=128MiB
GOGC=50
```

`/etc/systemd/system/baychat.service`：

```ini
[Unit]
Description=BayChat P2P Transfer & Realtime Hangout Server
After=network-online.target coturn.service

[Service]
Type=simple
User=baychat
Group=baychat
WorkingDirectory=/opt/baychat
EnvironmentFile=/etc/baychat.env
ExecStart=/opt/baychat/baychat
Restart=always
RestartSec=3
MemoryMax=180M
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=/opt/baychat/data

[Install]
WantedBy=multi-user.target
```

启动服务并验证：

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now baychat
curl -s "http://127.0.0.1:8787/api/config?clientId=check"
# 看到 "hasTurn":true 即表示 STUN/TURN 凭证联动正常
```

#### 4. 配置 Nginx 反向代理（含 WebSocket 与 HTTPS）

```nginx
map $http_upgrade $baychat_connection_upgrade {
    default upgrade;
    ''      close;
}

server {
    listen 80;
    server_name chat.example.com;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    server_name chat.example.com;

    ssl_certificate     /etc/letsencrypt/live/chat.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/chat.example.com/privkey.pem;
    client_max_body_size 110m;

    location = /ws {
        proxy_pass http://127.0.0.1:8787;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $baychat_connection_upgrade;
        proxy_set_header Host $http_host;
        proxy_read_timeout 3600s;
        proxy_buffering off;
    }

    location / {
        proxy_pass http://127.0.0.1:8787;
        proxy_http_version 1.1;
        proxy_set_header Host $http_host;
        proxy_buffering off;
    }
}
```

> **提示（Cloudflare CDN 用户）**：如果希望主站域名 `chat.example.com` 开启 Cloudflare 橙色云代理，只需保持 **`turn.example.com` 为「仅 DNS（灰色云）」**，并在 `.env` 中设置 `TURN_HOST=turn.example.com` 即可两全其美——网页与信令走 Cloudflare 加速，大文件 P2P 与 TURN 中继 100% 直连服务器。

---

## 环境变量说明

| 变量名 | 默认值 | 说明 |
| :--- | :--- | :--- |
| `PORT` | `8080` | Go HTTP/WebSocket 监听端口 |
| `DATA_DIR` | `./data` | 固定房间元数据与聊天缓存持久化目录 |
| `STATIC_DIR` | `""` | 可选外部前端静态目录，留空则直接使用二进制内嵌静态资源 |
| `DOMAIN` | `chat.baytale.com` | 界面展示的站点域名 |
| `TURN_HOST` | `""` | STUN/TURN 域名或公网 IP |
| `TURN_PORT` | `3478` | STUN/TURN UDP & TCP 主端口 |
| `TURN_TLS_PORT` | `0` | TURNS（TLS over TCP）端口，配置证书后可设为 `5349` |
| `TURN_SECRET` | `""` | 与 `coturn` 共享的 `static-auth-secret` 密钥 |
| `TURN_TTL_SECONDS` | `43200` | 动态签发的临时 TURN 凭证有效期（秒，默认 12 小时） |
| `MAX_ROOM_PEERS` | `6` | 单房间最大成员数上限（2~12） |
| `MAX_ATTACHMENT_MB` | `0` | 服务端单附件硬上限（MB，`0` 表示不限制） |
| `DEFAULT_ROOM_MAX_FILE_MB` | `500` | 新建房间默认单附件上限（MB，`0` 表示不限制） |
| `HISTORY_MAX_MESSAGES` | `500` | 单房间最多缓存的历史消息条数（50~5000） |
| `HISTORY_MAX_MEDIA_MB` | `10` | 服务器缓存单张图片/语音上限（1~100 MB） |
| `ALLOWED_ORIGIN` | `""` | 可选限制允许跨域/WS 的 Origin，留空则允许所有来源 |

---

## 本地开发与测试

本地需要 **Go 1.23+** 与 **Node.js 20+**。

```bash
# 终端 1：启动 Go 后端（默认 :8080）
cd server
go run ./main.go

# 终端 2：启动 Vite 前端热更新服务（默认 :5173，自动代理 /api 和 /ws 到 :8080）
cd web
npm install
npm run dev
```

运行全套类型检查与 Go 竞态检测单测：

```bash
# 前端 TypeScript 检查 + 生产构建
cd web && npx tsc --noEmit && npm run build

# 后端单元测试（含 -race 并发竞态检测）
cd server && go test -v -race ./...
```

---

## 常见问题排查（FAQ）

1. **为什么语音连麦、实时投屏或网页截屏提示需要 HTTPS？**
   - 浏览器安全策略要求 `getUserMedia` / `getDisplayMedia` / `showSaveFilePicker` 必须在 `https://` 或 `http://localhost` 安全上下文下使用。使用上述 Caddy 或 Nginx 配好 HTTPS 后自动全部解锁。
2. **两个不同网络设备总是连不上或一直「正在打洞」？**
   - 检查 `https://你的域名/api/config` 中 `"hasTurn"` 是否为 `true`；
   - 确认 `TURN_HOST` 对应域名未开启 CDN 代理（必须为「仅 DNS」指向真实公网 IP）；
   - 确认云服务器安全组与系统防火墙（UFW/firewalld）已放行 `3478/udp`、`3478/tcp` 及 `49152-49300/udp`。
3. **未来能打包成 Windows 桌面客户端吗？**
   - 可以。前端全部平台相关能力（磁盘流式写入、剪贴板、截屏、提示音）均已抽离在 `web/src/lib/platform.ts`，后续接入 Tauri 即可无缝打包出约 10MB 的桌面客户端并与网页端完全互通。
