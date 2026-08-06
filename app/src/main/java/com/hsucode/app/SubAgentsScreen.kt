@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.hsucode.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hsucode.data.AppDatabase
import com.hsucode.data.SkillEntity
import com.hsucode.data.SubAgentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class AgentDraft(
    val id: Long = 0,
    val name: String = "",
    val description: String = "",
    val prompt: String = "",
    val avatar: String = "agent",
    val skills: List<String> = emptyList(),
    val tools: List<String> = emptyList(),
    val enabled: Boolean = true,
    val temperature: Float = 0.7f,
    val builtin: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        fun from(agent: SubAgentEntity) = AgentDraft(
            id = agent.id,
            name = agent.name,
            description = agent.description,
            prompt = agent.systemPrompt,
            avatar = agent.avatar,
            skills = SubAgentCatalog.parseCsv(agent.skillNames),
            tools = SubAgentCatalog.parseCsv(agent.toolNames),
            enabled = agent.enabled,
            temperature = agent.temperature,
            builtin = agent.builtin,
            createdAt = agent.createdAt
        )

        fun from(template: SubAgentCatalog.Template) = AgentDraft(
            name = template.name,
            description = template.description,
            prompt = template.prompt,
            avatar = template.avatar,
            skills = template.skills,
            tools = template.tools,
            temperature = template.temperature
        )
    }

    fun toEntity(now: Long = System.currentTimeMillis()) = SubAgentEntity(
        id = id,
        name = name.trim(),
        description = description.trim(),
        systemPrompt = prompt.trim().ifBlank { "你是${name.trim()}。${description.trim()}" },
        avatar = avatar,
        skillNames = SubAgentCatalog.csv(skills),
        toolNames = SubAgentCatalog.csv(tools),
        enabled = enabled,
        temperature = temperature.coerceIn(0f, 2f),
        builtin = builtin,
        createdAt = createdAt,
        updatedAt = now
    )
}

private val avatarOptions = listOf(
    "agent" to "智",
    "plan" to "策",
    "search" to "研",
    "code" to "码",
    "review" to "审",
    "write" to "文",
    "test" to "测"
)

private fun avatarGlyph(key: String) = avatarOptions.firstOrNull { it.first == key }?.second ?: "智"

private fun avatarColor(key: String, colors: HsuColors): Color = when (key) {
    "plan" -> colors.yellow
    "search" -> colors.green
    "code" -> Color(0xFFB56BDF)
    "review" -> Color(0xFFE58F65)
    "write" -> Color(0xFFC6912D)
    "test" -> Color(0xFFD36272)
    else -> colors.ink
}

/**
 * Agent center. Configurations written here are used directly by [SubAgentTool],
 * including the tool allowlist, temperature and enabled state.
 */
