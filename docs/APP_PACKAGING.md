# Web、桌面与移动 App

所有端使用 `frontend/src` 中同一套 React、TypeScript、Protobuf 客户端、组件和 CSS。Tauri 2 仅提供原生窗口及通信适配，现有移动布局和屏幕安全区域用于小屏；没有独立的 App 界面。Java + Netty 后端仍作为远程独立服务运行，安装包不含 JVM、数据库或后端进程。

## 服务地址与认证

Web 继续用 `npm run dev/build/preview`，产物为 `frontend/dist/`。默认连接当前页面的 `/ws`、`/api/session`，开发时 Vite 读取 `XIM_BACKEND_URL` 代理，部署时使用现有 HTTPS 反向代理。`VITE_WS_URL` 只用于既有同站点直连场景；仍须精确配置后端 `xim.web.allowedOrigins`，Cookie 的 SameSite=Strict 规则仍生效。不要为 App 放宽 Cookie、Origin 或跨站请求检查。

App 通过 Rust 的 HTTP/WebSocket 客户端直连服务，地址来自构建时 `XIM_APP_SERVER_URL`。复制 `frontend/.env.app.example` 为 `.env.app.local`，或设置环境变量（环境变量优先）。填写没有路径、查询、用户名密码的 HTTPS origin，例如 `https://chat.example.com`。由此派生 `/api/session` 与 `wss://chat.example.com/ws`。App 不使用 Vite 代理，也不使用 `VITE_WS_URL`；上线前需要一个真实可达的服务地址，修改地址需要重新构建。Release 构建未设置地址或使用 HTTP 会提前报错。

App 保留现有协议：WebSocket 密码登录返回一次性 ticket，原生侧 POST `/api/session` 兑换 HttpOnly Cookie；原生 WebSocket 握手携带 Cookie，`resume` 保持原有连接租约、撤销、过期及补拉语义。原生请求不带浏览器的 Origin/Sec-Fetch-Site，后端既有规则允许无 Origin 的非浏览器客户端；不需要加入 `tauri://localhost` 或启用跨站 Cookie。TLS 校验证书链和主机名，不允许关闭校验，HTTP 不跟随重定向。自签名的 `deployment/local.sh` 证书不能直接用于 App 正式连接，应使用受信任 CA 的服务。

会话 Cookie 只留在 Rust 内存及当前用户的 App 数据目录 `session.json`，包含服务 origin、Cookie 和到期时间，不返回 JavaScript；换服务或过期不复用。macOS/Linux 使用目录 0700、文件 0600，Windows 使用用户 AppData 的继承权限，移动端使用应用私有目录。该文件是会话凭据，**没有额外加密或系统钥匙串保护**；不要备份分享或记录内容。退出登录先调用原有 DELETE 撤销服务端会话，再删除本机凭据；离线退出继续使用现有注销待重试机制。渲染层仅获授权使用四个固定服务的通信命令，无任意 HTTP、文件、Shell 权限。禁止给远程页面添加原生 capability。

本机开发允许 HTTP，后端需 `-Dxim.web.cookieSecure=false`。`app:dev` 缺省地址是 `http://127.0.0.1:8080`；Release 本机验收需要显式 `XIM_APP_ALLOW_HTTP=1`，不要把该验收包当作线上发行包。移动设备的 `127.0.0.1` 指设备自身：Android 模拟器可使用 `10.0.2.2`，真机应使用可达 HTTPS 域名或局域网开发机地址；局域网 HTTP 也须显式允许。原生网络不依赖 WebView 的第三方 Cookie、混合内容或 ATS 放宽。后台挂起可能中断连接，回到前台后现有心跳超时和重连会恢复登录并补拉；不提供系统推送或常驻后台服务。

## 通用准备与命令

安装 Node.js 20.19+/22.12+、Rust stable 1.90+（锁文件当前验证于 1.97.1），然后 `cd frontend && npm ci`。App 不需要客户端安装 Java；只有运行/构建独立后端和 Android 工具链需要相应 JDK。Rust 依赖使用 `src-tauri/Cargo.lock`，npm 使用 `package-lock.json`；升级时保持 Tauri CLI/API/Rust 的兼容版本。

