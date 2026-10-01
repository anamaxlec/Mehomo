![Mehomo — 用 Material 3 Expressive 连接你的 Agent](docs/assets/mehomo-overview.svg)

# Mehomo

**以 Material 3 Expressive 为设计核心的 Memoh 原生 Android 客户端。**

[下载正式版 APK](https://github.com/anamaxlec/Mehomo/releases/latest) · [English](README.en.md) · [Memoh 上游项目](https://github.com/felinics/Memoh) · [图标素材](design/icon/README.md)

Mehomo 将会话、Agent 与云端工作空间整合进同一个 Android 应用，支持 **Memoh 官方云服务**与**自托管 Memoh**。

## M3E 贯穿整个界面

界面使用 **Jetpack Compose Material 3 Expressive 原生控件**，统一形状、动效和颜色角色。聊天主体为原生 Compose；终端和桌面使用本地打包的专用 Web 渲染器。

- **有层次的控件：** 分组卡片和列表、按钮组、带图标的 tonal 操作、覆盖底部按钮的浮动菜单。
- **清晰的页面结构：** 带强调色的大标题随滚动收起到顶栏，初次加载使用统一的骨架屏过渡。
- **舒适的输入与阅读：** 紧凑的消息操作、复用的输入框、输入法避让、按供应商分组的模型和思考强度选择。
- **用户自定义：** 浮动导航可调整显示项目和顺序，支持浅色、深色和可选择的强调色。
- **自适应图标：** 紫色双马尾头像、圆形自适应图标，以及跟随壁纸配色的独立单色图层。

当前基于 `androidx.compose.material3:material3:1.5.0-alpha29` 开发，并会随 Material 3 Expressive API 演进继续适配。

## 它具体能做什么

Mehomo 面向已经在 Memoh 上使用 Bot 的用户：你可以在手机上发起任务、阅读执行过程、处理需要你决定的步骤，再进入同一个 Bot 的云端工作空间查看文件、终端或桌面。它连接现有 Memoh 服务，模型和 Agent 的实际执行由服务端负责。

### 对话与任务执行

| 功能 | 在应用中可以做什么 |
|---|---|
| Bot 与会话 | 切换 Bot；新建、搜索、重命名、删除会话；按时间分组查看历史，保留外接渠道来源标记 |
| 流式回复 | 边生成边阅读；展开思考和工具调用；停止生成；查看会话正在运行的状态图标 |
| 模型选择 | 搜索服务端模型目录、按供应商分组，切换模型和模型支持的思考强度；支持普通与 ACP Agent 的模型切换路径 |
| 执行位置 | 选择服务端提供的工作空间、Agent 和会话文件夹；底部展示实际选中项，无文件夹的已有会话不显示占位按钮 |
| 上下文 | 查看服务端报告的上下文占用，刷新状态；在运行时支持的情况下压缩会话上下文 |
| 消息操作 | 复制回复、重新生成、从某轮新建分支、查看消息时间 |
| 附件 | 从 Android 文件选择器选择文件或图片；发送附件，读取消息图片并打开预览 |
| 审批与提问 | 批准或拒绝工具请求；回答单选、多选、自定义文本等结构化问题 |
| 排队与插话 | 向 follow-up 队列追加任务，编辑、删除和重排；在运行时支持的情况下用 steer 插入当前任务 |
| 运行控制 | 查看并切换服务端提供的运行模式、权限模式，设置任务目标，执行快捷操作和查看可用技能 |

模型、思考强度、工作空间和运行控制选项会根据服务端能力动态展示。

### 阅读与 Markdown

支持标题、段落、粗体、斜体、列表、引用、分隔线、删除线、可点击链接、任务列表、表格，以及带语法着色和复制操作的代码块。宽代码块可以横向滚动，表格支持单元格换行；较长会话使用分页历史和稳定消息身份，处理历史与实时回复的交接。消息、模型目录和部分功能页的初次加载使用骨架屏。

### Bot 管理与云端工作空间

| 功能 | 已接入的操作 |
|---|---|
| 长期记忆 | 浏览、新增、编辑、删除、搜索记忆，查看状态与关系图，执行记忆压缩 |
| 定时任务 | 新建和编辑任务、启用或停用、删除、查看执行记录；可配置服务端支持的 cron/pattern 与提示词等参数 |
| 用量与状态 | 切换近 7/30/90 天，查看输入、输出、推理、缓存读取用量；按模型和供应商查看统计，分页查看调用记录并进入对应会话 |
| 容器指标 | 服务端提供指标时显示 CPU、内存和存储信息；不提供时明确显示不可用状态 |
| 应用 | 浏览应用商店、搜索、查看详情、安装并显示 SSE 进度；查看已安装应用、检查更新和卸载；查看连接状态 |
| 技能 | 浏览技能商店、查看已安装技能，导入或编辑技能内容、删除及执行服务端支持的技能操作 |
| MCP | 查看、新增、编辑、删除 MCP 连接，探测连接并查看工具信息 |
| 文件 | 浏览目录、筛选当前文件夹、预览和编辑文本文件、新建、重命名、删除、上传及下载 |
| 终端 | 连接 Bot 的 PTY，查看终端输出、输入命令、调整终端尺寸，使用常用终端按键 |
| 桌面 | 连接官方云 VNC 桌面或自托管 WebRTC 桌面，显示并操作远程工作空间 |
| 浏览器 | 通过服务端的端口预览功能打开云端应用或网站 |

Mehomo 直接连接并管理当前账号可访问的 Bot 与云端工作空间，模型和 Agent 的执行仍由 Memoh 服务端负责。

## 接入与适配了什么

### Memoh 服务与协议

| 对象 | 适配内容 |
|---|---|
| 官方 Memoh Cloud | 邮箱验证码登录、团队选择、登录状态恢复、Cookie 认证、团队范围请求；聊天和工作空间连接使用服务端签发的 ticket |
| 自托管 Memoh | HTTPS 服务地址、根路径或 `/api` 反代路径、用户名/密码登录、Bearer 认证和有效期内主动续期 |
| 聊天 WebSocket | 会话运行状态订阅、快照/增量事件归并、受理与确认、断线重连；序列缺口重新请求快照，避免丢弃已有历史 |
| 会话活动 SSE | 应用前台订阅 Bot 会话变化，刷新列表、日程等相关状态 |
| Agent / ACP | 读取服务端模型目录和能力，接入 ACP 模型、思考强度与运行控制 |
| 云端终端和桌面 | 终端采用 PTY + 本地打包的 xterm.js；官方云桌面采用 VNC/noVNC，自托管桌面保留 WebRTC 路径 |
| 外接渠道 | 会话列表保留服务端提供的渠道来源标记，例如 Telegram |

### Android 与界面

- **Android 8.0+：** `minSdk 26`，`targetSdk 36`，构建使用 `compileSdk 37`。
- **原生 M3E：** Compose 会话与管理页面、`MaterialExpressiveTheme`、expressive motion、分组卡片、按钮组、tonal 操作、大标题滚动收起和原生弹窗。
- **输入法与系统栏：** 会话、终端输入及管理表单处理 IME insets；长表单可以滚动，会话控制面板直接完整展开，减少键盘遮挡。
- **字体和布局：** 文本换行、响应式统计容器、受限宽度的消息阅读列，并适配字体放大场景。
- **外观：** 跟随系统/浅色/深色，用户可选择强调色；浮动导航可选择 1–4 个入口并调整顺序。
- **图标：** 默认浅紫底深紫头像，夜间资源保留紫色反差；圆形自适应图标及 Android 13+ 支持的单色主题图层。开启系统主题图标后，颜色由启动器按壁纸与主题决定，取决于启动器支持。
- **附件与凭据：** 使用 Android 文件选择器；凭据加密保存，服务地址要求 HTTPS。

## 当前状态

主要功能链路已经完成实现，并通过 release 构建与单元测试。官方云的登录、对话、模型切换、文件、终端和 VNC 桌面已完成实际连接验证；自托管适配、部分云端写操作和更多 Android 设备仍在持续覆盖。

### 当前限制

- 后台推送通知和 Android Live Updates 暂未接入。
- Markdown 暂不支持原始 HTML、数学公式、Mermaid 和 SVG；动态图显示首帧。
- 第三方连接器的独立 OAuth/API-key 配置仍在完善。
- 凭据存储目前使用 `EncryptedSharedPreferences`，后续计划迁移到新的 Android 凭据存储方案。

## 构建

支持 **Android 8.0+**（`minSdk 26`）。开发需要 **JDK 17+**、Android SDK **platform 37**；`targetSdk` 为 36。使用仓库自带的 Gradle wrapper。

```bash
./gradlew :app:assembleDebug
./gradlew testDebugUnitTest
```

通过 Android Studio 环境、`ANDROID_HOME` 或不提交到 Git 的 `local.properties` 设置 SDK 路径，并配置 `JAVA_HOME`。调试 APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。

### 签名正式版

准备自己的签名密钥，将 `keystore.properties.example` 复制为 `keystore.properties`，填写密钥绝对路径、alias 和密码。签名密钥及本地密码配置不进入仓库。

```bash
./gradlew :app:assembleRelease
```

配置签名后，正式版位于 `app/build/outputs/apk/release/app-release.apk`；未配置签名时生成未签名的 release APK。请安全备份密钥，后续升级必须使用同一密钥。正式签名 APK 无法直接覆盖同包名的调试签名安装。

## 架构

| 模块 | 职责 |
|---|---|
| `app` | 导航、依赖注入、应用入口 |
| `core:model` / `core:network` / `core:data` | 数据模型、REST 与 WebSocket 协议、运行事件归并、加密凭据、偏好设置 |
| `core:designsystem` / `core:markdown` | M3E 主题与通用控件、Markdown 渲染 |
| `feature:login` / `feature:chat` / `feature:sessions` | 登录、会话、Bot 管理、云端工作空间 |
| `feature:settings` / `feature:bots` | 外观设置、预留的 Bot 功能模块 |

官方云连接使用加密 Cookie 存储、团队选择和短期 WebSocket ticket；自托管连接使用 bearer 认证。服务地址要求 HTTPS。请勿将真实凭据写进 issue、日志或测试 fixture。

## 许可证与致谢

源码遵循 **AGPL-3.0**，详见 [LICENSE](LICENSE) 与 [第三方声明](THIRD_PARTY_NOTICES.md)。Memoh 是上游服务；Mehomo 与 Memoh、Google、xAI、Crypton Future Media 没有官方关联。生成的头像是参考初音未来及用户提供的扁平 bot 头像风格创作的同人图，角色与品牌权利归各自权利方所有。