@Composable
fun SubAgentsScreen(
    database: AppDatabase,
    onBack: () -> Unit,
    onRunAgent: (String, String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var agents by remember { mutableStateOf<List<SubAgentEntity>>(emptyList()) }
    var skills by remember { mutableStateOf<List<SkillEntity>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var tab by remember { mutableIntStateOf(0) }
    var draft by remember { mutableStateOf<AgentDraft?>(null) }
    var runTarget by remember { mutableStateOf<SubAgentEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<SubAgentEntity?>(null) }
    var feedback by remember { mutableStateOf("") }

    LaunchedEffect(reload) {
        val loaded = withContext(Dispatchers.IO) {
            Pair(
                runCatching { database.subAgentDao().getAll() }.getOrDefault(emptyList()),
                runCatching { database.skillDao().getAll() }.getOrDefault(emptyList())
            )
        }
        agents = loaded.first
        skills = loaded.second
    }

    val currentDraft = draft
    if (currentDraft != null) {
        AgentEditor(
            draft = currentDraft,
            skills = skills,
            onBack = { draft = null },
            onSave = { updated ->
                scope.launch {
                    val issue = withContext(Dispatchers.IO) {
                        val duplicate = database.subAgentDao().getByName(updated.name.trim())
                        when {
                            updated.name.isBlank() -> "请填写智能体名称"
                            updated.name.length > 40 -> "名称最多 40 个字符"
                            updated.description.length > 160 -> "职责说明最多 160 个字符"
                            updated.prompt.length > 12_000 -> "角色设定过长，请控制在 12,000 字符内"
                            duplicate != null && duplicate.id != updated.id -> "已存在同名智能体，请换一个名称"
                            else -> null
                        }
                    }
                    if (issue != null) {
                        feedback = issue
                        return@launch
                    }
                    withContext(Dispatchers.IO) { database.subAgentDao().upsert(updated.toEntity()) }
                    feedback = "已保存「${updated.name.trim()}」"
                    draft = null
                    reload++
                }
            }
        )
        return
    }

    val colors = LocalHsuColors.current
    Column(
        Modifier.fillMaxSize().background(colors.bg).verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text("返回", color = colors.sub)
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text("智能体中心", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                Text("把角色、能力边界和执行方式配置成可复用的工作单元", fontSize = 12.sp, color = colors.sub)
            }
            Button(
                onClick = { draft = AgentDraft() },
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.green, contentColor = colors.bg),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) { Text("新建") }
        }

        Spacer(Modifier.height(18.dp))
        AgentSummary(agents)
        Spacer(Modifier.height(18.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("我的智能体") })
            FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("专业模板") })
        }
        Spacer(Modifier.height(12.dp))

        if (feedback.isNotBlank()) {
            Text(feedback, fontSize = 12.sp, color = colors.green, modifier = Modifier.padding(bottom = 10.dp))
        }

        if (tab == 0) {
            if (agents.isEmpty()) {
                EmptyAgents(onCreate = { draft = AgentDraft() }, onTemplates = { tab = 1 })
            } else {
                agents.forEach { agent ->
                    AgentCard(
                        agent = agent,
                        onToggle = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    database.subAgentDao().upsert(agent.copy(enabled = !agent.enabled, updatedAt = System.currentTimeMillis()))
                                }
                                reload++
                            }
                        },
                        onEdit = {
                            draft = if (agent.builtin) {
                                AgentDraft.from(agent).copy(id = 0, builtin = false, name = "${agent.name}副本")
                            } else {
                                AgentDraft.from(agent)
                            }
                        },
                        onRun = { runTarget = agent },
                        onDelete = if (agent.builtin) null else { { deleteTarget = agent } }
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
        } else {
            Text("从模板开始", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
            Text("模板会创建为可编辑的自定义智能体，后续可按项目调整。", fontSize = 12.sp, color = colors.sub)
            Spacer(Modifier.height(10.dp))
            SubAgentCatalog.templates.forEach { template ->
                TemplateCard(template = template, onUse = { draft = AgentDraft.from(template) })
                Spacer(Modifier.height(10.dp))
            }
        }
    }

    runTarget?.let { agent ->
        RunAgentDialog(
            agent = agent,
            onDismiss = { runTarget = null },
            onRun = { task ->
                runTarget = null
                onRunAgent(agent.name, task)
            }
        )
    }

    deleteTarget?.let { agent ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除智能体") },
            text = { Text("删除「${agent.name}」不会影响已完成的执行记录。正在运行的任务不会自动停止。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { database.subAgentDao().deleteById(agent.id) }
                        deleteTarget = null
                        feedback = "已删除「${agent.name}」"
                        reload++
                    }
                }) { Text("删除", color = colors.red) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
            containerColor = colors.bg
        )
    }
}

@Composable
private fun AgentSummary(agents: List<SubAgentEntity>) {
    val colors = LocalHsuColors.current
    val active = agents.count { it.enabled }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.bgElevated).border(1.dp, colors.border, RoundedCornerShape(8.dp)).padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        SummaryItem("$active", "可调度")
        SummaryItem("${agents.size - active}", "已停用")
        SummaryItem("${agents.count { !it.builtin }}", "自定义")
    }
}

@Composable
private fun SummaryItem(value: String, label: String) {
    val colors = LocalHsuColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
        Text(label, fontSize = 11.sp, color = colors.sub)
    }
}

@Composable
private fun EmptyAgents(onCreate: () -> Unit, onTemplates: () -> Unit) {
    val colors = LocalHsuColors.current
    Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("还没有智能体", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = colors.ink)
        Text("从专业模板开始，或按你的工作流创建一个。", fontSize = 12.sp, color = colors.sub, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onTemplates) { Text("浏览模板") }
            Button(onClick = onCreate, shape = RoundedCornerShape(8.dp)) { Text("新建智能体") }
        }
    }
}

