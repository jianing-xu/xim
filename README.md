# XIM

基于 Netty 的单实例即时通信 MVP。前端使用 React + TypeScript + Vite，后端使用 Java 25 + Maven，支持浏览器 WebSocket 与 TCP 控制台客户端互通。共享前端通过 Tauri 2 支持 macOS、Windows、Android、iOS App，后端保持独立服务。

## 核心功能

- 账号注册、密码登录、修改密码及登录凭证撤销。
- 联系人搜索、离线私聊、群聊创建/加入/退出及成员查看。
- 消息持久化、历史分页、会话摘要和未读计数。
- 刷新与断线后自动恢复登录、补拉消息；发送失败可在原消息旁重试。
- 默认 SQLite，支持配置 PostgreSQL。

## 本地启动

需要 JDK 25、Maven 3.9+、Node.js 20.19+ 或 22.12+。确保 `JAVA_HOME`、Maven 和 IDE 使用同一 JDK。

在项目根目录启动后端（macOS/Linux）：

```sh
./backend/run.sh -Dxim.web.cookieSecure=false
```

另一个终端启动前端：

```sh
cd frontend
npm ci
npm run dev
```

打开 [http://localhost:5173](http://localhost:5173)，在登录页创建账号后登录。测试两个账号时使用独立浏览器配置或隐私窗口。

后端默认监听 TCP `8000`、HTTP/WebSocket `8080`；Vite 代理 `/ws` 和 `/api`。健康检查：[http://127.0.0.1:8080/api/health](http://127.0.0.1:8080/api/health)。`cookieSecure=false` 仅用于本机 HTTP 开发，生产保持默认值并使用 HTTPS/WSS。

默认不开放演示账号。如需本地演示：

```sh
XIM_AUTH_DEMO=true ./backend/run.sh -Dxim.web.cookieSecure=false
```

演示凭证为 `root/root`、`aaa/aaa`、`bbb/bbb`、`ccc/ccc`，仅用于开发。

## App 打包

在 `frontend/` 配置 `.env.app.local` 中的 `XIM_APP_SERVER_URL=https://实际服务域名` 后，使用 `npm run app:macos`、`npm run app:windows`、`npm run app:android`、`npm run app:ios`。移动端首次执行对应 `app:android:init` / `app:ios:init`，按平台安装工具链并配置签名。桌面开发使用 `npm run app:dev`；Web 原有构建与部署命令继续有效。

环境、Cookie/Origin 适配、各端签名与产物路径见 [App 打包说明](docs/APP_PACKAGING.md)，实际验证状态见 [多平台验收记录](docs/APP_ACCEPTANCE.md)。

## 常用配置

JVM 参数直接传给 `backend/run.sh`：

```sh
./backend/run.sh -Dxim.web.port=9090 -Dxim.db.sqlite.path=/absolute/path/xim.db -Dxim.web.cookieSecure=false
```

| JVM 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `xim.host` | `0.0.0.0` | 后端监听地址 |
| `xim.port` | `8000` | TCP 端口 |
| `xim.web.port` | `8080` | HTTP/WebSocket 端口；`-1` 关闭 Web 入口 |
| `xim.web.allowedOrigins` | `http://localhost:5173,http://127.0.0.1:5173` | 允许的前端 Origin，逗号分隔、精确匹配 |
| `xim.web.cookieSecure` | `true` | 登录 Cookie 使用 Secure 属性 |
| `xim.session.ttlSeconds` | `604800` | 登录凭证有效期，默认 7 天 |

修改开发代理地址：复制 `frontend/.env.example` 为 `frontend/.env.local`，设置 `XIM_BACKEND_URL` 后重启 Vite。生产推荐同源代理 `/ws` 和 `/api/`，示例见 [部署说明](deployment/README.md)。

默认数据库为 `backend/data/xim.db`（通过启动脚本运行），首次启动自动创建并迁移。数据库配置支持以下环境变量；对应 JVM 参数优先，脚本不自动读取 `.env`：

| JVM 参数 | 环境变量 | 默认值 |
| --- | --- | --- |
| `xim.db.type` | `XIM_DB_TYPE` | `sqlite`；可选 `pgsql` |
| `xim.db.sqlite.path` | `XIM_DB_SQLITE_PATH` | `data/xim.db`，相对于工作目录 |
| `xim.db.url` | `XIM_DB_URL` | `jdbc:postgresql://localhost:5432/xim` |
| `xim.db.user` | `XIM_DB_USER` | 空 |
| `xim.db.password` | `XIM_DB_PASSWORD` | 空 |

PostgreSQL 需预先创建数据库和有建表权限的账号。密码通过环境或密钥管理提供，不写入版本库。切换数据库类型不会自动迁移数据。

旧账号设密或管理员重置密码时，先停止后端，再使用相同数据库配置运行交互工具：

```sh
./backend/account-admin.sh <账号>
```

工具保留已有用户 ID、群成员和历史，并使旧凭证失效。

## 构建与测试

```sh
# 项目根目录：后端单元与集成测试
mvn -f backend/pom.xml clean verify

# 前端测试与生产构建
cd frontend
npm test
npm run build

# 浏览器端到端测试（默认使用本机 Chrome）
npm run e2e:prepare
npm run test:e2e
```

后端报告位于 `backend/target/surefire-reports/`、`backend/target/failsafe-reports/`；浏览器报告位于 `frontend/playwright-report/`。PostgreSQL 集成测试需配置 `XIM_TEST_PG_URL`、`XIM_TEST_PG_USER`、`XIM_TEST_PG_PASSWORD`，未配置时跳过。

## 部署与项目结构

```sh
# 项目根目录：构建发布包并在本机运行 HTTPS 服务
./scripts/build-release.sh
./deployment/local.sh /absolute/path/to/xim-local-data
```

打开 [https://127.0.0.1:7443](https://127.0.0.1:7443)。本地脚本使用自签名证书，首次访问需在浏览器中确认；指定目录保存数据库和日志。Linux nginx/systemd 示例、生产配置和 SQLite 备份/恢复见 [部署说明](deployment/README.md)。

- [`frontend/`](frontend/)：浏览器界面与客户端；协议类型见 [`src/protocol.ts`](frontend/src/protocol.ts)。
- [`backend/`](backend/)：认证、聊天、存储及 TCP 客户端；IDE 可运行 `cn.xjn.xim.server.NettyServer` 或 `cn.xjn.xim.client.NettyClient`。
- [`protocol/xim.proto`](protocol/xim.proto)：共享协议定义。
- [`deployment/`](deployment/) 与 [`scripts/`](scripts/)：部署、构建和备份工具。

## 使用边界

单账号只保留一条连接，主动登录会替换旧连接。「已发送」表示服务端已持久化，不表示对方已读；重试请使用原消息旁的按钮。

项目面向单实例，在线路由不跨实例共享；尚无找回密码或账号删除。生产需由反向代理提供 HTTPS/WSS，原始 TCP 无 TLS。SQLite 在线备份使用 `scripts/sqlite-backup.py`，不要直接复制正在写入的数据库；删除数据库会丢失账号与聊天数据。

## 协议与验收

聊天协议使用 Protobuf v2 二进制载荷。生成命令、旧客户端升级、精确 int64 与完整分批同步见 [协议说明](protocol/README.md)；逐项实测结果和外部条件见 [验收记录](deployment/ACCEPTANCE.md)。