```sh
cd frontend
npm test
npm run test:native
npm run build                 # 独立 Web: dist/
npm run app:dev               # 当前桌面平台开发窗口 + Vite
# 设置 XIM_APP_SERVER_URL 后：
npm run app:macos             # macOS .app 与 .dmg
npm run app:windows           # Windows NSIS .exe 与 MSI
npm run app:android:init      # 首次生成 Android 工程
npm run app:android -- --apk --aab
npm run app:ios:init          # 首次生成 Xcode 工程
npm run app:ios -- --export-method debugging
```

额外 Tauri 参数在 `--` 后传入，例如 `--target`、`--debug`、`--config`。`app:build` 是当前桌面平台的通用入口。App 前端构建输出 `dist-app/`，与 Web `dist/` 分离，打包不会覆盖待部署的 Web 目录。`npm run build:app` 只构建界面资产，完整 App 用以上包装脚本；直接调用 `npm run tauri` 需要自行提供相同环境变量。`TAURI_DEV_HOST` 由移动开发 CLI 传入以支持设备访问 Vite/HMR。

`src-tauri/gen/` 是机器相关生成工程，不提交；首次或删除目录后重新 init。应用标识默认为 `cn.xjn.xim`，发行前按实际归属确认；修改标识后重新生成移动工程。图标由现有 `public/icon.png` 派生，`npm run app:icons` 可重生成各平台尺寸。不要把证书、私钥、keystore、会话文件或本机 `.env.app.local` 提交。

## macOS

在 macOS 上安装 Apple Command Line Tools 和 Rust。macOS 最低版本为 12；默认构建当前 CPU，产物为 `src-tauri/target/release/bundle/macos/XIM.app` 和 `bundle/dmg/`。Intel 或 universal 需另装相应 Rust target：

```sh
rustup target add aarch64-apple-darwin x86_64-apple-darwin
npm run app:macos -- --target universal-apple-darwin
open src-tauri/target/release/bundle/macos/XIM.app
```

如果自动化环境的 Finder 美化步骤报 `bundle_dmg.sh` 错误，使用 `CI=true npm run app:macos`（PowerShell 设置 `$env:CI = 'true'`）跳过该美化步骤；仍生成同样的安装包。

默认 `signingIdentity: "-"` 仅作 ad-hoc 本机验证。正式分发需有效 Developer ID Application 身份，并覆盖配置（不要把示例名称当作证书）：

```sh
npm run app:macos -- --config '{"bundle":{"macOS":{"signingIdentity":"Developer ID Application: YOUR NAME (TEAMID)"}}}'
```

