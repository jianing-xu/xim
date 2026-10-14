# 多平台打包验收（2026-10-14）

本次保留同一套 React 19 + TypeScript + Vite 7 界面与 Protobuf 协议，接入 Tauri 2.12.3（CLI 2.12.2、API 2.12.3）。Java/Netty 后端仍独立运行；没有把服务器或真实数据库打入 App。平台通信代码在 `frontend/src/platform/` 与 `frontend/src-tauri/src/lib.rs`，打包命令和限制见 [使用说明](APP_PACKAGING.md)。

## 验证状态

| 项目 | 配置实现 | 构建 | 实际运行验证 |
| --- | --- | --- | --- |
| 独立 Web | 是 | 生产 `dist/` 通过 | Chrome 下 7 项 HTTPS/WSS E2E 通过；另与原生 App 双向互通 |
| macOS arm64 | 是 | `.app`、DMG 通过，ad-hoc 签名 | 真实 WKWebView 窗口登录、中文/Emoji 私聊、Cookie 重启恢复、离线补拉、后端断线重连通过 |
| macOS Intel/universal | 是，提供 target 命令 | 未执行 | 未执行 |
| Windows | 是，NSIS/MSI/WebView2 与签名入口 | 未执行 | 未执行 |
| Android | 是，移动入口、SDK 24、图标、命令及签名接入说明 | 未执行 | 未执行 |
| iOS | 是，移动入口、iOS 15、图标、命令及 Team 配置入口 | 未执行 | 未执行 |

本次 macOS 运行环境为 Apple Silicon、macOS 26.6、Rust 1.97.1，工具链只有 Apple Command Line Tools，没有完整 Xcode。其他三端按任务要求不运行构建或设备测试。屏幕安全区域和已有移动布局在共享 CSS 中实现，不能据此宣称移动真机软键盘、切前后台等系统行为通过。

## 自动验证

- `npm test`：73 项 Vitest 通过（原有协议、聊天、恢复、联系人 68 项；新增原生 IPC/连接取消/发送顺序/注销排序 3 项、兼容请求超时 2 项）。
- `cargo test --manifest-path frontend/src-tauri/Cargo.toml --locked --offline`：3 项通过，覆盖服务 origin/TLS 策略、Cookie 注入防护、持久会话 origin/过期限制与 0600 权限。
- `npm run build`：TypeScript 检查与 Vite 生产构建通过，独立 Web 产物位于 `frontend/dist/`。
- `mvn -f backend/pom.xml verify`：143 项单元测试通过，34 项集成测试中 33 项通过、1 项 PostgreSQL 测试因未配置服务跳过。后端源码与协议未因 App 改动。
- `npm run e2e:prepare` 生成独立服务器 jar/lib；`npm run test:e2e` 在隔离 Chrome 与临时 SQLite/HTTPS 代理中全部 7 项通过。覆盖注册/登录、私聊/群聊、离线联系人、未读和历史、刷新、后端重启、SQLite 备份恢复、掉/迟回执、重复重试、断线补拉、密码与凭证撤销、过期和失败限速、int64 精度及 150 会话分批恢复。报告为 `frontend/playwright-report/index.html`，后端报告在 `backend/target/{surefire,failsafe}-reports/`。

首次沙箱内 Chrome 启动被系统终止，测试未执行；随后在允许浏览器启动的执行环境重跑，上述 7 项全部通过。首次 Rust 编译发现旧版 Tauri 与最新运行时不兼容，已统一到匹配版本并锁定依赖后通过。

## macOS 构建与实际流程

本机验收包使用独立应用标识 `cn.xjn.xim.acceptance`、独立临时数据库 `/tmp/xim-native-20261014/chat.db`，仅连接回环端口 18880。允许 HTTP 是显式本机验收例外；不是线上发行配置。

```sh
cd frontend
CI=true XIM_APP_SERVER_URL=http://127.0.0.1:18880 XIM_APP_ALLOW_HTTP=1 \
  npm run app:macos -- --config '{"identifier":"cn.xjn.xim.acceptance"}'
```

输出：

- `frontend/src-tauri/target/release/bundle/macos/XIM.app`（约 13.1 MiB）。
- `frontend/src-tauri/target/release/bundle/dmg/XIM_1.0.0_aarch64.dmg`（约 4.3 MiB）。

`codesign --verify --deep --strict` 通过，`hdiutil verify` 校验通过。默认 Finder 美化脚本首次超时失败，使用 Tauri `CI=true` 跳过美化后成功封装；没有禁用系统安全设置。签名为本机 ad-hoc，没有 Developer ID 签名或 Apple 公证。没有商店上传、生产部署或生成虚假证书。

实际通过原生窗口和独立 Web 页面操作：

1. 原生窗口显示共享登录页，连接临时 Netty 服务，使用临时演示账号 root 登录；Web 的 aaa 登录同一服务。
2. App 与 Web 双向发送中文和 🌍 消息，双方显示内容一致；发送侧获得持久化回执「已发送」。
3. 退出 App 进程，Web 给离线 root 发消息。重新启动 `.app` 后，原生持久 Cookie 自动恢复 root 登录；会话显示未读，打开后出现离线消息与原历史，没有要求重新输入密码。
4. 停止临时后端，App 显示「连接断开 · 正在重连」，输入框禁用。重启相同临时数据库的后端后，App 与 Web 自动恢复连接，双方继续发送消息并得到回执。
5. 界面复用原有组件与 CSS；原生窗口、Web 及断线恢复证据见下方截图。

[macOS 聊天截图](app-macos-chat.png)、[Web 聊天截图](app-web-chat.png)、[App 重启恢复](app-macos-recovery.png)、[断线状态](app-macos-disconnected.png)、[重连后收发](app-macos-reconnected.png)。这些截图仅包含隔离测试账号与验收消息。最终产物在兼容超时清理与格式化后已重新构建；随后尝试再次打开 App 时系统锁屏，未继续操作。上述实际核心流程在此前构建包中完成，未宣称锁屏后的最终启动检查通过。

## 未验证或剩余外部条件

App 的 HTTPS/WSS、证书/主机名校验与 Secure Cookie 路径已实现；本次 macOS 核心运行使用显式允许的本机 HTTP/WS，**未用真实受信任 HTTPS 服务完成原生登录验收**。Web 的 HTTPS/WSS 登录与恢复已由真实浏览器 E2E 验证。上线前应将 App 构建为实际 HTTPS 服务地址，保持 `xim.web.cookieSecure=true`，验证原生 TLS 链与反向代理。不要在发行包启用 `XIM_APP_ALLOW_HTTP`。

App 会话 Cookie 存在私有应用目录，未额外加密；不存密码、不暴露给 JS，仍需按凭据保护本机文件及备份。背景挂起没有系统推送/后台常驻承诺。Android 签名 Gradle 配置需在 init 后应用到生成工程，iOS 需要真实 Team/证书/profile；这些配置和运行行为都需要对应环境继续验收。Windows 安装器/离线 WebView2、Android APK/AAB 和 iOS 归档/IPA 均没有在本次生成或设备运行，签名及商店审核也不在本次完成范围。
