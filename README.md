# xim

基于 Netty 的即时通信 MVP，支持 Web 聊天与控制台客户端互通，使用 Maven 构建，要求 JDK 25、Maven 3.9+。

## 项目结构

- `client`：连接与重试、控制台命令、响应分发、每 5 秒发送一次心跳。
- `server`：登录鉴权、私聊、群组创建/加入/退出、成员列表、群消息转发。
- `web` / `resources/web`：HTTP 静态页面、JSON WebSocket 接口和中文响应式聊天页面，复用服务端业务处理器。
- `codec` / `protocol` / `serialize`：TCP 分帧、18 种请求/响应包及 JSON 序列化。
- `util.SessionManager`：内存中的用户连接、会话和群组映射。
- `handler.IMIdleStateHandler`：15 秒无入站数据时关闭连接。

协议使用 11 字节包头：4 字节魔数 `0x12345678`、1 字节版本、1 字节序列化算法、1 字节命令、4 字节负载长度，后接 UTF-8 JSON 数据。

服务端 pipeline 依次执行空闲检测、分帧、编解码、登录、心跳、鉴权、业务分发。客户端执行空闲检测、分帧、编解码、心跳定时器、响应分发。

## 构建与运行

确保 `JAVA_HOME` 和 Maven 使用 JDK 25，IDE 的项目 SDK 也设置为 25：

```sh
java -version
mvn -version
mvn clean verify
```

`.java-version` 指定 Java 25。Maven 使用 `--release 25`；Lombok 通过显式注解处理器路径运行，仅在编译时使用。JUnit 仅用于测试。

在 IDE 中分别运行 `cn.xjn.xim.server.NettyServer` 和 `cn.xjn.xim.client.NettyClient`。服务端端口为 `8000`，客户端连接 `127.0.0.1:8000`。

可通过 IDE 的 VM options 或 Java JVM 参数覆盖地址和端口：`-Dxim.host=127.0.0.1 -Dxim.port=9000`；客户端还支持 `-Dxim.maxRetry=5`（初次连接之后最多再重试 5 次）。默认服务端监听 `0.0.0.0`，客户端连接 `127.0.0.1`；服务端设置 `-Dxim.port=0` 时使用系统分配的端口，并在启动日志打印实际端口。两端都在 JVM 关闭时释放事件循环，服务端绑定失败时也会释放资源。

演示账号及密码分别为 `root/root`、`aaa/aaa`、`bbb/bbb`、`ccc/ccc`。登录后支持 `sendToUser`、`logout`、`createGroup`、`joinGroup`、`listGroupMember`、`quitGroup`、`sendToGroup` 和 `exit` 命令；消息通过空白分隔输入。

## Web 聊天

启动 `cn.xjn.xim.server.NettyServer` 后，在浏览器打开 **http://127.0.0.1:8080/**。同一个进程同时监听 TCP 8000 和 HTTP/WebSocket 8080，无需 Node.js、npm 或额外前端构建。页面与 WebSocket `/ws` 同源部署。

也可以从终端启动（macOS/Linux）：

```sh
mvn package -DskipTests
mvn dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
java -cp "target/classes:$(cat target/classpath.txt)" cn.xjn.xim.server.NettyServer
```

用两个浏览器窗口分别登录 `root/root` 和 `aaa/aaa`，进入「在线」列表并选择对方即可私聊。控制台登录后的用户也会显示在在线列表中；用户名相同的不同连接通过用户 ID 区分。

