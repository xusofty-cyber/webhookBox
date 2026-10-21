# 从源码构建（默认）。机器内存 < 2G 时不建议在服务器上跑这个，
# 用 Dockerfile.prebuilt 走"本地交叉编译 + 上传二进制"的路线。
FROM golang:1.22-alpine AS build
WORKDIR /src

ENV CGO_ENABLED=0
COPY go.mod go.sum ./
RUN go mod download

COPY *.go ./
RUN go build -trimpath -ldflags="-s -w" -o /out/hookbox .

FROM alpine:3.20

RUN apk add --no-cache ca-certificates tzdata \
 && adduser -D -u 10001 -h /app hookbox \
 && mkdir -p /data && chown hookbox:hookbox /data

COPY --from=build /out/hookbox /app/hookbox

USER hookbox
WORKDIR /app
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s \
  CMD wget -qO- http://127.0.0.1:8080/healthz || exit 1

ENTRYPOINT ["/app/hookbox"]
