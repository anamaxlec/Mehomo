# Memoh v0.22.0 适配记录

2026-10-19 对比官方 Memoh 从 `bba5ae9a0a59` 到 `ead52ca506ea` 的 21 条提交。

官方来源：[v0.22.0](https://github.com/felinics/Memoh/releases/tag/v0.22.0)、[本次完整增量](https://github.com/felinics/Memoh/compare/bba5ae9a0a5928a20b22261aad2db45733e6c4bd...ead52ca506ea45529fae3995d14d12c5f9dc71c6)。范围包括 v0.22.0 发布后的桌面剪贴板、输入法和准备锁修复。

| 上游变化 | Android 处理 |
| --- | --- |
| 直连运行时通用化，新增 Grok Build | 共用运行时定义补充 `grok-build`，支持创建、配置、启用和默认 Agent 选择；Grok 使用自身的模型与思考等级目录。日程已有按 Agent ID 读取模型及保存执行配置的路径，继续复用。 |
| 通用 Agent 设备授权 | Codex、Grok 使用 `/bots/{bot}/agents/{agent}/login/device/{authorize,poll,cancel}`；成功后刷新 Agent，不调用 staged claim。Codex 在旧服务端接口返回 404 时沿用既有 staged 授权；Grok 不降级到不支持的 staged 账号授权。 |
| xAI API key | 使用 `xai_api_key` 和服务端授权凭据认领接口；认证模式为 `api_key`。Grok 账号模式为 `grok_account`。切换模式前要求断开已有凭据。 |
| Grok 运行命令与分支 | 任务执行期间禁用 Grok 运行命令；根据每条回复的 `runtime_forkable` 显示分支入口，不自行按当前分页位置推断。 |
| Claude Code 思考显示、历史追加保留工具与 reasoning | 保持通用消息渲染与合并路径；验证 reasoning → tool → text 的流、结束快照及历史恢复，复用问题面板支持多选和自定义回答。 |
| 桌面文本剪贴板 | WebRTC 发送 `{type: "clipboard", text}`，接收 `clipboard` 和 `clipboard_error`；Cloud RFB 使用已有 noVNC 剪贴板事件和粘贴方法。原生按钮分别触发本机粘贴、复制远程文字，按 UTF-8 限制 48 KiB，并检查协议消息上限。 |
| 桌面输入法、编辑快捷键与可见性管理 | 保留原生输入框提交中文后按 Unicode keysym 发送的方式；输入失败保留草稿，失焦释放 WebRTC 按键。等待输入通道就绪后显示已连接；离开关闭查看连接，回来恢复曾主动连接的桌面，手动断开保持断开。 |
| 桌面失效会话、准备锁与 bridge 回收 | 关闭时释放已有查看会话；迟到的 offer 用原账号 API 和原 Bot 回收，旧回调不得修改新连接。短暂中断最多重试三次，离开或手动断开取消重试。服务端准备锁和 bridge 回收由上游提供。 |
| OpenAI Codex / GitHub Copilot 供应商接口类型 | 当前原生供应商编辑器与导入解析已经包含 `openai-codex`、`github-copilot`，无需新增平行配置。 |
| 压缩预算和连续前缀、失败历史行、记忆概览延迟刷新 | 服务端内部修复，使用现有历史、上下文和记忆接口；无需客户端模拟服务端状态或修改压缩参数。 |
| memoh-help skill、上下文导入测试、Web/iOS sticky 顶栏及版本发布 | skill 和测试随服务端安装；Web/iOS 特有布局不移植到 Android 原生顶栏。 |

桌面脚本不保存账号 token、Cloud cookie 或 API key；这些仍由原生网络层持有。前后台恢复只恢复查看连接，不创建或重新执行对话任务。