- 页面支持会话搜索、未读计数、消息时间、多行/中文/Emoji、会话草稿和发送状态。Enter 发送，Shift + Enter 换行，并避免中文输入法确认文字时误发送。
- 点「＋」选择在线成员创建群聊，创建者自动加入；群号显示在群聊顶部，可分享给其他用户加入。支持群成员查看与退出群聊。
- 在线列表每 5 秒刷新；同周期发送心跳，60 秒无入站数据会关闭 WebSocket。断线后自动重连连接，恢复后需要重新登录；不保存密码。
- 私聊接收者离线时返回失败信息；发送响应超时或断线时显示「发送结果未知」，避免把不确定结果标记为已送达。「已发送」表示服务端已接受并路由消息，不表示对方已读。
- Web 接口校验登录状态、群成员权限和请求格式。消息长度上限 4000 个 UTF-16 单元，WebSocket 帧/聚合消息上限 64 KiB。页面通过文本节点展示消息。
- 当前会话、消息和群号仅存于页面内存，刷新、退出或重新登录后清空；服务端用户与群组也仅存于内存。恢复连接后需要重新加入已有群组，不提供离线消息或历史记录。

配置项：`-Dxim.web.port=9090` 改变 Web 端口，`-Dxim.web.port=0` 分配临时端口并打印实际端口，`-Dxim.web.port=-1` 仅启动 TCP。`xim.host` 同时控制两个监听地址。任一监听绑定失败时释放整个服务端资源。

### WebSocket 接口

请求为 JSON 文本对象，以 `type` 指定操作；消息请求可携带字符串 `requestId`，`sent`/`error` 回传该编号。

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
| `groupMessage` | `groupId`, `message`, `requestId` | 所有群成员收到 `groupMessage`（`fromGroupId`, `fromUser`, `fromUserId`, `message`）；发送方另收到 `sent` |
| `heartbeat` | 无 | `heartbeat` |

非法请求返回 `error`（`reason`、可选 `requestId`）；登录前只允许登录和心跳。WebSocket 握手拒绝不同源的浏览器 Origin。

## 测试

```sh
mvn test                                    # 仅运行单元测试
mvn clean verify                            # 单元测试、打包、集成测试及合并覆盖率报告
mvn clean verify -DskipITs                   # 跳过集成测试
mvn clean verify -Dit.test=MessagingIT       # 单元测试 + 指定的 TCP 集成测试
mvn clean verify -Dit.test=ApplicationLifecycleIT # 单元测试 + 进程与生命周期集成测试
mvn verify -Dit.test=WebChatIT               # 单元测试 + HTTP/WebSocket 及 Web/TCP 互通测试
```

