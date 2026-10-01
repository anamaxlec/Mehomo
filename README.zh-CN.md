![Mehomo — 用 Material 3 Expressive 连接你的 Agent](docs/assets/mehomo-overview.svg)

# Mehomo

**以 Material 3 Expressive 为设计核心的 Memoh 原生 Android 客户端。**

[English](README.md) · [Memoh 上游项目](https://github.com/felinics/Memoh) · [图标素材](design/icon/README.md)

Mehomo 将会话和 Agent 的云端工作空间放进同一个 Android 应用，支持 **Memoh 官方云服务**和**自托管 Memoh**。这是独立开发的非官方客户端。

## M3E 贯穿整个界面

界面使用 **Jetpack Compose Material 3 Expressive 原生控件**，统一形状、动效和颜色角色。聊天主体为原生 Compose；终端和桌面使用本地打包的专用 Web 渲染器。

- **有层次的控件：**分组卡片和列表、按钮组、带图标的 tonal 操作、覆盖底部按钮的浮动菜单。
- **清晰的页面结构：**带强调色的大标题随滚动收起到顶栏，初次加载使用统一的骨架屏过渡。
- **舒适的输入与阅读：**紧凑的消息操作、复用的输入框、输入法避让、按供应商分组的模型和思考强度选择。
- **用户自定义：**浮动导航可调整显示项目和顺序，支持浅色、深色和可选择的强调色。
- **自适应图标：**紫色双马尾头像、圆形自适应图标，以及跟随壁纸配色的独立单色图层。

当前固定使用 `androidx.compose.material3:material3:1.5.0-alpha29`。M3E 的部分 API 仍处于 alpha 阶段，依赖版本经过明确选择。

## 功能

| 模块 | 内容 |
|---|---|
| 会话 | 历史消息、流式回复、思考与工具调用、供应商分组、思考强度 |
| Markdown | 表格、代码块、链接、任务列表、引用、删除线 |
| 消息操作 | 复制、重新生成、新分支、消息时间 |
| Agent 控制 | 工具审批、提问、附件、追加与插话队列、运行控制 |
| 云端工作空间 | 文件管理、端口预览、终端、远程桌面 |
| Bot 管理 | 记忆、定时任务与执行记录、Token 用量、应用、技能、MCP 管理 |

**验证范围：**162 项单元测试覆盖协议、消息归并和部分 UI 辅助逻辑。已在 API 36 模拟器上对官方云服务进行部分端到端检查，包括历史消息、流式回复、终端与桌面、文件和部分管理页面。自托管目前主要通过本地 HTTPS fixture 验证；审批和提问等边界通过 fixture 测试，并非全部服务端配置或接口都经过真实账号验证。

**现阶段限制：**推送通知和 Android Live Updates 暂缓。Markdown 不渲染原始 HTML、数学公式和 Mermaid，SVG 图片暂不支持，动态图显示首帧。第三方供应商的独立 OAuth/API-key 配置表单尚未完整实现。具体功能取决于服务端能力和账号权限。

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
