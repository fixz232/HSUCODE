package com.hsucode.app

/**
 * Built-in starting points for the agent center. They are copied into a user-owned
 * agent, so users can adjust every field without mutating the seeded safe roles.
 */
object SubAgentCatalog {
    data class Template(
        val id: String,
        val name: String,
        val avatar: String,
        val description: String,
        val prompt: String,
        val skills: List<String>,
        val tools: List<String>,
        val temperature: Float
    )

    data class ToolOption(val id: String, val label: String, val detail: String)

    val toolOptions = listOf(
        ToolOption("file_read", "读取文件", "查看文件内容"),
        ToolOption("list_dir", "浏览目录", "查看工作区结构"),
        ToolOption("glob", "按名称查找", "匹配文件路径"),
        ToolOption("grep", "内容检索", "在文件中查找文本"),
        ToolOption("web_search", "网页搜索", "查找公开资料"),
        ToolOption("web_fetch", "读取网页", "抓取公开页面内容"),
        ToolOption("file_write", "写入文件", "创建或完整写入文件"),
        ToolOption("file_edit", "编辑文件", "局部修改现有文件"),
        ToolOption("multi_edit", "批量编辑", "一次修改多个文件位置"),
        ToolOption("shell_exec", "执行命令", "在已选工作区运行命令")
    )

    val templates = listOf(
        Template(
            id = "planner",
            name = "规划师",
            avatar = "plan",
            description = "澄清目标、拆解任务并给出可执行方案",
            prompt = "你是规划师。先澄清目标、约束与验收标准，再把工作拆成小而可验证的步骤。不要直接修改文件；结论必须包含风险和下一步。",
            skills = listOf("explore"),
            tools = listOf("file_read", "list_dir", "glob", "grep", "web_search", "web_fetch"),
            temperature = 0.4f
        ),
        Template(
            id = "researcher",
            name = "研究员",
            avatar = "search",
            description = "检索资料，区分事实、推断与待验证项",
            prompt = "你是研究员。围绕问题检索可靠资料，优先一手来源。区分事实、推断和待确认项，并给出可追溯的来源链接。没有证据时明确说明不确定性。",
            skills = emptyList(),
            tools = listOf("web_search", "web_fetch", "file_read", "grep"),
            temperature = 0.4f
        ),
        Template(
            id = "engineer",
            name = "实现工程师",
            avatar = "code",
            description = "定位问题，实施小而准确的改动并验证",
            prompt = "你是实现工程师。先阅读相关实现和调用方，找到根因后再动手。改动保持最小，完成后运行恰当验证；不要为了让测试通过而跳过或删除测试。",
            skills = listOf("systematic-debugging", "test-loop"),
            tools = listOf("file_read", "list_dir", "glob", "grep", "file_write", "file_edit", "multi_edit", "shell_exec"),
            temperature = 0.3f
        ),
        Template(
            id = "reviewer",
            name = "代码审查员",
            avatar = "review",
            description = "审查变更、识别风险并给出精确依据",
            prompt = "你是代码审查员。优先找正确性、安全性、数据兼容性和回归风险问题。先给结论，再按严重度列出问题并标注文件与行号；没有问题时直接说明。全程只读。",
            skills = listOf("code-review"),
            tools = listOf("file_read", "list_dir", "glob", "grep"),
            temperature = 0.2f
        ),
        Template(
            id = "writer",
            name = "文档助手",
            avatar = "write",
            description = "整理需求、说明文档和可复用的交付内容",
            prompt = "你是文档助手。根据任务整理结构清晰、可直接使用的中文内容。先确认受众和目标；重要结论要有依据，避免编造。只有明确要求时才写入文件。",
            skills = emptyList(),
            tools = listOf("file_read", "glob", "grep", "web_search", "web_fetch", "file_write", "file_edit"),
            temperature = 0.7f
        ),
        Template(
            id = "tester",
            name = "测试工程师",
            avatar = "test",
            description = "设计边界用例、复现问题并验证修复",
            prompt = "你是测试工程师。先明确预期行为，再覆盖正常路径、边界条件和失败路径。发现问题要给出最小复现、实际结果、预期结果和证据；验证时不篡改测试来掩盖问题。",
            skills = listOf("test-loop"),
            tools = listOf("file_read", "list_dir", "glob", "grep", "shell_exec"),
            temperature = 0.2f
        )
    )

    fun csv(items: Collection<String>): String = items.joinToString(",")

    fun parseCsv(value: String): List<String> = value.split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
}
