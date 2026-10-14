# XIM Protobuf v2

`xim.proto` 是 TCP 和 WebSocket 唯一的聊天协议源。业务字段属于独立的类型化 message；`Envelope.version=2` 与 oneof 消息类型指定协议，没有把 JSON 放进 bytes/string。当 .proto 扩展时新增字段编号，不复用已有编号；普通未知字段可忽略，未知/缺失业务操作、版本和错误方向拒绝。

## 生成

```sh
mvn -f backend/pom.xml generate-sources
cd frontend
npm ci
npm run proto:generate
```

Maven 固定 protoc、protobuf-java、protobuf-java-util 4.33.2，os-maven-plugin 自动选平台二进制，生成 Java 到 target。前端固定 protobufjs 8.8.1、protobufjs-cli 2.7.1 和 Long 5.3.2，生成静态编解码 JS + .d.ts 到 `src/generated`。build/test/typecheck/dev/test:e2e 先生成；不手改、不提交生成产物。普通 npm ci 不需要全局 protoc；Java 生成由 Maven 提供 protoc。

`ProtocolWire` 用 protobuf-java 生成消息和 JsonFormat 在进程内适配既有业务 POJO，不改变存储/权限/撤销/去重处理；线上只发送生成消息的二进制。TypeScript `protocol-wire` 使用生成编解码，int64 从 Long 导出十进制字符串。序号、阅读位置、before/after/nextBefore/nextAfter/syncCursor 用 BigInt 操作，不能先转成 Number。contacts.after/nextAfter 是用户 ID 字符串，单独保留其不透明游标语义。时间/显示计数仅在验证安全整数后转换；大未读仍保持精确字符串并显示 99+。

## 传输和升级

TCP：魔数 0x12345678，版本 2、序列化标识 2、原 command 字节、4 字节长度，总头 11 字节，后跟 Envelope。Envelope 字段 1–30 与原 TCP command 编号对应，头和 oneof 类型必须一致。总帧上限 1 MiB；未知算法/损坏载荷关闭真实连接。控制台通过共享 PacketCodec/Serializer 自动使用 v2。

WebSocket：`/ws`，二进制帧，Envelope 版本 2，聚合/帧上限 64 KiB。账号、显示名、Cookie 恢复/撤销事件、目录、群组、历史、摘要、阅读、可靠编号和迟到回执全部在 .proto 中。request_id 在私聊/群聊中也是发送者范围内的逻辑客户端编号；StoredMessage.client_message_id 保留此编号。Cookie 本身通过 HttpOnly/Secure HTTP 头传输，票据字段用于一次性兑换；健康和 `/api/session` HTTP API 保持 JSON/原状态码。

v1 JSON 不与 v2 混用。升级前停旧后端、备份数据库，协调替换前后端及控制台产物；旧 JSON TCP 关闭连接，旧 JSON WebSocket 得到二进制升级提示。数据库仍是 V6，原 ID、历史、群成员、阅读位置和凭证不重建。可通过一次性票据重新登录，旧有效 Cookie 也继续恢复原身份。恢复较早备份时关闭旧客户端页签后重新进入，避免更晚页签缓存混入回滚历史。

## 有限帧及完整同步

历史响应按实际编码字节缩页：before 保留最近端，after 保留较早端，返回对应真实游标、hasMore；完整正文不会缩成摘要。单条超过传输预算的旧数据会明确报错。不能以返回条数少于 limit 判断查询结束。

会话 lastMessage 是最多 120 Unicode 码点的预览，保留 ID、sequence/time、发送者和客户端编号。完整目录分批发送：snapshot_id 相同、part 从 0 连续递增、sync_cursor 相同，has_more=false 才结束。客户端先收齐再原子应用完整目录，随后以 50ms 最小页请求间隔补拉每个会话。缺批/错序/游标不一致/超时重新连接，不提交部分目录或推进未完成的缓存游标。

## 验证

`golden-vectors.json` 是跨语言契约，含 Unicode、超过 JS 安全整数的游标和 Long.MAX_VALUE。Java 和 TS 都读取验证；显式更新协议后可运行 `node scripts/generate-golden.mjs` 重生成，普通构建不改写契约。

```sh
mvn -f backend/pom.xml clean verify
cd frontend
npm test
npm run e2e:prepare
npm run test:e2e
```

测试包括全操作/可选字段往返、未知字段、坏 UTF-8、非法方向/版本/空或损坏/超大包、TCP/WebSocket 互通、真实连接上限与慢握手、长中文历史双向分页、完整分批快照、精确字符串缓存及失效旧缓存、Blob 顺序和旧连接隔离。真实浏览器另测 >2^53 SQLite 序号和 150 个长消息会话在生产限流下的重启恢复。

参考：[Protobuf 字段存在性与 int64](https://protobuf.dev/programming-guides/proto3/)、[protobufjs 静态类型](https://github.com/protobufjs/protobuf.js#static-code)。
