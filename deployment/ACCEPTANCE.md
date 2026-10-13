# 单实例 MVP 验收记录

验收日期：2026-10-13，最终协议为 Protobuf v2。当前蓝白界面和图标、用户精简后的根 README 已保留。全部服务只使用回环地址、临时端口、自有进程和独立 SQLite，未写业务数据库、发布公网、安装系统服务或修改证书信任。

## 最终构建与测试

| 验证 | 实测结果 | 证据/命令 |
| --- | --- | --- |
| 干净后端构建/生成 | 143 项单元测试通过；34 项集成测试中 33 通过、1 项 PostgreSQL 未配置跳过 | `./scripts/build-release.sh` 中 `mvn clean verify`；`/tmp/xim-final-release.log` 和 target XML |
| 前端生成、类型与构建 | 68 项测试通过，类型检查和 Vite 生产构建通过 | `npm run typecheck`、`npm test`、`npm run build`；`/tmp/xim-final-typecheck.log`、`/tmp/xim-final-frontend.log`、`/tmp/xim-final-frontend-build.log` |
| 真正发布包浏览器验收 | 7 组 Chrome E2E 全通过（约 1.5 分钟） | `npm run test:e2e`；夹具直接运行 `backend/target/release/xim.jar:lib/*`，静态服务提供生产 dist；`/tmp/xim-final-browser.log` |
| 发布脚本实际启动 | jar/lib、start-backend.sh、local.sh、指定 CA curl、HTTPS/WSS、Secure Cookie、群消息、在线备份和重启身份/历史通过 | `node scripts/release-smoke.mjs`；`/tmp/xim-final-smoke.log` |
| JSON/V6 数据升级 | 复制阶段一真实 JSON 服务写入的测试数据库，以最终 Protobuf 发布包登录、读取；原用户 ID、消息 ID、sequence、time 和正文一致，继续发送、备份、重启成功 | `XIM_MIGRATION_DB=/absolute/old-test.db node scripts/release-smoke.mjs`；`/tmp/xim-migration-smoke.log` |
| 数据模型保持 | 9 个 storage/迁移源码文件与迁移前快照哈希一致；数据库仍为 V6 | 与 pre-Protobuf manifest 比对，未新增或改写数据库迁移 |

浏览器逐项结果：

| 场景 | 实测覆盖 |
| --- | --- |
| 1. TLS、聊天与恢复 | 真实 TLS 连接、Secure/HttpOnly/SameSite=Strict Cookie、注册/登录、离线首聊、私聊回复、群创建/邀请/成员/退出/重加、未读、超过 50 条历史分页、刷新草稿、服务重启、在线备份与新路径恢复 |
| 2. 账号与多窗口 | 同浏览器第二窗口拒绝自动抢占、主动登录替换、旧窗口刷新不恢复、改密、旧密码失效、撤销、注销及刷新仍退出 |
| 3. 可靠重试与补拉 | 拦截真实服务回执和确认历史后人工重试；同 requestId、仅一条入库/展示；迟到原回执不重复；真实断网期间 65 条消息经多页 after 完整恢复 |
| 4. 联系人与移动端 | 22 位离线联系人两页、跨页选成员后搜索仍保留选择、离线群邀请、搜索、390×844 移动端离线首聊和无横向溢出 |
| 5. 凭证及限流 | 真实进程 3 秒绝对凭证过期；5 次错误密码后正确密码仍触发登录限流 |
| 6. int64 精度 | 真实 SQLite 序号 9007199254740993/994/995：二进制回执、顺序、十进制缓存、刷新、精确 after 请求和正文保持一致 |
| 7. 完整大目录 | 150 个会话、每条 4000 中文正文；快照多批且每帧 ≤64KiB；服务重启后各会话再添一条，所有 150 个补拉游标完成至 300、未读/排序保留、刷新目录完整、阅读只清当前会话、历史正文仍 4000 字 |

Protobuf 单元/网络测试另外覆盖全部 38 个 oneof 操作类型、可选字段与关联编号、Java/TS 共用 golden vectors、Unicode、Long.MAX_VALUE、普通未知字段及伪造发送者无效、损坏/坏 UTF-8/错误方向/版本/空与超大包。真实 TCP 连接测试拒绝 v1/未知算法、即时拒绝巨型帧头、连接容量释放及慢速零碎 HTTP 头不能延长绝对握手截止时间。长中文历史按实际字节缩页，before/after 均不漏记录。前端测试验证完整快照收齐再应用、缺批断线、Blob/ArrayBuffer 顺序、旧连接异步隔离，以及超大 Blob 不先复制。

## 备份恢复结果

测试原库中「备份之后消息」为 1 条，在线备份和恢复库均为 0 条；恢复文件完整性与外键检查通过。恢复时关闭旧页签，用新页签读取恢复历史，并继续向前分页验证旧回复存在。这样既检查数据库真实回滚，也避免恢复点之后的可选页签缓存混入结果。具体可复现流程见 [部署说明](README.md)，不能直接混用旧 WAL/SHM。

## 迁移前快照

阶段一先通过完整构建、5 组真实浏览器及发布启动/备份/重启演练，然后开始 Protobuf。

- 源码包：`/tmp/xim-pre-protobuf-20261013-05bac6af.tar.gz`
- 清单：`/tmp/xim-pre-protobuf-20261013-05bac6af.manifest.json`，含 197 个文件的 SHA-256。
- 当时 HEAD：`05bac6af84915a5be620b577fd85900bb902a4fa`。
- 包 SHA-256：`c7eaea105e84c6742fa67a2151168342940d9d62b65b3d0ae09f94318673afbb`。
- 不含 .git、node_modules、target、dist、数据库/sidecar、密钥、运行日志或 Playwright 产物；反映开始协议迁移时的源码，后续用户其他 chat 的变更保留在当前工作树。

最终发布 jar SHA-256：`fe455e8f23f61af8d5417dd3550c76352ce66bce24d3bd79e989db07ffc5bc95`。本 chat 未 commit 或 push，留给主 chat 统一按模块执行。

## 未验证条件

PostgreSQL 无 `XIM_TEST_PG_URL`/真实测试服务，1 项明确跳过，不能算兼容性通过。Docker CLI 存在，但守护进程 socket 不存在，未运行容器。nginx/systemd 和指定公网域名/CA 证书缺实际目标，提供配置示例但未声称实测。已实测的是本机静态 HTTPS/WSS 代理、指定临时 CA 校验和 Java 进程发布流程。

Vite 提示单个压缩前 JS chunk 约 502kB（gzip 约 112kB）；另有既有 Lombok/Netty/JDK 警告。构建成功，无失败测试。业务仍为单实例文本 MVP，跨实例路由、找回密码、账号删除不在本轮范围。
