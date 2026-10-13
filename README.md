# XIM

基于 Netty 的即时通信 MVP。项目采用前后端分离结构：`frontend` 使用 React + TypeScript + Vite，`backend` 使用 Java 25 + Maven + Netty；浏览器通过 JSON WebSocket 与后端通信，同时保留 TCP 控制台客户端互通。

## 项目结构

```text
xim/
├── frontend/                  # 独立 React + TypeScript 项目
│   ├── src/
│   │   ├── components/        # 登录、会话列表、聊天面板、群组弹窗
│   │   ├── App.tsx            # React 入口与页面状态订阅
│   │   ├── chat-client.ts     # WebSocket、会话状态、心跳及重连
│   │   ├── chat-client.test.ts # 前端行为测试
│   │   ├── protocol.ts        # 请求、响应及会话的 TypeScript 类型
│   │   └── styles.css         # 中文响应式聊天界面
│   ├── .env.example           # 后端地址配置示例
│   ├── nginx.conf             # 生产静态托管及接口代理示例
│   ├── package.json
│   └── vite.config.ts
├── backend/                   # 独立 Maven 项目
│   ├── src/main/java/cn/xjn/xim/
│   │   ├── server/            # TCP 服务端及共享聊天业务
│   │   ├── web/               # HTTP API、WebSocket、Origin 校验
│   │   ├── client/            # TCP 控制台客户端
│   │   ├── codec/             # TCP 分帧及编解码
│   │   ├── protocol/          # 请求/响应包
│   │   ├── serialize/         # JSON 序列化
│   │   ├── storage/           # JDBC 存储、数据库配置、版本迁移
│   │   └── util/              # 在线连接及群路由
│   ├── src/test/              # 单元与集成测试
│   ├── pom.xml
│   └── run.sh                 # 后端启动脚本
└── pom.xml                    # Maven 聚合入口，仅构建 backend
```

后端不再提供前端 HTML、JS 和 CSS，两个项目独立安装依赖、构建及部署。根目录仍可执行 `mvn clean verify`；前端需单独执行 npm 命令。

## 本地启动

要求 JDK 25、Maven 3.9+，以及 Node.js 20.19+ 或 22.12+（推荐 Node.js 24）。`.java-version` 指定 Java 25；请确保 `JAVA_HOME`、Maven 和 IDE 使用同一 JDK。

在项目根目录的第一个终端启动后端（macOS/Linux）：

```sh
./backend/run.sh -Dxim.web.cookieSecure=false
```