签名身份来自实际钥匙串或 CI 的 `APPLE_CERTIFICATE` / `APPLE_CERTIFICATE_PASSWORD`。公证另需合法 Apple 凭据，例如 `APPLE_ID`、`APPLE_PASSWORD`（App 专用密码）、`APPLE_TEAM_ID`，或官方支持的 API key。本任务未提供这些材料，也未执行公证、上传或商店发布。详见 [Tauri macOS 签名](https://v2.tauri.app/distribute/sign/macos/)。

## Windows

在 Windows 构建主机安装 MSVC C++ Build Tools、Windows SDK、Rust MSVC toolchain、Node 和 WebView2。示例为 PowerShell：

```powershell
cd frontend
npm ci
$env:XIM_APP_SERVER_URL = 'https://chat.example.com'
rustup target add x86_64-pc-windows-msvc
npm run app:windows -- --target x86_64-pc-windows-msvc
```

默认生成当前用户安装的 NSIS 和 MSI；NSIS 默认联网下载缺少的 WebView2，可用 `--config` 将 `bundle.windows.webviewInstallMode.type` 改为 `embedBootstrapper` 或 `offlineInstaller`。离线部署应选择相应模式。产物在 `src-tauri/target/<target>/release/bundle/nsis/` 与 `msi/`；未传 target 时省略 `<target>/`。

无签名证书可生成本地验收安装包，正式签名需真实代码签名证书/服务。在本机私有配置里填写 `bundle.windows.certificateThumbprint`、`digestAlgorithm: "sha256"`、`timestampUrl`，或自定义 `signCommand`，通过 `--config` 传入；参考 [Tauri Windows 签名](https://v2.tauri.app/distribute/sign/windows/)。本任务没有在 macOS 上伪装执行 Windows 构建。

## Android

安装 Android Studio、SDK、NDK、Android Studio 自带 JDK，设置 `JAVA_HOME`、`ANDROID_HOME`、`NDK_HOME`。后端继续使用 JDK 25，Android 构建终端可单独使用 Android Studio JDK。安装 Rust targets：

```sh
rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android
npm run app:android:init
npm run app:android:dev
npm run app:android -- --apk --aab --target aarch64
```

最低 SDK 24、版本码 10000，升级发布须递增 `bundle.android.versionCode`，target SDK/NDK 以生成工程、当前工具链及实际分发渠道要求为准。本任务没有宣称满足商店当期审核。产物在 `src-tauri/gen/android/app/build/outputs/apk/` 与 `bundle/`。开发调试签名不能替代正式发行签名。

正式 release 使用自己的 keystore/upload key。首次 init 后，在生成的 `app/build.gradle.kts` 中加入下面配置（保留已有 debug 配置），从环境读取密钥参数，不写入生成工程：

```kotlin
// 放在 android { ... } 内，在 buildTypes 之前
signingConfigs {
    create("ximRelease") {
        storeFile = file(requireNotNull(System.getenv("XIM_ANDROID_KEYSTORE")))
        storePassword = requireNotNull(System.getenv("XIM_ANDROID_STORE_PASSWORD"))
        keyAlias = requireNotNull(System.getenv("XIM_ANDROID_KEY_ALIAS"))
        keyPassword = requireNotNull(System.getenv("XIM_ANDROID_KEY_PASSWORD"))
    }
}
// 放在已有 buildTypes { getByName("release") { ... } 内
signingConfig = signingConfigs.getByName("ximRelease")
```

四项变量由本机或 CI 密钥管理注入。缺失时配置失败，避免误用调试签名；重新 init 后需重新应用配置。签名方案参考 [Tauri Android 签名](https://v2.tauri.app/distribute/sign/android/)。没有在此任务创建或猜测 keystore，也没有执行 Android 构建/设备测试。

## iOS

只能在 macOS 构建，需要完整 Xcode、iOS SDK、CocoaPods、Apple 开发团队；Command Line Tools 不能替代 Xcode。最低 iOS 15。安装 targets 并生成项目：

```sh
rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios
npm run app:ios:init
# Xcode 中配置真实 Team、Bundle Identifier、证书及 provisioning profile
npm run app:ios:dev
npm run app:ios -- --export-method debugging
# 仅需不签名归档时（不能安装到真机）：
npm run app:ios -- --no-sign --archive-only
```

团队可通过 `APPLE_DEVELOPMENT_TEAM` 或 `--config '{"bundle":{"iOS":{"developmentTeam":"YOUR_TEAM_ID"}}}'` 配置。生成项目及归档在 `src-tauri/gen/apple/` 下；需要真实证书/profile 才能导出可安装 IPA。商店渠道使用官方 CLI 支持的 `app-store-connect`，仅生成包不代表上传；参考 [Tauri iOS 签名](https://v2.tauri.app/distribute/sign/ios/)。当前主机只有 Command Line Tools，没有完整 Xcode；本任务未执行 iOS 构建/模拟器/真机测试。

## 验收边界

实际运行结果、构建产物与限制见 [多平台验收记录](APP_ACCEPTANCE.md)。Windows/Android/iOS 在本任务的交付范围是共享代码、平台配置、图标、构建入口与环境/签名说明；它们仍需在各自主机和设备上验证，不应把配置存在解读为运行通过。