@Composable
private fun AgentCard(
    agent: SubAgentEntity,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onRun: () -> Unit,
    onDelete: (() -> Unit)?
) {
    val colors = LocalHsuColors.current
    val avatar = avatarColor(agent.avatar, colors)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.bgElevated)
            .border(1.dp, if (agent.enabled) colors.border else colors.faint.copy(alpha = .45f), RoundedCornerShape(8.dp)).padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(avatar.copy(alpha = .16f)),
                contentAlignment = Alignment.Center
            ) { Text(avatarGlyph(agent.avatar), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = avatar) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(agent.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                    if (agent.builtin) {
                        Text("内置", fontSize = 10.sp, color = colors.sub, modifier = Modifier.padding(start = 7.dp))
                    }
                }
                Text(if (agent.enabled) "可由主脑派发" else "已停用，不会被派发", fontSize = 11.sp, color = if (agent.enabled) colors.green else colors.sub)
            }
            Switch(checked = agent.enabled, onCheckedChange = { onToggle() })
        }
        if (agent.description.isNotBlank()) {
            Text(agent.description, fontSize = 13.sp, color = colors.sub, modifier = Modifier.padding(top = 12.dp))
        }
        AgentCapabilities(agent)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
            if (onDelete != null) TextButton(onClick = onDelete) { Text("删除", color = colors.red) }
            TextButton(onClick = onEdit) { Text(if (agent.builtin) "复制并编辑" else "编辑") }
            Button(
                enabled = agent.enabled,
                onClick = onRun,
                shape = RoundedCornerShape(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 7.dp)
            ) { Text("试运行") }
        }
    }
}

@Composable
private fun AgentCapabilities(agent: SubAgentEntity) {
    val colors = LocalHsuColors.current
    val skills = SubAgentCatalog.parseCsv(agent.skillNames)
    val tools = SubAgentCatalog.parseCsv(agent.toolNames)
    if (skills.isEmpty() && tools.isEmpty()) return
    FlowRow(
        modifier = Modifier.padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (skills.isNotEmpty()) CapabilityPill("${skills.size} 个技能", colors.green)
        if (tools.isNotEmpty()) CapabilityPill("${tools.size} 项工具", colors.sub)
        CapabilityPill("温度 ${String.format("%.1f", agent.temperature)}", colors.sub)
    }
}

@Composable
private fun CapabilityPill(label: String, color: Color) {
    Text(
        label,
        fontSize = 11.sp,
        color = color,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = .10f)).padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun TemplateCard(template: SubAgentCatalog.Template, onUse: () -> Unit) {
    val colors = LocalHsuColors.current
    val avatar = avatarColor(template.avatar, colors)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.bgElevated).border(1.dp, colors.border, RoundedCornerShape(8.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(8.dp)).background(avatar.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
            Text(avatarGlyph(template.avatar), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = avatar)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
            Text(template.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
            Text(template.description, fontSize = 12.sp, color = colors.sub)
        }
        TextButton(onClick = onUse) { Text("使用") }
    }
}