- 测试结果：`target/surefire-reports/`。
- 集成测试结果：`target/failsafe-reports/`，使用 [Maven Failsafe](https://maven.apache.org/surefire/maven-failsafe-plugin/usage.html) 在 `verify` 构建流程运行 `*IT` 类。
- 独立 JVM 的日志：`target/integration-logs/`，每个进程单独一个文件。
- 覆盖率报告：`target/site/jacoco/index.html`，另提供 XML、CSV。
- 使用 Netty `EmbeddedChannel` 验证协议与业务，不启动监听服务；pipeline 初始化测试创建未连接的 NIO channel。
- 心跳测试通过虚拟时间推进，不等待真实心跳周期。控制台登录/登出测试保留现有各 1 秒的等待。
- 每项测试释放 buffer/channel，清理会话和群组；测试日志只写控制台。

覆盖范围包括全部 18 种包的编解码和协议头、Unicode 与嵌套会话序列化、分片/粘包、非法魔数/长度、未知命令/算法、登录成功与失败、空凭据、登出后鉴权、会话替换与断开清理、私聊路由、群组生命周期与广播、客户端响应分发、心跳停止、空闲关闭、pipeline 顺序及控制台命令。

2026-10-12 在 JDK 25.0.2、Maven 3.9.16 下完成 `clean verify`：93 项单元测试 + 14 项集成测试，共 107 项全部通过，失败/错误/跳过均为 0；合并覆盖率为行覆盖率 98.52%、分支覆盖率 95.56%，包含独立 JVM 的客户端/服务端启动代码。JaCoCo 默认过滤 Lombok 生成的方法。

### 集成测试

`MessagingIT` 的 9 项测试使用真实 NIO 服务端、生产服务端 pipeline 和独立的 TCP wire 客户端，覆盖失败后重新登录、Unicode 私聊、群组创建/加入/成员查询/广播/退出、登出后拒绝业务请求、断开后的会话与群成员清理、登录前心跳和鉴权、分片包头、合并帧/未知算法及非法魔数关闭。分片测试等待服务端确认每次读取，再发送下一片，避免操作系统把多次写入合并为完整帧。

`ApplicationLifecycleIT` 的 5 项测试在独立 JVM 中运行生产 `main` 入口，覆盖服务端/客户端启动、控制台登录与 `exit`、连接拒绝后的真实重试成功、重试耗尽后的进程退出、端口占用时服务端退出，以及客户端心跳在超过 15 秒读空闲阈值后仍能维持连接。保留控制台 `exit` 现有的退出码 `1`。

Web 版本将 `ApplicationLifecycleIT` 扩充至 6 项，额外验证生产入口提供 Web 页面，以及 Web 端口被占用时退出并释放 TCP 监听。新增 `WebChatIT` 的 6 项测试使用真实 JDK HTTP/WebSocket 客户端，覆盖静态页面、资源与 HTTP 方法、登录失败后重试、在线用户、Unicode 私聊与发送回执、离线/空白/超长消息错误、退出和重新登录、非法 JSON、群聊创建/加入/广播/成员权限/退出/断开清理、Web/TCP 私聊和群聊互通，以及跨源握手拒绝。

2026-10-12 Web 版本通过 `mvn verify`：93 项单元测试 + 21 项集成测试，共 114 项，失败/错误/跳过均为 0。浏览器联调验证了登录失败后重试、两个账号双向私聊、多行与文本消息、发送状态、群聊创建与广播、成员列表、退出及重新加入，以及服务中断后的自动重连与重新登录。

测试仅使用 `127.0.0.1` 和临时端口；正常服务器由系统分配端口，重试测试先取得一个未占用端口再延迟启动服务端。所有等待都有超时，测试结束关闭 socket、监听端口、事件循环和子进程，并清理注册表；意外 pipeline 异常或进程需要强制终止都会使测试失败。完整集成测试约需 30–45 秒，主要是验证真实心跳和重试间隔。

JaCoCo 为每个子进程单独记录执行数据，`verify` 阶段合并单元测试、集成测试及子进程数据，再生成报告。长期运行、压力、网络丢包/延迟和 TLS 尚未测试。

## 本次升级与修复

- Java 8 → Java 25，Compiler Plugin 3.14.1、Surefire 3.5.4、JaCoCo 0.8.14。
- Lombok 1.18.42，按 [官方 Maven 配置说明](https://projectlombok.org/setup/maven) 显式启用注解处理；Netty 4.1.127.Final；保留 JUnit 4 API 并升级至 4.13.2。
- 分帧器等待完整魔数；独立解码器等待完整数据包；校验包头和长度；未知命令/算法不再输出 `null`。
- 空密码返回登录失败；服务端断开事件继续传播；加入不存在的群组返回失败响应。
- 鉴权处理器持续检查登录状态，拒绝登出后的业务请求并释放拒绝的消息。
- 会话解除不会删除同一用户的新连接映射，并移除其群组成员关系；客户端断开清理会话。
- 共享客户端响应处理器可用于多个连接；断开或移除心跳处理器时取消定时任务。

## 后续边界

项目仍是内存演示程序：账号硬编码、无消息持久化、无 TLS。TCP 路径的群组消息与成员查询尚未完整处理不存在的群组及成员权限（Web 入口已增加校验）；TCP 帧大小上限仍为 `Integer.MAX_VALUE`（WebSocket 上限为 64 KiB）；空群组也未自动回收。Fastjson 1.x 和现有日志依赖尚未迁移，本次验证不等同于生产安全审计。

JDK 25 下 Lombok/Netty 仍可能输出 `sun.misc.Unsafe` 弃用警告；Lombok 也会提示 Packet 子类的 equals/hashCode 不包含父类字段。这些警告不影响本次构建与测试通过。