上面的 `cookieSecure=false` 仅用于本机 HTTP 开发；生产使用 HTTPS/WSS，保持默认 Secure Cookie。后端默认监听 TCP `8000`、HTTP/WebSocket `8080`。也可以在 IDE 中导入 `backend/pom.xml` 并运行 `cn.xjn.xim.server.NettyServer`。健康检查为 [http://127.0.0.1:8080/api/health](http://127.0.0.1:8080/api/health)，返回 `{"status":"ok","service":"xim-backend"}`；根路径 `/` 返回 404。

第二个终端启动前端：

```sh
cd frontend
npm ci
npm run dev
```

浏览器打开 [http://localhost:5173](http://localhost:5173)。Vite 将 `/ws` 和 `/api` 代理到 `http://127.0.0.1:8080`，页面请求使用当前站点地址。开发端口固定为 `5173`，被占用时直接报错。

默认使用真实账号：在登录页点击「创建账号」，注册后登录。用两个浏览器窗口分别注册并登录不同账号，在「联系人」搜索账号或显示名并选择对方即可私聊，对方尚未上线时也能留言。只有显式启用开发配置 `XIM_AUTH_DEMO=true ./backend/run.sh` 或 `./backend/run.sh -Dxim.auth.demo=true` 时，才提供 `root/root`、`aaa/aaa`、`bbb/bbb`、`ccc/ccc` 演示凭证；生产环境保持开关关闭。

前端技术配置参考 [React 文档](https://react.dev/) 和 [Vite 代理配置](https://vite.dev/config/server-options#server-proxy)。

## 地址配置与部署

后端 JVM 参数可直接传给启动脚本，例如：

```sh
./backend/run.sh -Dxim.host=127.0.0.1 -Dxim.port=9000 -Dxim.web.port=9090
```

| 配置 | 默认值 | 作用 |
| --- | --- | --- |
| `xim.host` | 服务端 `0.0.0.0`，TCP 客户端 `127.0.0.1` | 监听/连接地址 |
| `xim.port` | `8000` | TCP 端口；服务端 `0` 使用临时端口 |
| `xim.web.port` | `8080` | HTTP/WebSocket 端口；`0` 使用临时端口，`-1` 仅启动 TCP |
| `xim.web.allowedOrigins` | `http://localhost:5173,http://127.0.0.1:5173` | 允许的前端 Origin，逗号分隔、精确匹配 |
| `xim.web.cookieSecure` | `true` | 使用 `__Host-xim_session` Secure Cookie；仅本机 HTTP 开发显式设为 `false`（使用 `xim_session_dev`） |
| `xim.session.ttlSeconds` | `604800` | 凭证绝对有效期，1–2592000 秒；不随心跳续期 |
| `xim.maxRetry` | `5` | TCP 客户端首次连接之后的重试次数 |
| `xim.log.dir` | `target/logs` | 应用日志目录，相对于启动工作目录；启动脚本使用 `backend` 目录 |

更改开发代理地址时，复制 `frontend/.env.example` 为 `frontend/.env.local`，修改 `XIM_BACKEND_URL` 并重启 Vite：

```dotenv
XIM_BACKEND_URL=http://127.0.0.1:9090
```

生产环境执行 `cd frontend && npm ci && npm run build`，将 `frontend/dist/` 单独交给静态服务器托管。推荐由同一前端域名反向代理 `/ws` 和 `/api/` 至 Netty 后端；示例见 `frontend/nginx.conf`，需根据实际部署修改静态目录和后端地址。WebSocket 代理须保留 Upgrade 头，并将 Host 设置为浏览器访问的主机名（含非默认端口）。HTTPS 页面会自动使用 `wss`。

如果前端直接连接独立后端域名，在构建前设置 `VITE_WS_URL=wss://api.example.com/ws`，并给后端配置 `-Dxim.web.allowedOrigins=https://chat.example.com`；Origin 不带路径和结尾斜杠。WebSocket 握手始终允许同源 Origin，以及明确列出的前端 Origin；其他浏览器来源返回 403。无 Origin 的 TCP/JDK 等非浏览器 WebSocket 客户端仍可连接。`XIM_BACKEND_URL` 只影响 Vite 开发/预览代理，不写入浏览器构建产物。

恢复所用的 HTTP 端点由 WebSocket 地址派生为同主机的 `/api/session`。独立后端地址必须与前端保持同站且同方案，例如 `https://chat.example.com` + `wss://api.example.com/ws`；后端对精确 allowlist 中的 Origin 提供 POST/DELETE 的凭证式 CORS 和 OPTIONS 预检，响应带 `Vary: Origin`，不使用通配来源。本机直连可使用 `http://localhost:5173` + `ws://localhost:8080/ws`，并显式关闭开发 Cookie 的 Secure 属性。`localhost` 与 `127.0.0.1` 不属于同站，不可混用直连地址。Cookie 为 host-only、SameSite=Strict，浏览器的跨站 Cookie 限制仍生效，服务也拒绝带 `Sec-Fetch-Site: cross-site` 的会话交换；不同站点的前后端必须改用同源 `/ws` 和 `/api/` 反向代理。

本地查看构建产物可使用 `npm run preview`（同样启用接口代理）。生产部署使用静态服务器。

## 数据库配置与恢复

默认使用 SQLite，无需额外数据库服务。`./backend/run.sh` 首次启动自动创建 `backend/data/xim.db` 及父目录；从 IDE 或其他工作目录启动时，默认路径为启动目录下的 `data/xim.db`。可通过绝对路径固定数据库位置：

```sh
./backend/run.sh -Dxim.db.sqlite.path=/absolute/path/xim.db
```

JVM 参数优先于同名环境变量；启动脚本继承环境变量，不自动读取 `.env` 文件。

| JVM 参数 | 环境变量 | 默认值 |
| --- | --- | --- |
| `xim.db.type` | `XIM_DB_TYPE` | `sqlite`；可选 `pgsql`（也接受 `postgresql`） |
| `xim.db.sqlite.path` | `XIM_DB_SQLITE_PATH` | `data/xim.db` |
| `xim.db.url` | `XIM_DB_URL` | `jdbc:postgresql://localhost:5432/xim` |
| `xim.db.user` | `XIM_DB_USER` | 空，PostgreSQL 用户名 |
| `xim.db.password` | `XIM_DB_PASSWORD` | 空，PostgreSQL 密码 |

PostgreSQL 需预先创建数据库和有建表权限的账号，由环境或密钥管理提供 `XIM_DB_PASSWORD`，再启动：

```sh
XIM_DB_TYPE=pgsql \
XIM_DB_URL='jdbc:postgresql://127.0.0.1:5432/xim' \
XIM_DB_USER=xim ./backend/run.sh
```

不要把真实密码放进代码、连接 URL、命令行 JVM 参数或版本库。连接 URL 支持 pgJDBC 参数（例如部署时的 `sslmode=verify-full`），详见 [pgJDBC 连接配置](https://jdbc.postgresql.org/documentation/use/)；SQLite 使用 [Xerial JDBC 驱动](https://github.com/xerial/sqlite-jdbc)。日志和客户端错误不输出数据库连接参数。

启动时按顺序执行 `backend/src/main/resources/db/migration/` 下的 V1、V2、V3、V4、V5、V6 迁移，在 `xim_schema_version` 记录版本；重复启动不重建已有数据，遇到更新版本的数据库会拒绝启动。用户、群组、成员和消息分别保存在 `xim_users`、`xim_groups`、`xim_members`、`xim_messages`，成员及消息有外键约束。群创建和消息写入使用事务，数据库初始化失败时服务不监听端口；写入失败不会返回成功回执。SQLite 启用外键、WAL 和 5 秒繁忙等待。V2 为消息增加可空的 `client_message_id` 及 `(sender_id, client_message_id)` 唯一索引，保留 V1 已有身份、群成员、消息 ID、序号及时间；旧消息的客户端编号为空。V3 为用户增加可空的密码哈希、凭证版本和演示标记；不改写已有用户 ID、群成员和历史。旧账号不会被注册入口认领，未设置密码时不能登录，需由管理员设置密码（见下文）。V4 新增 `xim_conversation_reads`，按用户、会话类型和会话 ID 保存单调递增的阅读 sequence，不改写已有身份、成员、消息及凭证；升级后的已有会话从阅读位置 0 开始统计历史未读。V5 新增 `xim_sessions`，保存随机凭证/一次性票据的 SHA-256 哈希、签发时凭证版本、到期时间及撤销状态；保留原身份、成员、历史及阅读位置。V6 为用户新增可空的 `display_name`，旧用户回退显示原账号名，保留密码哈希、凭证、稳定 ID、历史、群成员和阅读位置。

保存数据库文件即可保留稳定身份；更换数据库或删除文件会创建全新的身份和数据。现有内存会话没有可迁移的历史。切换 SQLite/PostgreSQL 只切换连接，当前没有自动搬迁两种数据库之间数据的功能。备份 SQLite 时先停止服务，再复制数据库文件；数据库及 WAL/SHM/journal 文件已被 `.gitignore` 忽略。

## 账号与密码

登录页提供注册，可选填 1–64 个字符的显示名（支持中文，不能全为空白或包含控制字符，省略时显示账号名）；登录后的侧栏提供「修改密码」。账号为 3–32 位 ASCII 字母、数字或下划线，区分大小写；新密码为 8–128 个 UTF-16 单元，支持 Unicode 和空格，但不能全为空白或含控制字符。重名注册返回固定提示，不覆盖已有账号。注册成功后手动登录；改密需验证当前密码，新旧密码不得相同，成功后立即撤销当前连接的授权并关闭连接，需要使用新密码重新登录。密码更新、演示标记解除、凭证版本递增在同一事务中提交，原身份、会话关系及历史保留。

密码使用 PBKDF2-HMAC-SHA256（600000 次迭代、每个密码独立的 16 字节随机盐、32 字节哈希），仅保存带算法和迭代参数的哈希。登录失败统一提示「账号或密码错误」，不区分不存在、无密码或密码错误；错误和应用日志不输出密码，TCP JSON 解析异常也不保留可能包含原始凭证的异常内容。浏览器不持久化密码、不自动重试注册/改密请求；响应超时或断线时提示结果未确认，应重新登录检查。注册不自动登录。

TCP 和 WebSocket 共用认证、注册、改密、输入校验及限速处理。5 分钟固定窗口内，同一账号累计 5 次认证失败或同一来源 IP 累计 20 次失败后暂停尝试；成功登录清除该账号的失败计数。注册按来源 IP 限制为每 5 分钟 10 次请求。限速计数在单实例内存中保存，最多 10000 个计数项，重启会清除；使用连接来源 IP，不信任客户端提供的转发头。反向代理后多个用户可能共享代理 IP，部署时应结合代理入口限速。

| JVM 参数 | 环境变量 | 默认值 |
| --- | --- | --- |
| `xim.auth.demo` | `XIM_AUTH_DEMO` | `false`；仅开发时显式设为 `true` |

开发模式仅在演示账号缺少密码时写入演示哈希，重启不重置用户已修改的密码。关闭开发开关后，带演示标记的账号即使曾在开发环境落盘，也不能用演示密码登录；用户改密或管理员设密后解除演示标记，可在生产环境继续使用原有身份。生产默认既不创建演示账号，也不开放演示凭证。

V1/V2 升级后的旧身份没有密码。管理员应先停止后端，再通过交互终端设置密码；此工具也可创建账号，使用与服务器相同的数据库环境变量：

```sh
./backend/account-admin.sh root
# 如需指定现有数据库：
XIM_DB_SQLITE_PATH=/absolute/path/xim.db ./backend/account-admin.sh root
```

工具两次隐藏输入新密码，不接受密码命令行参数或非交互输入；保留已有账号 ID、群成员及消息，并递增凭证版本。不要在服务运行时使用工具重设密码。已有演示身份不能通过注册入口覆盖。

## 登录凭证与连接恢复

浏览器仍经 WebSocket 输入密码登录，密码只用于当前请求，既不进入状态快照，也不写入 localStorage/sessionStorage 或重试队列。登录成功返回 30 秒、一次性票据；页面以 JSON POST 到 `/api/session` 换取 32 字节随机、host-only、HttpOnly、SameSite=Strict Cookie，生产默认附带 Secure，凭证本体不返回给 JavaScript。数据库只存哈希，票据交换不在 URL 中携带秘密，响应禁止缓存；重复、过期、撤销的票据均拒绝。生产流量必须经 HTTPS/WSS，开发非 Secure 模式只用于本机测试。

有效凭证默认 7 天绝对过期；刷新或短暂断线后 WebSocket 用 Cookie 恢复同一账号与群路由。恢复和每次已登录 Web 请求都会校验数据库到期/撤销状态与 `credential_version`，有效连接在绝对到期时主动撤权断开。退出登录撤销当前凭证并清除 Cookie；「撤销登录凭证」撤销账号所有凭证。改密/管理员设密递增凭证版本，旧 Cookie 即使跨服务重启也不能恢复；用户身份、群成员与消息历史保留。过期、替换和撤销会显示明确原因，并停止该窗口自动恢复，需主动登录。

单账号只保留一条连接。主动密码登录可以替换旧连接并撤销旧凭证；自动恢复不会抢占另一活跃窗口。页面保存非认证用途的上一连接编号，断线/刷新可以转移自己那条连接一次；每次成功恢复更新编号，克隆页签持有的旧编号不能重复抢占。Cookie 单独不能授权抢占已有活跃窗口。被替换窗口收到原因后停止恢复，刷新也保持停止状态，避免互踢循环。不同账号共享同一浏览器 Cookie 时以最后一次主动登录为准，需要同时使用不同账号时使用独立浏览器配置或隐私窗口。

每个页签的 sessionStorage 缓存账号标识、当前会话、草稿、最多 200 条近期消息及未确认消息、不可变发送请求和已完成同步游标，不缓存密码/票据/Cookie。相同账号恢复时保留缓存；注销、失效和切换账号清除缓存；存储不可用时仍可聊天，但刷新后无法保留页签草稿/重试队列。离线注销立即阻止本机恢复并保存不含秘密的待注销标记，连接恢复后重试服务端 DELETE；服务不可达期间提示服务端注销尚未确认。

连接每 5 秒发送心跳，连续 15 秒没有任何服务端响应时主动放弃旧 socket；握手 10 秒、恢复/会话快照/每页补拉 8 秒超时也触发重连，按 1、2、4、8、10 秒上限退避。旧 socket 的事件不再修改状态，页面显示正在恢复登录或补拉消息。恢复先取完整会话摘要/未读快照，再按会话的已完成游标使用 `history after` 每页 50 条补拉直到 `hasMore=false`；实时消息和迟到回执只合并显示，不推进补拉游标。分页中断后从上一完整位置重拉并去重，恢复快照也删除已不属于本账号的群会话。补拉完成后，历史已确认的发送不再重发，其余结果未知的发送沿用原请求编号/正文/目标重试一次；明确失败的请求保留手动重试入口。

## 离线联系人与目录

侧栏「联系人」默认浏览真实账号目录，支持账号或显示名子串搜索，空搜索浏览全部；默认每页 20 位，点击「加载更多联系人」继续。搜索等待 250 毫秒以减少重复请求，更换搜索即清空旧结果，旧页和旧错误响应不会覆盖当前查询。目录自动过滤自己，只列出有密码且在当前生产/开发配置中可使用的账号，不列出尚未由管理员设密的旧身份或已禁用的演示账号。「在线」计数和状态仍由单实例实时连接决定，每 5 秒刷新；对方离线并不阻止选中或发送。

选择联系人按稳定用户 ID 创建或打开唯一私聊，与已有会话摘要、活动时间和未读合并；选择本身不会清未读。首次联系离线用户时可以直接发送，对方登录后从会话和历史恢复消息。联系人列表有独立的加载、空结果、失败及重试状态，后端查找/发送不存在的用户返回明确错误；连接中断时停止查询，恢复后可以重试。目录不是好友审批或复杂资料系统，目前显示名仅在注册时填写，账号仍用于认证。

创建群聊复用同一目录，可以选择离线成员；跨页加载、更换搜索和查询失败均保留已选成员，最多选择 50 位其他用户，已选名字可单独移除。创建时由服务端再次校验成员 ID，并使用原有持久化成员/离线消息能力，创建失败通过现有错误提示反馈。

目录通过已认证且凭证有效的 WebSocket `contacts` API 查询，客户端传入其他 `userId` 不会改变查询身份。只返回公开字段 `userId`、`username`、`displayName`、`online`，不返回密码、密码哈希、凭证版本、票据、Cookie 或会话记录。按稳定 ID 升序使用 `after` 游标分页，页大小为 1–50；`query` 最多 64 个字符，ASCII 大小写不敏感，`%`、`_`、`!` 按字面搜索，SQL 使用绑定参数。不是目录快照；分页期间新注册且 ID 位于已读游标之前的用户可通过「刷新联系人」发现。SQL 契约兼顾 SQLite/PostgreSQL；当前 PostgreSQL 实测仍未配置。

## 聊天功能

- 私聊、创建/加入/退出群聊、群成员查看；群号显示在顶部，可分享给其他用户。
- 会话搜索、未读计数、消息时间、多行/中文/Emoji、独立会话草稿和私聊/群聊发送状态。
- Enter 发送、Shift + Enter 换行，并避免中文输入法确认文字时误发送。
- 每 5 秒发送心跳并刷新在线列表和会话摘要；60 秒没有入站数据时后端关闭 WebSocket。断线后指数退避重连，凭证有效时自动恢复及补拉，不保存密码。
- 私聊和群聊立即显示本地消息行。请求超时、断线或发送失败后，在原消息旁点击「重试」，复用同一 `requestId`、内容及目标，不创建新消息行，也不改动输入框草稿。不要把原文重新粘贴后点击「发送」当作重试，这会创建新编号和新逻辑消息。
- 服务端按发送者和客户端编号持久化去重：同一编号、内容和目标返回原消息 ID/时间，不再次插入或广播；编号复用于不同内容、收件人或群组会返回 `error`。数据库提交成功后才发送 `sent`；「已发送」表示服务端已持久化，不表示对方已读。单实例 MVP 的在线推送仍为尽力路由，提交后、广播前进程崩溃时，收件人可通过历史恢复，重复请求不会补播。
- 超时后保留编号映射，迟到 `sent`、自己的群广播或历史记录都能纠正状态；群广播、回执及历史按服务端 ID 和发送者范围内的客户端编号合并，不重复展示。成功状态不会被旧错误或超时降级。同一账号断线/刷新后保留未确认消息，先补拉确认，再自动重试结果未知的请求；主动退出、凭证失效和切换账号清除重试队列。
- Web 入口检查登录、群成员权限和请求格式；消息最多 4000 个 UTF-16 单元，WebSocket 帧/聚合消息最多 64 KiB。React 以文本展示消息内容。
- 刷新、重新登录和服务重启后，登录同一账号即可恢复私聊会话、群组及历史；打开会话加载最近 50 条，点击「加载更早消息」继续分页。原有群组自动恢复，无需重新加入。消息使用服务端 ID 和时间，合并历史与实时消息时按 ID 去重。
- 未读和阅读位置保存在数据库，登录即显示最后消息摘要、活动时间和完整未读数，按最近消息 sequence 排序；自己的消息不会计入未读，未读统计不受最近 50 条历史页大小限制。草稿和当前选中会话可通过页签缓存恢复，不保存密码；Cookie 有效时自动登录。在线状态仅由实时连接决定；断线/退出登录保留群成员关系，只有「退出群聊」删除成员关系。退出群聊后不能读取该群历史；按群号重新加入后可查看完整群历史，但从重新加入时的最新 sequence 开始计算新未读；重复加入不会重置阅读位置。

在 IDE 运行 `cn.xjn.xim.client.NettyClient` 可使用 TCP 控制台客户端，默认连接 `127.0.0.1:8000`。登录后支持 `sendToUser`、`logout`、`createGroup`、`joinGroup`、`listGroupMember`、`quitGroup`、`sendToGroup`、`conversations`、`history`、`exit`；参数以空白分隔。`conversations` 查询已保存的会话；`history user <用户ID> latest` 或 `history group <群号> latest` 查询最近 50 条，使用响应的 `nextBefore` 替换 `latest` 可继续向前查询。TCP 用户也会显示在 Web 在线列表中，同一账号使用稳定用户 ID；新登录会断开该账号的旧连接。

## 阅读与恢复同步

选择会话只打开面板，不立即清未读。页面必须可见、聊天面板处于打开状态、最近历史已加载且不在加载中，并且最新消息已显示在滚动区域底部，才提交阅读位置；停留会话/在线列表、手机返回列表、隐藏浏览器页签、面板不可见或翻看较早消息时不提交。阅读推进至已显示的最大服务端 sequence，表示该会话在此位置之前都已阅读；因此打开最新页并阅读到底部可以确认超过 50 条的历史未读范围，无需逐页清理。未读在服务端提交后由 `read` 回执更新；失败或超时保留未读，随后会话同步会恢复数据库状态。此位置仅属于当前用户，不发送对方已读回执。

私聊的阅读 sequence 必须属于双方的私聊，群聊的阅读和历史查询要求当前用户仍是群成员；拒绝未来或其他会话的位置。重复及较旧的位置不会倒退阅读进度。退出群聊后保留消息和阅读记录，但不列出该群，也不能更新或查询该群历史。首次按群号加入已有群或重新加入时，以当时群内最新位置作为未读起点；完整历史仍可分页访问。创建群时被邀请的成员从群建立时开始计未读；仅登录、断线或服务重启不改变成员关系。

`conversations` 返回完整快照及 `syncCursor`（该快照时全局已提交消息的最大 sequence），每个会话包含 `lastMessage`、`lastSequence`、`lastTime`、`readSequence` 和 `unread`。前端合并迟到快照时使用快照未读基数，加上游标之后已收到且未阅读的消息增量；保留较新的摘要和阅读位置，并重新同步修正遗漏推送。重复推送、历史合并和自己的消息不会重复增加未读。

后续登录/断线恢复可先获取完整会话快照，再用 `history` 的 `after` 从该会话已保留的位置向后补拉；`after` 与 `before` 互斥，`after="0"` 从最早一条开始。向后页面按 sequence 升序返回，`hasMore=true` 时使用 `nextAfter` 继续，返回的 `syncCursor` 是该页查询时的全局消息上界。游标只是消息位置，不能反映没有新消息时发生的阅读或成员关系变更；恢复时仍需获取完整会话快照，不应因游标相同跳过快照。登录恢复客户端已复用这些接口，独立维护每个会话的已完成补拉游标。

## WebSocket 接口

WebSocket 路径为 `/ws`。请求为 JSON 文本对象，以 `type` 指定操作；消息的字符串 `requestId` 同时是逻辑消息编号（非空，最多 80 个 UTF-16 单元），重试必须沿用原编号、原文及目标。`sent`/`error` 回传编号。兼容未提供编号的旧 Web/TCP 调用，但此类请求每次独立写入，不享受幂等重试。

| type | 请求字段 | 响应 |
| --- | --- | --- |
| `register` | `username`, `password`, 可选 `displayName`、`requestId` | `register`: `success`, `reason`, `requestId`；不自动登录 |
| `changePassword` | `currentPassword`, `newPassword`, 可选 `requestId`；需登录 | `changePassword`: `success`, `reason`, `requestId`；成功后关闭连接 |
| `login` | `username`, `password` | `login`: `success`, `userId`, `username`, `reason`；成功还返回 `sessionTicket`, `connectionId`, `expiresAt` |
| `resume` | 可选上一连接 `connectionId`，凭证由 Cookie 自动传输 | `resume`: `success`, `userId`, `username`, `connectionId`, `expiresAt`；活跃窗口冲突返回 `sessionInvalid` |
| `revokeSessions` | 无 | `sessionInvalid`: `code=revoked`, `reason`，随后关闭连接 |
| `logout` | 无 | `logout`: `success` |
| `users` | 无 | `users`: `users`（实时在线用户 ID 与账号名） |
| `contacts` | `requestId` 必填；可选 `query`（默认空）、`after`、`limit`（默认 20，1–50） | `contacts`: `requestId`, `users`（`userId`, `username`, `displayName`, `online`）, `hasMore`, 可选 `nextAfter` |
| `message` | `toUserId`, `message`, `requestId` | 在线对方收到 `message`（`fromUserId`, `fromUsername`, `message`, `id`, `sequence`, `time`, `requestId`）；发送方收到提交成功的 `sent`（`requestId`, `id`, `sequence`, `time`） |
| `createGroup` | `userIds` 数组（可为空） | 群成员收到 `createGroup`: `success`, `groupId`, `usernameList` |
| `joinGroup` | `groupId` | `joinGroup`: `success`, `groupId`, `reason` |
| `members` | `groupId` | `members`: `groupId`, `sessions` |
| `quitGroup` | `groupId` | `quitGroup`: `success`, `groupId` |
| `groupMessage` | `groupId`, `message`, `requestId` | 在线成员收到 `groupMessage`（`fromGroupId`, `fromUser`, `fromUserId`, `message`, `id`, `sequence`, `time`, `requestId`）；发送方另收到 `sent` |
| `conversations` | 无 | `conversations`: `conversations`（`kind`: `user`/`group`, `id`, `name`, `lastMessage`, `lastSequence`, `lastTime`, `readSequence`, `unread`）, `syncCursor` |
| `read` | `kind`, `id`, `sequence`（正数字符串）, `requestId` | `read`: `requestId`, `conversation`（提交后完整摘要）, `syncCursor` |
| `history` | `kind`, `id`, 可选 `before` 或 `after`（字符串游标，互斥）, `limit`（默认 50，1–100）, `requestId` | `history`: `kind`, `id`, `requestId`, `messages`, `nextBefore`, `nextAfter`, `hasMore`, `syncCursor` |
| `heartbeat` | 无 | `heartbeat` |

非法请求返回 `error`（`reason`、可选 `requestId`）。登录前允许注册、登录、恢复和心跳。注册及改密的业务失败使用对应操作响应的 `success=false` 和固定 `reason`，结构错误使用 `error`。类型定义见 `frontend/src/protocol.ts`。

历史消息字段为 `id`, `sequence`, `fromUserId`, `fromUsername`, `message`, `time`（Unix 毫秒），以及可选 `clientMessageId`（原发送编号，需结合发送者识别）；按数据库序号升序返回。空页的 `nextBefore` 可省略，`hasMore=false` 表示结束。私聊查询只包含当前用户与指定用户之间的消息，群聊查询和成员查看要求当前用户是群成员。

TCP 包头格式保持不变，原有命令 1–18 保留；新增命令 19/20 为历史请求/响应，21/22 为会话请求/响应，23 为错误响应，24 为发送回执（仅请求带 `requestId` 时返回）；25/26 为注册请求/响应，27/28 为改密请求/响应；29/30 为阅读请求/响应。账号和阅读字段与 WebSocket 相同，TCP 也回传 `requestId`。历史请求字段与 WebSocket 一致。TCP 的私聊/群聊请求也可携带 `requestId`，享受相同持久化去重和成功回执；旧控制台未提供编号的发送行为保持兼容。TCP 分帧：11 字节包头（4 字节魔数 `0x12345678`、1 字节版本、1 字节序列化算法、1 字节命令、4 字节负载长度），后接 UTF-8 JSON。

## 构建与测试

后端可从根目录构建，或进入 `backend` 独立运行：

```sh
mvn test
mvn clean verify
mvn clean verify -DskipITs
mvn verify -Dit.test=WebChatIT
```

测试结果位于 `backend/target/surefire-reports/` 和 `backend/target/failsafe-reports/`，独立 JVM 日志位于 `backend/target/integration-logs/`，合并覆盖率报告位于 `backend/target/site/jacoco/index.html`。

前端验证：

```sh
cd frontend
npm run typecheck
npm test
npm run build
```

持久化测试包含真实 SQLite 文件写入、重新打开后的恢复、版本迁移、外键失败后的事务回滚、Unicode 消息、历史分页及权限。`PersistenceIT` 启动两个独立服务 JVM，共用测试数据库文件，验证重启后的身份、离线私聊/群聊、会话、成员关系和 TCP/WebSocket 路由恢复。集成测试数据库放在 `backend/target/`，仓储及故障注入测试使用临时数据库，不会写入默认业务数据库。`MessageRetryStoreTest` 验证 V1 逐级升级、两个 JDBC 连接的并发重复请求、重开数据库后的原 ID/时间、编号复用拒绝和旧 TCP 兼容；`MessageRetryHandlersTest` 验证私聊/群聊不重复广播，以及真实 SQL 写入失败不回执成功。`PersistenceIT` 在两个独立 JVM 之间重复 Web/TCP 私聊及群聊请求，检查原回执和无重复路由。`ConversationReadStoreTest` 验证 V3→V4 升级、65 条私聊和群聊的完整未读、自己的消息排除、重复与较旧阅读、权限、群加入/退出边界和向后同步分页；相同 SQL 契约接入可选 PostgreSQL 实测。`ConversationReadHandlersTest` 验证 Web/TCP 阅读请求、回执关联、权限和编解码。`PersistenceIT` 还在真实服务 JVM 中发送 65 条离线私聊及群聊，验证新连接登录、阅读前后重启、摘要顺序、历史分页不改变未读、重复发送、自己的回复和向后补拉。前端测试使用可控 WebSocket 和时钟，验证丢失/迟到回执、超时重试、重连后同账号重试、群广播/回执顺序和历史合并、草稿及账号隔离，以及隐藏页面/列表/翻页不提交阅读、阅读超时、摘要恢复、旧快照与实时消息/阅读回执交错时的未读合并。

PostgreSQL 实测为可选集成测试，连接参数使用 `XIM_TEST_PG_URL`、`XIM_TEST_PG_USER`、`XIM_TEST_PG_PASSWORD`。先由环境或密钥管理提供测试密码，再运行：

```sh
XIM_TEST_PG_URL='jdbc:postgresql://127.0.0.1:5432/xim_test' \
XIM_TEST_PG_USER=xim_test mvn verify -Dit.test=PostgreSQLStoreIT
```

测试账号需要创建/删除 schema 的权限。测试只创建随机命名的独立 schema，结束时删除该 schema；重用与 SQLite 相同的 SQL 契约测试，验证迁移、写入、恢复、事务、分页、权限和账号注册/登录/改密/恢复。没有配置 `XIM_TEST_PG_URL` 时，此项明确跳过，不代表 PostgreSQL 已验证。

2026-10-13 可靠重试验证：根目录 `mvn verify` 通过 104 项单元测试和 23 项集成测试，PostgreSQL 实测项跳过 1 项；前端 TypeScript 检查、23 项行为测试及 Vite 生产构建通过。已实测 SQLite V1→V2 保留数据升级、并发去重、SQL 写入失败不返回成功、独立 JVM 重启后的 Web/TCP 私聊和群聊重试、原 ID/时间恢复及无重复路由。未设置 `XIM_TEST_PG_URL`，因此 PostgreSQL 实际运行兼容性尚未验证；相同重试 SQL 契约已接入可选 PostgreSQL 集成测试。

2026-10-13 账号管理验证：122 项后端单元测试通过；完整网络回归与新增默认生产配置独立进程验收通过，合计 26 项集成测试通过、1 项 PostgreSQL 实测跳过。前端 28 项行为测试、TypeScript 检查和生产构建通过。账号专项覆盖随机盐哈希及异常脱敏、注册/重名/非法输入、双协议登录与改密、旧密码失效、限速共享与窗口过期、V1/V2→V3 升级后的 ID/成员/历史保留、开发/生产演示凭证差异、生产进程重启后的新密码及历史恢复；独立进程日志检查未发现测试密码或密码哈希。PostgreSQL 的账号 SQL 契约已接入可选实测，但本机未设置测试服务，仍未验证。

2026-10-13 会话摘要与未读恢复验证：根目录 `mvn verify` 通过 126 项单元测试、27 项集成测试，PostgreSQL 实测跳过 1 项；前端 35 项行为测试、TypeScript 检查和 Vite 生产构建通过。已实测 V4 升级保留旧身份及历史、65 条离线私聊/群聊的完整未读、自己的消息排除、历史分页不清未读、重复/较旧阅读更新、群成员生命周期与权限、TCP/WebSocket 阅读协议、新连接模拟刷新及独立 JVM 重启前后恢复、摘要顺序及向后补拉。前端可控 WebSocket 验证隐藏页签/面板、停留列表和浏览旧页不提交阅读；新增推送 sequence 11 与旧快照游标 10/未读 3（含重复快照）合并得到未读 4，阅读回执与新推送交错保持新的摘要和单调阅读位置。未设置 `XIM_TEST_PG_URL`，PostgreSQL 实际运行兼容性仍未验证，阅读 SQL 契约已接入可选测试。

2026-10-13 离线联系人验证：完整 `mvn verify` 通过 133 项单元测试、32 项集成测试，另 1 项 PostgreSQL 实测未配置跳过；前端 59 项测试、TypeScript 检查和生产构建通过。`ContactStoreTest` 覆盖账号/中文显示名与字面通配符搜索、自身和不可用身份过滤、游标分页、非法参数、重开恢复及 V5→V6 保留身份/密码/凭证/历史/群成员/阅读位置。`ContactsIT` 通过真实 WebSocket 和独立生产配置 JVM 验证鉴权、伪造查询身份无效、精确隐私字段、分页、在线状态、空结果、格式及页大小限制、不存在用户、自身私聊拒绝、首次离线私聊及群成员选择、上线恢复未读/历史与稳定 ID、查询存储失败的脱敏错误。目录 SQL 契约接入可选 PostgreSQL，未配置的跳过不代表通过。真实浏览器经本机代理验证 22 位离线联系人的两页浏览、首聊发送、跨页成员选择后搜索仍保留成员并创建群、收件人登录后恢复私聊/群聊未读与私聊历史，及 390×844 移动端搜索空结果和布局；还实测后端不可达时显示查询失败/重试，以及后端重启后自动恢复原账号和当前联系人搜索。浏览器使用独立临时数据库，临时服务已关闭，未触及真实业务数据库或公开部署。

2026-10-13 登录与断线恢复验证：完整 `mvn verify` 通过 130 项单元测试、31 项集成测试，另 1 项 PostgreSQL 实测因未配置跳过；前端 52 项行为测试、TypeScript 检查和生产构建通过。`SessionStoreTest` 覆盖 V4→V5 保留数据、凭证重开恢复、哈希存储、一次性/过期票据、注销、主动登录替换、改密和管理员改密撤销；同一 SQL 契约已接入可选 PostgreSQL。`WebSessionLifecycleTest` 验证重复绑定、撤权和关闭连接会取消绝对过期任务。`SessionRecoveryIT` 使用真实 HTTP/WebSocket、独立服务 JVM 和临时 SQLite 文件，覆盖刷新恢复、两次服务启动之间 65 条离线私聊/群聊的完整 after 分页、丢失回执重发返回原 ID 且无重复路由、群路由恢复、多窗口/克隆旧连接编号不互踢、主动替换、Cookie DELETE、2 秒绝对过期关闭活跃连接、改密后的旧 Cookie 跨重启失效、撤销、Cookie 安全属性、错误来源和表单拒绝、精确来源的凭证式 CORS/预检与跨站拒绝。前端可控时钟测试验证 65 条分批补拉、实时/迟到回执交错不跳游标、中途断线重拉去重、刷新后的独立草稿与发送编号、先补拉后重试、明确失败不自动重发、旧 socket 事件隔离、15 秒无响应发现、握手退避、快照/页面超时、失效后刷新不自动恢复及注销/新登录的 Cookie 请求排序。真实浏览器经本机同源代理实测登录、刷新后同账号/当前会话/草稿恢复、第二窗口拒绝自动抢占、主动登录替换、旧窗口刷新保持停止恢复、注销后刷新仍退出。浏览器验收使用独立 /tmp 数据库和显式非 Secure 开发 Cookie，临时服务已停止；未公开部署，也未验证指定生产 HTTPS 环境。PostgreSQL 服务仍未配置，实际运行兼容性未验证。

后端集成测试使用回环地址及临时端口，关闭连接、事件循环和子进程。完整验证约需一分钟，主要等待真实心跳、重试和独立 JVM 重启。

## 当前边界

项目已提供注册、密码认证及改密，提供可撤销会话凭证和自动登录，尚无内置 TLS、找回密码或账号删除。真实生产流量需由指定部署环境提供 HTTPS/WSS 等传输保护；本项目不自动公开部署。TCP 和 WebSocket 已统一验证收件人、消息长度及群成员权限；TCP 帧大小上限已限制为 1 MiB（含头）。成员为空的群组及历史保留在数据库中，不自动回收；群号加入策略沿用原有演示行为。存储使用单个 JDBC 连接和有界串行业务执行器，适用于单实例 MVP；在线路由不支持跨服务实例共享。Fastjson 1.x 和原有日志依赖保留。JDK 25 下已有的 Lombok/Netty Unsafe 及 equals/hashCode 警告不影响构建与测试。

## 可复现部署与浏览器验收

单实例发布包、静态反向代理、HTTPS/WSS、Linux nginx/systemd 示例、持久目录、配置和 SQLite 在线备份/恢复步骤见 [部署说明](deployment/README.md)。`./scripts/build-release.sh` 实际执行完整构建；`./deployment/local.sh /absolute/data` 使用回环地址和指定持久目录启动发布包及静态 HTTPS 服务，不修改全局服务或证书信任。

```sh
cd frontend
npm run e2e:prepare
npm run test:e2e
# 根目录，先完成 build-release.sh
node scripts/release-smoke.mjs
```

新增生产保护：TCP/HTTP 共享连接上限 512、握手前 15 秒读取超时、每连接 30 请求/秒和 60 突发、TCP 1 MiB/WS 64 KiB 帧上限。批量网络测试按 75ms/两条请求节奏运行，未放宽生产限流；超限关闭连接，已提交消息通过重试和补拉恢复。

2026-10-13 部署阶段验证：136 项后端单元测试、32 项集成测试通过，1 项 PostgreSQL 未配置跳过；59 项前端测试及生产构建通过。5 组可重复 Chrome E2E 全通过，覆盖真实 HTTPS/WSS/Secure Cookie、账号/改密/撤销/绝对过期/多窗口、离线私聊/群邀请、联系人跨页与移动端、历史前向分页、未读摘要、刷新/服务重启、断网 65 条消息的完整 after 分页补拉、重复重试与迟到回执、在线备份及新路径恢复。发布包构建和 `release-smoke.mjs` 也实际通过 jar/lib、start-backend/local 脚本、指定 CA 的 curl、浏览器群消息、稳定 ID 和重启持久化。nginx/systemd/公网证书及 PostgreSQL 未在本机实测。