@Composable
private fun AgentEditor(
    draft: AgentDraft,
    skills: List<SkillEntity>,
    onBack: () -> Unit,
    onSave: (AgentDraft) -> Unit
) {
    val colors = LocalHsuColors.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var name by remember(draft) { mutableStateOf(draft.name) }
    var description by remember(draft) { mutableStateOf(draft.description) }
    var prompt by remember(draft) { mutableStateOf(draft.prompt) }
    var avatar by remember(draft) { mutableStateOf(draft.avatar) }
    var enabled by remember(draft) { mutableStateOf(draft.enabled) }
    var temperature by remember(draft) { mutableStateOf(draft.temperature) }
    var selectedSkills by remember(draft) { mutableStateOf(draft.skills.toSet()) }
    var selectedTools by remember(draft) { mutableStateOf(draft.tools.toSet()) }
    var expanding by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(colors.bg).verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("返回", color = colors.sub) }
            Text(if (draft.id == 0L) "新建智能体" else "编辑智能体", modifier = Modifier.weight(1f).padding(start = 10.dp), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
            Button(
                onClick = {
                    onSave(draft.copy(
                        name = name,
                        description = description,
                        prompt = prompt,
                        avatar = avatar,
                        enabled = enabled,
                        temperature = temperature,
                        skills = selectedSkills.sorted(),
                        tools = selectedTools.sorted()
                    ))
                },
                enabled = name.isNotBlank(),
                shape = RoundedCornerShape(8.dp)
            ) { Text("保存") }
        }

        Spacer(Modifier.height(20.dp))
        Text("基本信息", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
        Spacer(Modifier.height(8.dp))
        AgentField("名称", name, { name = it }, "例如：发布前检查", singleLine = true)
        AgentField("职责说明", description, { description = it }, "一句话描述何时派发给它", singleLine = true)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("允许主脑派发", fontSize = 14.sp, color = colors.ink)
                Text("停用后保留配置和历史，但不会被自动选择。", fontSize = 11.sp, color = colors.sub)
            }
            Switch(checked = enabled, onCheckedChange = { enabled = it })
        }

        Spacer(Modifier.height(20.dp))
        Text("头像标识", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            avatarOptions.forEach { (key, glyph) ->
                val selected = avatar == key
                val color = avatarColor(key, colors)
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (selected) color.copy(alpha = .18f) else colors.bgElevated)
                        .border(1.dp, if (selected) color else colors.border, RoundedCornerShape(8.dp))
                        .clickable { avatar = key },
                    contentAlignment = Alignment.Center
                ) { Text(glyph, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = color) }
            }
        }

        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("角色设定", modifier = Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
            TextButton(
                enabled = !expanding && (name.isNotBlank() || prompt.isNotBlank()),
                onClick = {
                    val seed = prompt.ifBlank { "$name：$description" }
                    expanding = true
                    scope.launch {
                        val app = context.applicationContext as HsucodeApplication
                        val result = PromptExpander.expand(
                            app.database, app.keystore, PromptExpander.Kind.IDENTITY, seed, selectedSkills.toList()
                        )
                        result.onSuccess { prompt = it }
                        result.onFailure { prompt = prompt.ifBlank { "你是$name。$description" } }
                        expanding = false
                    }
                }
            ) { Text(if (expanding) "扩展中" else "扩展设定") }
        }
        Text("明确角色边界、产出格式和停止条件。扩展功能会以当前名称和职责生成可编辑初稿。", fontSize = 12.sp, color = colors.sub)
        Spacer(Modifier.height(7.dp))
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
            placeholder = { Text("描述角色负责什么、不能做什么、如何交付结果") },
            colors = agentTextFieldColors(colors)
        )

        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("回答稳定性", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
            Text(String.format("%.1f", temperature), fontSize = 14.sp, color = colors.green, fontFamily = FontFamily.Monospace)
        }
        Text("低值更适合审查和执行；高值更适合发散、写作和构思。", fontSize = 12.sp, color = colors.sub)
        Slider(value = temperature, onValueChange = { temperature = (it * 10).toInt() / 10f }, valueRange = 0f..2f, steps = 19)

        Spacer(Modifier.height(16.dp))
        Text("可用技能", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
        Text("仅列出已安装技能；执行时智能体仍需自行调用所选技能。", fontSize = 12.sp, color = colors.sub)
        Spacer(Modifier.height(8.dp))
        if (skills.isEmpty()) {
            Text("尚未安装技能，可在设置的技能页添加。", fontSize = 12.sp, color = colors.sub)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                skills.forEach { skill ->
                    FilterChip(
                        selected = skill.name in selectedSkills,
                        onClick = {
                            selectedSkills = if (skill.name in selectedSkills) selectedSkills - skill.name else selectedSkills + skill.name
                        },
                        label = { Text(skill.name) }
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("工具权限", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
        Text("只提供此处选择的工具。写文件和执行命令仍会遵循全局审批策略。", fontSize = 12.sp, color = colors.sub)
        Spacer(Modifier.height(8.dp))
        SubAgentCatalog.toolOptions.forEach { option ->
            val selected = option.id in selectedTools
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                    selectedTools = if (selected) selectedTools - option.id else selectedTools + option.id
                }.padding(vertical = 8.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Switch(checked = selected, onCheckedChange = {
                    selectedTools = if (it) selectedTools + option.id else selectedTools - option.id
                })
                Column(Modifier.padding(start = 12.dp)) {
                    Text(option.label, fontSize = 14.sp, color = colors.ink)
                    Text(option.detail, fontSize = 11.sp, color = colors.sub, fontFamily = FontFamily.Monospace)
                }
            }
            HorizontalDivider(color = colors.border.copy(alpha = .65f))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AgentField(label: String, value: String, onValueChange: (String) -> Unit, hint: String, singleLine: Boolean) {
    val colors = LocalHsuColors.current
    Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = colors.ink, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(hint) },
        singleLine = singleLine,
        colors = agentTextFieldColors(colors)
    )
}

@Composable
private fun agentTextFieldColors(colors: HsuColors) = TextFieldDefaults.colors(
    focusedContainerColor = colors.bgElevated,
    unfocusedContainerColor = colors.bgElevated,
    focusedTextColor = colors.ink,
    unfocusedTextColor = colors.ink,
    focusedIndicatorColor = colors.green,
    unfocusedIndicatorColor = colors.border,
    cursorColor = colors.green
)

@Composable
private fun RunAgentDialog(agent: SubAgentEntity, onDismiss: () -> Unit, onRun: (String) -> Unit) {
    val colors = LocalHsuColors.current
    var task by remember(agent.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("试运行 ${agent.name}") },
        text = {
            Column {
                Text("任务会使用该智能体的角色设定、技能、工具白名单与权限审批。", fontSize = 12.sp, color = colors.sub)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = task, onValueChange = { task = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp), placeholder = { Text("输入要交给它完成的具体任务") }, colors = agentTextFieldColors(colors))
            }
        },
        confirmButton = { Button(enabled = task.isNotBlank(), onClick = { onRun(task) }, shape = RoundedCornerShape(8.dp)) { Text("开始运行") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        containerColor = colors.bg
    )
}
