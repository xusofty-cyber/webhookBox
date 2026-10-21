# HookBox 文档

> 版本 0.1.0 ｜ 最后更新 2026-10-21

| 文档 | 内容 | 适合谁看 |
|---|---|---|
| [部署文档](部署文档.md) | 架构说明、两套中转层的完整部署步骤、本次实际部署记录、回滚方法 | 运维、接手的人 |
| [使用说明](使用说明.md) | App 怎么用、各平台怎么接 webhook、测试方法、故障排查 | 使用者、配置平台的人 |

## 三句话概括这个项目

1. 各平台（GitHub、监控告警、支付回调……）把 webhook 发到**你自己的中转服务**。
2. 中转服务验签、落库去重，然后通过一条 WebSocket 长连接实时推给手机。
3. 手机上装一个 App，收到就弹通知，历史消息本地留存。

不需要 Google 服务框架（GMS），不需要第三方推送平台，数据全程在自己手里。

## 代码位置

| 内容 | 本地 | 服务器 |
|---|---|---|
| 全部源码 | `hookbox/` | — |
| Worker 中转层 | `hookbox/worker/` | — |
| Go 中转层 | `hookbox/server/` | `/opt/hookbox/` |
| 安卓客户端 | `hookbox/android/` | — |
| 本文档 | `hookbox/docs/` | `/opt/hookbox/docs/` |

## 凭据存放位置

**文档里不写任何密钥。** 实际值统一在这些地方：

| 凭据 | 位置 |
|---|---|
| `RELAY_TOKEN` | 服务器 `~/projects/hookbox/.env` |
| `GITHUB_SECRET` / `GENERIC_SECRET` | 同上，默认留空 |
| App 里填的地址与 token | 手机 App「中转设置」，存在 DataStore |

查看方式：

```bash
ssh ubuntu@你的服务器IP
cat ~/projects/hookbox/.env
```
