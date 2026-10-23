package dev.memoh.core.model

import kotlinx.serialization.json.*
import kotlin.random.Random

data class ChatExample(val id: String, val category: String, val title: String, val prompt: String, val requires: List<String> = emptyList()) {
    fun missing(capabilities: Map<String, Boolean>): List<String> = requires.filter { capabilities[it] == false }
}

// Official Memoh built-in catalog, adapted for the native Chinese interface.
val chatExamples: List<ChatExample> = listOf(
    ChatExample("personal-blog", "dev", "做一个个人博客", "在 /data/blog 下帮我做一个个人博客：前端用 Vite + Vue，后端用 Node.js，文章和评论存在 SQLite 里。写三篇示例文章，让后端在后台一直运行，然后在桌面浏览器里打开博客，带我看一遍。", listOf("workspace", "browser")),
    ChatExample("github-trending-brief", "automation", "GitHub Trending 每日简报", "每天早上 9 点，在桌面浏览器里打开 GitHub Trending，逐个查看今天排名前 10 的仓库，值得细看的就点进去。然后发我一份简报：每个项目做什么、用什么语言、为什么受关注。现在先跑一次，让我看看过程。", listOf("browser", "schedule")),
    ChatExample("build-open-source", "dev", "从源码编译 Redis", "从 GitHub 克隆 Redis，从源码编译两次：一次只用单核，一次用满全部 CPU 核心。对比两次的编译耗时，然后跑完整测试套件，告诉我通过了多少项。", listOf("workspace")),
    ChatExample("local-docs-site", "research", "把 Vite 文档站搬到本地", "把 Vite 官方文档的源码克隆到 /data/docs/vite，构建成站点并在这台机器上跑起来。然后在桌面浏览器里打开，给我看配置参考里 server.proxy 那一节。文档留在存储里，下次还能直接用。", listOf("workspace", "browser")),
    ChatExample("competitor-analysis", "research", "垂直领域竞品分析", "帮我的产品做一份竞品分析。找出同一细分领域的 5 到 8 个直接竞品，在浏览器里逐个访问官网和定价页，把功能、价格、目标客户和近期动态整理成表格存到 /data/reports，最后给出三个我们可以抓住的机会。我的产品和所在市场：", listOf("browser", "workspace")),
    ChatExample("morning-mail-digest", "automation", "早间邮件摘要", "每天早上 8 点，把昨晚收到的未读邮件按紧急程度整理成一份简短摘要发给我。", listOf("email", "schedule")),
    ChatExample("research-brief", "research", "研究助理", "帮我研究一个主题。先问我想了解什么，然后找可靠的资料来源，最后写一页简报。", listOf("web_search")),
    ChatExample("remember-preference", "memory", "记住我的偏好", "记住：我对花生过敏。以后推荐菜谱或帮我点外卖时都要避开。", listOf("memory")),
    ChatExample("weekly-report-reminder", "team", "周报提醒", "每周五下午 5 点提醒群里的成员填周报，晚上 8 点把还没交的人整理成名单发给我。", listOf("schedule", "channel")),
    ChatExample("price-drop-alert", "automation", "降价提醒", "每天看一次这款显示器的价格，降到 1500 元以下马上通知我：", listOf("browser", "schedule")),
    ChatExample("analyze-spending", "dev", "账单分析", "用 Python 分析我上传的 CSV 账单，按类别画出本月支出图表，并指出增长最多的一项。", listOf("workspace")),
    ChatExample("meeting-notes", "team", "群聊纪要", "把今天群聊里的讨论整理成会议纪要，列出待办事项和负责人。", listOf("channel", "memory")),
    ChatExample("broken-links", "dev", "检查失效链接", "用浏览器打开我们的网站，逐页检查失效链接，整理成表格给我：", listOf("browser")),
    ChatExample("evening-journal", "memory", "睡前日记", "每天晚上 11 点问我今天过得怎么样，把我的回答记进长期记忆，月底陪我回顾一次。", listOf("schedule", "memory")),
    ChatExample("draft-replies", "automation", "邮件回复草稿", "查看我的未读邮件，挑出需要我回复的，为每封写一份回复草稿，我看过之后再发。", listOf("email")),
    ChatExample("polish-writing", "writing", "润色一段文字", "帮我把下面这段文字改得更清楚、更简洁，保留我的语气，最后说明主要改了哪里：\n\n", listOf()),
    ChatExample("plan-my-week", "writing", "安排这一周", "帮我安排这一周。先问我有哪些截止日期和固定安排，再给出一份切实可行的每日计划。", listOf()),
    ChatExample("explain-concept", "research", "讲清楚一个概念", "一步步给我讲清楚一个概念，从我已经知道的部分讲起。先问我想了解哪个概念、我对它有多熟悉。", listOf()),
)

val chatExampleCategories = linkedMapOf("all" to "全部", "research" to "研究", "automation" to "自动化", "dev" to "开发", "memory" to "记忆", "team" to "协作", "writing" to "写作")

/** Pick locally so changing suggestions works independently of prompt generation. */
fun sampleChatExamples(
    capabilities: Map<String, Boolean> = emptyMap(),
    previous: List<StarterPrompt> = emptyList(),
    random: Random = Random.Default,
): List<StarterPrompt> {
    val available = chatExamples.filter { it.missing(capabilities).isEmpty() }
    val previousPrompts = previous.map { it.prompt }.toSet()
    val unseen = available.filter { it.prompt !in previousPrompts }
    val candidates = (if (unseen.size >= 3) unseen else available).shuffled(random)
    val diverse = candidates.distinctBy { it.category }
    return (diverse + candidates.filterNot { it in diverse }).take(3).map { StarterPrompt(it.title, it.prompt) }
}

fun exampleCapabilityLabel(id: String): String = when (id) {
    "web_search" -> "搜索服务"
    "memory" -> "记忆"
    "browser" -> "浏览器"
    "workspace" -> "工作空间"
    "email" -> "邮件应用"
    "channel" -> "消息渠道"
    else -> id
}

/** Failed or forbidden probes stay unknown, rather than disabling examples. */
fun exampleCapabilities(settings: JsonObject?, hasWorkspace: Boolean = false): Map<String, Boolean> = buildMap {
    put("schedule", true)
    if (hasWorkspace) put("workspace", true)
    settings?.let {
        put("web_search", (it["search_provider_id"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true)
        (it["memory_enabled"] as? JsonPrimitive)?.booleanOrNull?.let { value -> put("memory", value) }
        (it["display_enabled"] as? JsonPrimitive)?.booleanOrNull?.let { value -> put("browser", value) }
    }
}

fun resolveStarterPrompts(held: StarterPromptsResponse?, response: StarterPromptsResponse): StarterPromptsResponse = when {
    response.unchanged && held != null -> held
    response.hash.isNotBlank() && response.items.isNotEmpty() -> response.copy(unchanged = false)
    else -> held ?: StarterPromptsResponse()
}
