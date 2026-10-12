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
│   │   └── util/              # 内存会话及群组管理
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
./backend/run.sh
```

后端默认监听 TCP `8000`、HTTP/WebSocket `8080`。也可以在 IDE 中导入 `backend/pom.xml` 并运行 `cn.xjn.xim.server.NettyServer`。健康检查为 [http://127.0.0.1:8080/api/health](http://127.0.0.1:8080/api/health)，返回 `{"status":"ok","service":"xim-backend"}`；根路径 `/` 返回 404。

第二个终端启动前端：

```sh
cd frontend
npm ci
npm run dev
```

浏览器打开 [http://localhost:5173](http://localhost:5173)。Vite 将 `/ws` 和 `/api` 代理到 `http://127.0.0.1:8080`，页面请求使用当前站点地址。开发端口固定为 `5173`，被占用时直接报错。

演示账号为 `root/root`、`aaa/aaa`、`bbb/bbb`、`ccc/ccc`。用两个浏览器窗口分别登录 `root` 和 `aaa`，在「在线」列表选择对方即可私聊。

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
| `xim.maxRetry` | `5` | TCP 客户端首次连接之后的重试次数 |
| `xim.log.dir` | `target/logs` | 应用日志目录，相对于启动工作目录；启动脚本使用 `backend` 目录 |

更改开发代理地址时，复制 `frontend/.env.example` 为 `frontend/.env.local`，修改 `XIM_BACKEND_URL` 并重启 Vite：

```dotenv
XIM_BACKEND_URL=http://127.0.0.1:9090
```

生产环境执行 `cd frontend && npm ci && npm run build`，将 `frontend/dist/` 单独交给静态服务器托管。推荐由同一前端域名反向代理 `/ws` 和 `/api/` 至 Netty 后端；示例见 `frontend/nginx.conf`，需根据实际部署修改静态目录和后端地址。WebSocket 代理须保留 Upgrade 头，并将 Host 设置为浏览器访问的主机名（含非默认端口）。HTTPS 页面会自动使用 `wss`。

如果前端直接连接独立后端域名，在构建前设置 `VITE_WS_URL=wss://api.example.com/ws`，并给后端配置 `-Dxim.web.allowedOrigins=https://chat.example.com`；Origin 不带路径和结尾斜杠。WebSocket 握手始终允许同源 Origin，以及明确列出的前端 Origin；其他浏览器来源返回 403。无 Origin 的 TCP/JDK 等非浏览器 WebSocket 客户端仍可连接。`XIM_BACKEND_URL` 只影响 Vite 开发/预览代理，不写入浏览器构建产物。

本地查看构建产物可使用 `npm run preview`（同样启用接口代理）。生产部署使用静态服务器。

## 聊天功能

- 私聊、创建/加入/退出群聊、群成员查看；群号显示在顶部，可分享给其他用户。
- 会话搜索、未读计数、消息时间、多行/中文/Emoji、独立会话草稿和私聊发送状态。
- Enter 发送、Shift + Enter 换行，并避免中文输入法确认文字时误发送。
- 每 5 秒发送心跳并刷新在线列表；60 秒没有入站数据时后端关闭 WebSocket。断线后指数退避重连，恢复连接后需要重新登录，不保存密码。
- 请求超时或断线显示「发送结果未知」；失败时恢复草稿且不覆盖新输入。「已发送」表示服务端已接受并路由消息，不表示对方已读。
- Web 入口检查登录、群成员权限和请求格式；消息最多 4000 个 UTF-16 单元，WebSocket 帧/聚合消息最多 64 KiB。React 以文本展示消息内容。
- 页面内存中的消息、草稿、会话在刷新、退出或重新登录后清空；恢复登录后需重新加入已有群组，无离线消息或历史记录。

在 IDE 运行 `cn.xjn.xim.client.NettyClient` 可使用 TCP 控制台客户端，默认连接 `127.0.0.1:8000`。登录后支持 `sendToUser`、`logout`、`createGroup`、`joinGroup`、`listGroupMember`、`quitGroup`、`sendToGroup`、`exit`；参数以空白分隔。TCP 用户也会显示在 Web 在线列表中，同名连接通过用户 ID 区分。

## WebSocket 接口

WebSocket 路径为 `/ws`。请求为 JSON 文本对象，以 `type` 指定操作；消息请求带字符串 `requestId`，`sent`/`error` 回传编号。

| type | 请求字段 | 响应 |
| --- | --- | --- |
| `login` | `username`, `password` | `login`: `success`, `userId`, `username`, `reason` |
| `logout` | 无 | `logout`: `success` |
| `users` | 无 | `users`: `users`（用户 ID 与用户名） |
| `message` | `toUserId`, `message`, `requestId` | 对方收到 `message`（`fromUserId`, `fromUsername`, `message`）；发送方收到 `sent` |
| `createGroup` | `userIds` 数组（可为空） | 群成员收到 `createGroup`: `success`, `groupId`, `usernameList` |
| `joinGroup` | `groupId` | `joinGroup`: `success`, `groupId`, `reason` |
| `members` | `groupId` | `members`: `groupId`, `sessions` |
| `quitGroup` | `groupId` | `quitGroup`: `success`, `groupId` |
| `groupMessage` | `groupId`, `message`, `requestId` | 所有成员收到 `groupMessage`（`fromGroupId`, `fromUser`, `fromUserId`, `message`）；发送方另收到 `sent` |
| `heartbeat` | 无 | `heartbeat` |

非法请求返回 `error`（`reason`、可选 `requestId`）。登录前仅允许登录和心跳。类型定义见 `frontend/src/protocol.ts`。

TCP 协议保持不变：11 字节包头（4 字节魔数 `0x12345678`、1 字节版本、1 字节序列化算法、1 字节命令、4 字节负载长度），后接 UTF-8 JSON。

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

2026-10-13 拆分版本验证：后端 `mvn clean verify` 通过 93 项单元测试和 22 项集成测试；前端 9 项行为测试通过，TypeScript 检查及 Vite 生产构建通过。前端行为测试覆盖登录/超时、心跳、Unicode 私聊与回执、失败与草稿恢复、未读、群组生命周期、断线重连和 React StrictMode 清理。后端集成测试覆盖 TCP/WebSocket 互通、健康检查、静态资源移除、允许的前端 Origin 与非法跨源拒绝。

后端集成测试使用回环地址及临时端口，关闭连接、事件循环和子进程。完整验证约需 40 秒，主要等待真实心跳和重试。

## 当前边界

项目是内存演示程序：账号硬编码、无消息持久化、无 TLS。TCP 群组入口尚未完整校验成员权限及不存在的群组，TCP 帧大小上限仍为 `Integer.MAX_VALUE`；空群组未自动回收。Fastjson 1.x 和原有日志依赖保留。JDK 25 下已有的 Lombok/Netty Unsafe 及 equals/hashCode 警告不影响构建与测试。
