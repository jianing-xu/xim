# 单实例部署与恢复

需要 Java 25、Maven 3.9+、Node 20.19+/22.12+、Python 3（标准库 sqlite3）、OpenSSL、curl。SQLite 是交付路径；运行一个后端实例。前端构建产物单独托管，数据库不放在静态目录中。

## 本地实际运行

```sh
./scripts/build-release.sh
./deployment/local.sh /absolute/path/to/xim-local-data
```

打开 https://127.0.0.1:7443。脚本仅监听回环，使用指定目录的持久数据库和日志；首次在同目录生成 30 天自签名证书，不改变系统证书信任。浏览器首次需为这个本机证书继续访问。退出脚本会停止其两个子进程，重跑复用账号/历史/Cookie。证书到期可在停机后重新生成或提供有效证书文件。调整端口：`XIM_STATIC_PORT=7444 XIM_WEB_PORT=8081 XIM_TCP_PORT=8001 ./deployment/local.sh /absolute/data`。

`build-release.sh` 执行完整 Maven 和前端单测/构建，生成 `backend/target/release/xim.jar` 与 `lib/`；启动脚本只运行产物。静态服务实际提供 `frontend/dist`，代理 `/api/`、Cookie 请求和 `/ws`，HTTPS 页面使用 WSS。

健康检查（本机证书仅通过指定 CA 验证，无全局信任更改）：

```sh
curl --cacert /absolute/path/to/xim-local-data/cert.pem https://127.0.0.1:7443/api/health
```

## Linux 生产示例

先在构建机运行构建脚本，把 `backend/target/release/` 复制为 `/opt/xim/backend`，`frontend/dist/` 为 `/opt/xim/frontend`，`deployment/start-backend.sh` 为 `/opt/xim/deployment/start-backend.sh`。创建无登录权限的 `xim` 系统用户，创建仅该用户可写的 `/var/lib/xim`（0700）。配置文件 `/etc/xim/xim.env`（root:xim 0640）从 `xim.env.example` 填写，固定绝对数据库与日志路径，关闭演示凭证。安装 `xim.service` 后先 `systemd-analyze verify`，再 `systemctl enable --now xim`。服务 `SIGTERM` 会等待 Netty/存储执行器排空并关闭数据库；检查 `journalctl -u xim` 与健康接口。

nginx 使用 `nginx-tls.conf`，替换域名、静态目录、证书/私钥路径，再 `nginx -t` 和 reload。证书从指定域名的可信 CA 获取并按该部署环境配置续期；示例没有自动申请证书或公开站点。HTTP 重定向 HTTPS；TLS 1.2/1.3，WSS Upgrade 头及 Host 保留，75 秒读超时长于应用心跳。Cookie 默认 Secure/HttpOnly/SameSite=Strict。保持前端与 API 同源；后端允许 Origin 精确填写无路径域名。

对外只开放 443（80 仅重定向或证书验证）。后端 HTTP 和 TCP 均绑定 127.0.0.1，不对公网开放 8000；需要控制台时通过受控 SSH 转发。原始 TCP 无 TLS，凭证不应直接传输到公网。系统/防火墙操作由实际服务器管理员执行，本仓库不自动改全局服务或信任。

## 配置及资源边界

数据库环境配置见根 README。JVM 参数优先；不要把真实数据库密码或 Cookie/票据写进命令行日志或 Git。备份也包含密码哈希/会话哈希和聊天内容，应保持 0600、访问隔离及离线加密。

后端共享 TCP/HTTP 最大 512 个连接（`xim.connections.max`，1–100000）；握手前 15 秒无读取关闭，TCP 15 秒/WebSocket 60 秒读取空闲关闭。每连接每秒 30 请求，60 突发（`xim.requests.perSecond`），超限关闭连接，客户端恢复后补拉。HTTP 请求与 WebSocket 帧在进入有界 1024 项串行数据库执行器前分别限速；TCP 上限 1 MiB（含 11 字节头，兼顾大历史响应），WebSocket/HTTP 聚合上限 64 KiB。登录失败/注册限速复用账号模块；代理后源地址为代理地址，生产示例补充 nginx 每来源连接和 HTTP 请求限制。限制为单实例基础保护，重启清除内存计数。

## 备份与恢复演练

在线备份使用 SQLite backup API，包含已提交 WAL 数据；不要直接复制正在写入的 `.db` 或忽略 WAL。

```sh
python3 scripts/sqlite-backup.py /var/lib/xim/xim.db /secure/backups/xim-20261013.db
```

脚本拒绝覆盖已有目标，执行完整性和外键检查。恢复必须先停后端，保存原数据库和 sidecar 到隔离目录，然后将备份复制到**新的数据库路径**，更新 `XIM_DB_SQLITE_PATH` 后启动。不要把旧 WAL/SHM 与恢复文件混用。可再次用本脚本从备份生成新的恢复文件；保持权限归属为服务用户。确认健康、同账号稳定 ID、私聊/群成员/阅读位置/历史、恢复点后的消息确实不存在。原数据库保留，失败时停服务并切回原路径。恢复整个数据库也回滚会话撤销状态，生产恢复后应按需要通过现有账号管理操作撤销旧凭证。

```sh
python3 scripts/sqlite-backup.py /secure/backups/xim-20261013.db /var/lib/xim/restored.db
# 停服务后在 /etc/xim/xim.env 修改 XIM_DB_SQLITE_PATH=/var/lib/xim/restored.db，再启动
```

自动演练用独立临时数据库，不依赖用户数据：

```sh
cd frontend
npm ci
npm run e2e:prepare
npm run test:e2e
```

默认使用本机 Chrome。无 Chrome 的 CI 可运行 `npx playwright install chromium`，再 `XIM_BROWSER_CHANNEL=chromium npm run test:e2e`。测试自行创建临时端口、Java 进程、生产静态 HTTPS 代理、临时证书和 SQLite；结束关闭自有进程，保留临时目录作为证据。Playwright HTML 报告在 `frontend/playwright-report/`，失败 trace/截图在 `frontend/test-results/`。测试对临时自签名证书设置 `ignoreHTTPSErrors`，只影响隔离测试浏览器，不修改系统。

生产 nginx/systemd 和指定公网 CA/域名需在实际服务器验证。本机没有 nginx、Docker 服务、PostgreSQL 测试服务，这些不能宣称运行通过。HTTPS/WSS、静态代理和 SQLite 在线备份/恢复由本地脚本及真实浏览器实际验证。

实现参考：[nginx WebSocket 代理](https://nginx.org/en/docs/http/websocket.html)、[SQLite 在线备份](https://sqlite.org/backup.html)、[Playwright 真实服务器 WebSocket 拦截](https://playwright.dev/docs/api/class-websocketroute)。
