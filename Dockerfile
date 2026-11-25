# 第一阶段：使用 Node 构建前端静态资源
FROM node:22-alpine AS web-builder
WORKDIR /src/web

COPY web/package.json web/package-lock.json* ./
RUN npm install

COPY web/ ./
RUN npm run build

# 第二阶段：使用 Go 编译单二进制服务端（并将前端 dist 打包进二进制）
FROM golang:1.23-alpine AS go-builder
WORKDIR /src/server

# 如果服务器在国内，可按需开启代理（默认走官方源，自动兼容）
ENV CGO_ENABLED=0

COPY server/ ./
RUN rm -rf ./internal/static/dist
COPY --from=web-builder /src/web/dist ./internal/static/dist

RUN go mod tidy && go build -trimpath -ldflags="-s -w" -o /out/baychat ./main.go

# 第三阶段：极简运行时镜像
FROM alpine:3.20
RUN apk add --no-cache ca-certificates tzdata
WORKDIR /app

COPY --from=go-builder /out/baychat /app/baychat
RUN mkdir -p /data

ENV PORT=8080
ENV DATA_DIR=/data

EXPOSE 8080
VOLUME ["/data"]

ENTRYPOINT ["/app/baychat"]
