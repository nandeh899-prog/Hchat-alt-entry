package com.aeonlab.toolkit.hooks.items.groupadmin

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.miuix.ActionRow
import com.aeonlab.toolkit.ui.miuix.BottomActionBar
import com.aeonlab.toolkit.ui.miuix.InputRow
import com.aeonlab.toolkit.ui.miuix.PageScaffold
import com.aeonlab.toolkit.ui.miuix.SettingsCard
import com.aeonlab.toolkit.ui.miuix.SwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.theme.MiuixTheme

import android.widget.Toast
import androidx.compose.foundation.lazy.items
import com.aeonlab.toolkit.hooks.items.conversationgroup.ConversationGroupPickerSupport
import com.aeonlab.toolkit.hooks.items.groupleave.GroupLeaveMonitorSettings
import com.aeonlab.toolkit.hooks.items.groupleave.GroupMemberMediaOrder
import com.aeonlab.toolkit.ui.miuix.ContactListCard
import com.aeonlab.toolkit.ui.miuix.ContactOption
import com.aeonlab.toolkit.ui.miuix.PickerSectionHeader
import com.aeonlab.toolkit.ui.miuix.PopupChoice
import com.aeonlab.toolkit.ui.miuix.GroupMemberMediaOrderDialog
import com.aeonlab.toolkit.ui.miuix.PopupChoiceRow

private enum class GroupAdminRoute { ROOT, GROUP, RULE, TEMPLATES, TEMPLATE, LIST, PICK_MEMBERS, PICK_GROUPS, TEST_PICK, REPORT, WARNS }

private enum class GroupAdminPickMode { ADD, TEST }

/** 黑名单时长循环：永久 → 1 小时 → 1 天 → 7 天。 */
private val BLACK_HOURS = listOf(0, 1, 24, 168)

@Composable
internal fun GroupAdminMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val title = provider.title().ifBlank { "群管理助手" }
    var route by remember { mutableStateOf(GroupAdminRoute.ROOT) }
    var room by remember { mutableStateOf("") }
    var listKind by remember { mutableStateOf(GroupAdminList.ADMIN) }
    var query by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var enabled by remember { mutableStateOf(GroupAdminSettings.isEnabled(context)) }
    var tick by remember { mutableStateOf(0) }
    var hint by remember { mutableStateOf("") }
    var reportTitle by remember { mutableStateOf("") }
    var ruleId by remember { mutableStateOf(GroupAdminRuleCatalog.LONG) }
    var ruleTemplateId by remember { mutableStateOf("") }
    // 5.5.30 修「警告次数管理页返回回退到本群规则设置」：该页原来 onBack 硬编码回 GROUP，
    // 而入口在首页 ROOT。改为记住进入前的来源路由，返回到来源页。
    var warnReturn by remember { mutableStateOf(GroupAdminRoute.ROOT) }
    var templateId by remember { mutableStateOf("") }
    var lastRuleId by remember { mutableStateOf("") }
    val groupListState = rememberLazyListState()
    val listListState = rememberLazyListState()
    val memberListState = rememberLazyListState()
    val pickGroupsListState = rememberLazyListState()
    val templatesListState = rememberLazyListState()
    val templateEditListState = rememberLazyListState()
    var reportLines by remember { mutableStateOf<List<String>>(emptyList()) }

    when (route) {
        GroupAdminRoute.ROOT -> GroupAdminRootPage(
            context = context,
            title = title,
            enabled = enabled,
            tick = tick,
            onToggle = { value ->
                enabled = value
                GroupAdminSettings.setEnabled(context, value)
            },
            onOpenGroup = { target ->
                room = target
                query = ""
                input = ""
                hint = ""
                route = GroupAdminRoute.GROUP
            },
            onAddGroup = { route = GroupAdminRoute.PICK_GROUPS },
            onOpenTemplates = { route = GroupAdminRoute.TEMPLATES },
            onOpenWarns = {
                warnReturn = route
                route = GroupAdminRoute.WARNS
            },
            onBack = onBack
        )

        GroupAdminRoute.GROUP -> GroupAdminGroupPage(
            listState = groupListState,
            lastRuleId = lastRuleId,
            context = context,
            room = room,
            tick = tick,
            hint = hint,
            onHint = { hint = it },
            onChanged = { tick++ },
            onOpenList = { kind ->
                listKind = kind
                query = ""
                input = ""
                route = GroupAdminRoute.LIST
            },
            onPickMembers = { route = GroupAdminRoute.PICK_MEMBERS },
            onPickTest = {
                query = ""
                selected = emptySet()
                route = GroupAdminRoute.TEST_PICK
            },
            onReport = { lines, name ->
                reportLines = lines
                reportTitle = name
                route = GroupAdminRoute.REPORT
            },
            onOpenRule = { id ->
                ruleId = id
                ruleTemplateId = ""
                lastRuleId = id
                hint = ""
                route = GroupAdminRoute.RULE
            },
            onForget = {
                GroupAdminStore.forgetGroup(context, room)
                room = ""
                route = GroupAdminRoute.ROOT
            },
            onBack = {
                route = GroupAdminRoute.ROOT
                tick++
            }
        )

        GroupAdminRoute.RULE -> GroupAdminRulePage(
            context = context,
            room = room,
            ruleId = ruleId,
            templateId = ruleTemplateId,
            tick = tick,
            onChanged = { tick++ },
            onBack = {
                route = if (ruleTemplateId.isNotBlank()) GroupAdminRoute.TEMPLATE else GroupAdminRoute.GROUP
                tick++
            }
        )

        GroupAdminRoute.TEMPLATES -> GroupAdminTemplatesPage(
            context = context,
            tick = tick,
            listState = templatesListState,
            onChanged = { tick++ },
            onOpenTemplate = { id ->
                templateId = id
                route = GroupAdminRoute.TEMPLATE
            },
            onBack = { route = GroupAdminRoute.ROOT }
        )

        GroupAdminRoute.TEMPLATE -> GroupAdminTemplateEditPage(
            context = context,
            templateId = templateId,
            tick = tick,
            listState = templateEditListState,
            onChanged = { tick++ },
            onOpenRule = { id ->
                ruleId = id
                ruleTemplateId = templateId
                lastRuleId = id
                route = GroupAdminRoute.RULE
            },
            onBack = {
                route = GroupAdminRoute.TEMPLATES
                tick++
            }
        )

        GroupAdminRoute.LIST -> GroupAdminListPage(
            listState = listListState,
            context = context,
            room = room,
            kind = listKind,
            query = query,
            input = input,
            tick = tick,
            onQuery = { query = it },
            onInput = { input = it },
            onChanged = { tick++ },
            onPickMembers = { route = GroupAdminRoute.PICK_MEMBERS },
            onBack = {
                route = GroupAdminRoute.GROUP
                tick++
            }
        )

        GroupAdminRoute.PICK_MEMBERS -> GroupAdminPickMembersPage(
            listState = memberListState,
            context = context,
            room = room,
            kind = listKind,
            mode = GroupAdminPickMode.ADD,
            query = query,
            selected = selected,
            onQuery = { query = it },
            onSelected = { selected = it },
            onConfirm = { picked ->
                GroupAdminStore.update(context, room) { cfg ->
                    val target = GroupAdminStore.listOf(cfg, listKind)
                    for (id in picked) {
                        val ref = GroupAdminMemberCache.members(room).firstOrNull { it.id == id } ?: continue
                        GroupAdminStore.addMember(cfg, target, id, ref.name)
                    }
                }
                selected = emptySet()
                route = GroupAdminRoute.LIST
                tick++
            },
            onBack = { route = GroupAdminRoute.LIST }
        )

        GroupAdminRoute.TEST_PICK -> GroupAdminPickMembersPage(
            listState = memberListState,
            context = context,
            room = room,
            kind = listKind,
            mode = GroupAdminPickMode.TEST,
            query = query,
            selected = selected,
            onQuery = { query = it },
            onSelected = { selected = it },
            onConfirm = { picked ->
                val list = picked.toList()
                selected = emptySet()
                if (list.isEmpty()) {
                    route = GroupAdminRoute.GROUP
                } else {
                    val cfg = GroupAdminStore.group(context, room)
                    val lines = ArrayList<String>()
                    list.forEachIndexed { index, id ->
                        if (index > 0) lines.add("")
                        lines.add("—— 目标 " + (index + 1) + "/" + list.size + " ——")
                        lines.addAll(GroupAdminRoleInspector.report(context, cfg, room, id))
                    }
                    reportLines = lines
                    reportTitle = "只读试跑：豁免/角色判定"
                    route = GroupAdminRoute.REPORT
                }
            },
            onBack = { route = GroupAdminRoute.GROUP }
        )

        GroupAdminRoute.REPORT -> GroupAdminReportPage(
            title = reportTitle.ifBlank { "只读判定结果" },
            lines = reportLines,
            onBack = { route = GroupAdminRoute.GROUP }
        )

        GroupAdminRoute.WARNS -> GroupAdminWarnPage(
            context = context,
            onBack = { route = warnReturn }
        )

        GroupAdminRoute.PICK_GROUPS -> GroupAdminPickGroupsPage(
            listState = pickGroupsListState,
            context = context,
            onSaved = { picked ->
                picked.forEach { GroupAdminStore.rememberGroup(context, it.first, it.second) }
                query = ""
                input = ""
                hint = ""
                route = GroupAdminRoute.ROOT
                tick++
            },
            onBack = {
                route = GroupAdminRoute.ROOT
                tick++
            }
        )
    }
}

@Composable
private fun GroupAdminRootPage(
    context: Context,
    title: String,
    enabled: Boolean,
    tick: Int,
    onToggle: (Boolean) -> Unit,
    onOpenGroup: (String) -> Unit,
    onAddGroup: () -> Unit,
    onOpenTemplates: () -> Unit,
    onOpenWarns: () -> Unit,
    onBack: () -> Unit
) {
    val scrollBehavior = MiuixScrollBehavior()
    val listState = rememberLazyListState()
    val groups = remember(tick, enabled) { GroupAdminStore.entries(context) }
    val warnTotal = remember(tick, enabled) { GroupAdminStore.warnEntries(context).size }
    var showCmdHelp by remember { mutableStateOf(false) }
    PageScaffold(
        title = title,
        largeTitle = title,
        scrollBehavior = scrollBehavior,
        bottomBar = { BottomActionBar("添加群聊", onAddGroup, secondaryText = "返回", onSecondaryClick = onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "总开关") }
            item {
                SettingsCard {
                    SwitchRow(
                        checked = enabled,
                        title = "启用群管理助手",
                        summary = "关闭后所有按群配置都不生效；本轮只有名单、豁免判定与开关生效",
                        onCheckedChange = onToggle
                    )
                }
            }
            item { SmallTitle(text = "模板") }
            item {
                SettingsCard {
                    ActionRow(
                        title = "模板",
                        summary = "共 " + GroupAdminTemplateStore.list(context).size +
                            " 个模板；群页「套用模板」即用它，套用后群内逐条规则锁定",
                        onClick = onOpenTemplates
                    )
                    ActionRow(
                        title = "警告次数管理",
                        summary = "当前 " + warnTotal + " 条计数（每条 = 群 + 成员 + 类型）。" +
                            "可查看/单项清除/全部清除；清除只动警告计数，不影响黑名单与规则配置",
                        onClick = onOpenWarns
                    )
                }
            }
            item { SmallTitle(text = "指令说明") }
            item {
                SettingsCard {
                    ActionRow(
                        title = "指令说明",
                        summary = if (showCmdHelp) "聊天指令全文已展开，点这里收起"
                        else "聊天指令的写法、权限分级与注意事项；点开查看全文",
                        onClick = { showCmdHelp = !showCmdHelp }
                    )
                    if (showCmdHelp) {
                        GroupAdminCommandHelp.lines.forEach { Note(it) }
                    }
                }
            }
            item { SmallTitle(text = "已纳管群") }
            if (groups.isEmpty()) {
                item { NoteCard("还没有纳管任何群。点下方「添加群聊」，选一个群后打开它的「纳入管理」。") }
            } else {
                item {
                    SettingsCard {
                        groups.forEachIndexed { index, entry ->
                            if (index > 0) RowSpacer()
                            val config = GroupAdminStore.group(context, entry.room)
                            MemberRow(
                                title = entry.name.ifBlank { entry.room },
                                subtitle = entry.room + "\n" + listBrief(config),
                                onClick = { onOpenGroup(entry.room) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupAdminGroupPage(
    context: Context,
    room: String,
    tick: Int,
    hint: String,
    onHint: (String) -> Unit,
    onChanged: () -> Unit,
    onOpenList: (GroupAdminList) -> Unit,
    onPickMembers: () -> Unit,
    onPickTest: () -> Unit,
    onReport: (List<String>, String) -> Unit,
    onForget: () -> Unit,
    onBack: () -> Unit,
    onOpenRule: (String) -> Unit,
    listState: LazyListState,
    lastRuleId: String,
) {
    val scrollBehavior = MiuixScrollBehavior()
    var clearArmed by remember(room) { mutableStateOf(false) }
    val config = remember(room, tick) { GroupAdminStore.group(context, room) }
    val groupName = remember(room, tick) { GroupAdminMemberCache.groupName(room).ifBlank { room } }
    val members = remember(room, tick) { GroupAdminMemberCache.members(room) }
    val stat = remember(room, tick) { GroupAdminMemberCache.stat(room) }
    val longRule = remember(room, tick) { GroupAdminRuleCatalog.ruleOf(config, GroupAdminRuleCatalog.LONG) }
    // InputRow 是完全受控组件（内部没有草稿状态，直接把 value 交给 BasicTextField），
    // 所以显示值必须来自本页自己的可变状态：原来直接把 remember(room, tick) 的存储快照当 value，
    // 编辑引发的重组会把输入内容盖回旧值，真机表现就是「阈值/警告次数/拉黑时长改不动」。
    // 草稿只在切群（room 变化）时从存储重建；退出群页重进会重新读存储，所以「重进是否保留」可验证。
    val initialLimit = remember(room) {
        GroupAdminRuleCatalog.paramInt(
            config,
            GroupAdminRuleCatalog.LONG,
            GroupAdminRuleCatalog.PARAM_LONG_LIMIT,
            GroupAdminRuleCatalog.DEFAULT_LONG_LIMIT
        ).toString()
    }
    var farewellDraft by remember(room) { mutableStateOf(config.farewell) }
    var longLimitDraft by remember(room) { mutableStateOf(initialLimit) }
    var longReasonDraft by remember(room) { mutableStateOf(longRule.reason) }
    var warnCountDraft by remember(room) { mutableStateOf(longRule.warnCount.toString()) }
    var warnTextDraft by remember(room) { mutableStateOf(longRule.warnText) }
    var unbanHoursDraft by remember(room) { mutableStateOf(longRule.unbanHours.toString()) }
    // 进群验证草稿：字段与存储键同名，编辑即写盘（saveConfig），与其它区块一致。
    var verifyDraft by remember(room, tick) { mutableStateOf(GroupAdminVerify.config(context, room)) }
    // 存下来真正生效的值，直接写进 summary，编辑时立刻可见，真机验证不必靠猜
    val longLimitInEffect = remember(room, tick) {
        GroupAdminRuleCatalog.paramInt(
            config,
            GroupAdminRuleCatalog.LONG,
            GroupAdminRuleCatalog.PARAM_LONG_LIMIT,
            GroupAdminRuleCatalog.DEFAULT_LONG_LIMIT
        )
    }
    val warnCountInEffect = remember(room, tick) { longRule.warnCount.coerceAtLeast(1) }
    val unbanHoursInEffect = remember(room, tick) { longRule.unbanHours }

    LaunchedEffect(room) {
        // 5.5.18 修：进入群页时缓存过期就重读（第 14 条），不再依赖重启微信
        if (room.isNotEmpty() && GroupAdminMemberCache.isStale(room)) {
            GroupAdminMemberCache.refreshAsync(room) { onChanged() }
        }
    }

    PageScaffold(
        title = groupName,
        largeTitle = groupName,
        scrollBehavior = scrollBehavior,
        bottomBar = { BottomActionBar("返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "本群") }
            item { NoteCard(room) }
            item {
                SettingsCard {
                    SwitchRow(
                        checked = config.master,
                        title = "纳入管理",
                        summary = "打开后本群才按这些配置处理，其它群不受影响",
                        onCheckedChange = { value ->
                            GroupAdminStore.setMaster(context, room, value)
                            onChanged()
                        }
                    )
                    RowSpacer()
                    SwitchRow(
                        checked = config.wlMaster,
                        title = "白名单豁免",
                        summary = "关闭后白名单成员不再被豁免（管理员与代理不受影响）",
                        onCheckedChange = { value ->
                            GroupAdminStore.update(context, room) { it.wlMaster = value }
                            onChanged()
                        }
                    )
                    RowSpacer()
                    SwitchRow(
                        checked = config.allowAdminCmd,
                        title = "允许管理员指令",
                        summary = "关闭后管理员发指令提示无权限（群主与代理不受影响）",
                        onCheckedChange = { value ->
                            GroupAdminStore.update(context, room) { it.allowAdminCmd = value }
                            onChanged()
                        }
                    )
                    RowSpacer()
                    SwitchRow(
                        checked = config.allowAgentCmd,
                        title = "允许代理指令",
                        summary = "关闭后代理群主发指令提示无权限（群主不受影响）",
                        onCheckedChange = { value ->
                            GroupAdminStore.update(context, room) { it.allowAgentCmd = value }
                            onChanged()
                        }
                    )
                    RowSpacer()
                    SwitchRow(
                        checked = config.agentManageAdmin,
                        title = "代理可任免管理员",
                        summary = "关闭后代理群主不能用 #um / #dm 任免管理员（群主不受影响）",
                        onCheckedChange = { value ->
                            GroupAdminStore.update(context, room) { it.agentManageAdmin = value }
                            onChanged()
                        }
                    )
                    RowSpacer()
                    SwitchRow(
                        checked = config.notify,
                        title = "动作后在群里提示",
                        summary = "踢人前先发告别语（@目标）；关掉后只踢不发。已生效：命中规则或黑名单时按此开关决定是否先发言",
                        onCheckedChange = { value ->
                            GroupAdminStore.update(context, room) { it.notify = value }
                            onChanged()
                        }
                    )
                    RowSpacer()
                    InputRow(
                        title = "告别语",
                        summary = "踢人前发的默认告别语，后面拼该规则的理由。已生效",
                        value = farewellDraft,
                        onValueChange = { value ->
                            farewellDraft = value
                            GroupAdminStore.update(context, room) { it.farewell = value }
                        }
                    )
                }
            }
            item { SmallTitle(text = "成员") }
            item {
                SettingsCard {
                    ActionRow(
                        title = "刷新群成员并结算",
                        summary = "读取成员、把名单里的昵称回填为当前群昵称，并清理到期黑名单",
                        onClick = {
                            GroupAdminMemberCache.refreshAsync(room) { list ->
                                val result = GroupAdminStore.settle(
                                    GroupAdminStore.group(context, room),
                                    list,
                                    System.currentTimeMillis()
                                )
                                GroupAdminStore.save(context, GroupAdminStore.group(context, room))
                                onHint(
                                    "已读取 " + list.size + " 人：新解析 " + result.first +
                                        " 条、回填昵称 " + result.second + " 条、清理到期 " + result.third + " 条"
                                )
                                onChanged()
                            }
                        }
                    )
                    RowSpacer()
                    Note(memberSummary(members.size, stat))
                }
            }
            if (hint.isNotBlank()) {
                item { NoteCard(hint) }
            }
            item { SmallTitle(text = "名单") }
            item {
                SettingsCard {
                    GroupAdminList.values().forEachIndexed { index, kind ->
                        if (index > 0) RowSpacer()
                        ActionRow(
                            title = kind.title,
                            summary = GroupAdminStore.summaryOf(config, kind, System.currentTimeMillis()),
                            onClick = { onOpenList(kind) }
                        )
                    }
                }
            }
            item { SmallTitle(text = "自动规则（24 条，按判定顺序）") }
            item {
                SettingsCard {
                    Note(
                        "顺序即优先级：命中即停。前 20 条按消息判定链排序；后 4 条由系统消息与群成员变更触发" +
                            "（拍一拍 / 改群名 / 非管理员拉人 / 退群拉黑）。点按任一条进入它的配置页。"
                    )
                    val boundTemplateId = config.templateId
                    val boundTemplate =
                        if (boundTemplateId.isBlank()) null else GroupAdminTemplateStore.get(context, boundTemplateId)
                    PopupChoiceRow(
                        title = "套用模板",
                        summary = if (boundTemplate != null) GroupAdminTemplates.appliedSummary(boundTemplate)
                        else "当前为单独设置（未套用模板）",
                        options = listOf(PopupChoice("不套用（改为单独设置）", "")) +
                            GroupAdminTemplateStore.list(context).map { PopupChoice(it.name, it.id) },
                        currentValue = boundTemplateId,
                        onValueChanged = { id ->
                            if (id.isBlank()) {
                                GroupAdminTemplateStore.unbind(context, room)
                                onHint("已改为单独设置：保留本群当前配置值")
                            } else {
                                GroupAdminTemplateStore.applyToGroup(context, room, id)
                                onHint("已套用模板「" + (GroupAdminTemplateStore.get(context, id)?.name ?: id) + "」：本群规则与媒体已按模板覆盖")
                            }
                            onChanged()
                        }
                    )
                    RowSpacer()
                    if (boundTemplate != null) {
                        Note(
                            "已套用模板「" + boundTemplate.name + "」：逐条规则已锁定。要改就回「模板」页改完再点「同步到已套用的群」，" +
                                "或者把上面这项改成「不套用（改为单独设置）」后单独调（保留当前值）。"
                        )
                        RowSpacer()
                    }
                    if (lastRuleId.isNotBlank()) {
                        val lastIndex = GroupAdminRuleCatalog.DISPLAY_ORDER.indexOf(lastRuleId) + 1
                        Note("上次编辑：第 " + lastIndex + " 条「" + (GroupAdminRuleCatalog.spec(lastRuleId)?.title ?: lastRuleId) + "」")
                        RowSpacer()
                    }
                    GroupAdminRuleCatalog.DISPLAY_ORDER.forEachIndexed { index, id ->
                        if (index > 0) RowSpacer()
                        val spec = GroupAdminRuleCatalog.spec(id)
                        ActionRow(
                            title = (index + 1).toString() + ". " + (spec?.title ?: id) + "（" + id + "）",
                            summary = ruleSummary(id, GroupAdminRuleCatalog.ruleOf(config, id)) +
                                (if (boundTemplate != null) " · 已锁定（模板）" else ""),
                            onClick = {
                                if (boundTemplate != null) {
                                    onHint("已套用模板「" + boundTemplate.name + "」：请到「模板」页修改后同步，或用上面的「套用模板」改为单独设置")
                                } else {
                                    onOpenRule(id)
                                }
                            }
                        )
                    }
                    RowSpacer()
                    ActionRow(
                        title = "运行期状态与规则参数",
                        summary = "确认消息入口是否已接上、双重前提是否满足、最近处理了什么",
                        onClick = {
                            onReport(
                                runtimeStatusLines(context, room, GroupAdminStore.group(context, room)),
                                "运行期状态（消息入口与规则）"
                            )
                        }
                    )
                }
            }
            item { SmallTitle(text = "只读校准（不踢人、不发消息）") }
            item {
                SettingsCard {
                    ActionRow(
                        title = "选成员试跑判定",
                        summary = "输出该成员的角色（getMemberRole / getMemberInviter）、豁免链、黑名单命中与将执行的步骤",
                        onClick = {
                            if (members.isEmpty()) {
                                onHint("正在读取群成员，读完再试跑；也可以先点上面的「刷新群成员并结算」")
                                GroupAdminMemberCache.refreshAsync(room) { onChanged() }
                            }
                            onPickTest()
                        }
                    )
                    RowSpacer()
                    ActionRow(
                        title = "扫描本群名单结果",
                        summary = "谁会豁免、谁在黑名单、有多少条已到期 / 待确认（全只读）",
                        onClick = {
                            onReport(
                                GroupAdminRoleInspector.scan(context, GroupAdminStore.group(context, room), room),
                                "只读扫描：本群名单结果"
                            )
                        }
                    )
                }
            }
            item { SmallTitle(text = "进群验证") }
            item {
                SettingsCard {
                    SwitchRow(
                        checked = verifyDraft.enable,
                        title = "启用进群验证",
                        summary = if (verifyDraft.enable)
                            "新人入群后按题库提问，答对才放行；与「纳入管理」互相独立"
                        else "关闭时不提问（本群仍可按自动规则管理）",
                        onCheckedChange = { value ->
                            verifyDraft = verifyDraft.copy(enable = value)
                            GroupAdminVerify.saveConfig(context, room, verifyDraft)
                            onChanged()
                        }
                    )
                    RowSpacer()
                    InputRow(
                        title = "题库",
                        summary = "格式：问题,答案;问题,答案，多条用分号隔开；答案要写成员会打出的原文，" +
                            "纯数字题就只填数字（别写完整算式、中文或全角）；当前解析出 " +
                            GroupAdminVerify.questions(verifyDraft).size + " 题",
                        value = verifyDraft.questionList,
                        minLines = 3,
                        onValueChange = { value ->
                            verifyDraft = verifyDraft.copy(questionList = value)
                            GroupAdminVerify.saveConfig(context, room, verifyDraft)
                        }
                    )
                    questionListNotes(verifyDraft.questionList)?.let { notes ->
                        RowSpacer()
                        Note(notes)
                    }
                    RowSpacer()
                    InputRow(
                        title = "答错上限（次）",
                        summary = "答错达到该次数即移出（只填数字，建议 2-5；小于 1 按 1 处理）；已生效：" + verifyDraft.maxErr + " 次",
                        value = verifyDraft.maxErr.toString(),
                        onValueChange = { value ->
                            val digits = value.filter { it.isDigit() }.take(5)
                            verifyDraft = verifyDraft.copy(maxErr = digits.toIntOrNull()?.coerceAtLeast(1) ?: 1)
                            GroupAdminVerify.saveConfig(context, room, verifyDraft)
                        }
                    )
                    RowSpacer()
                    InputRow(
                        title = "超时（秒）",
                        summary = "单位：秒；0 表示不限时，超时未完成验证将自动移出（只填数字，最多 5 位）；已生效：" + verifyDraft.timeLimit + " 秒",
                        value = verifyDraft.timeLimit.toString(),
                        onValueChange = { value ->
                            val digits = value.filter { it.isDigit() }.take(5)
                            verifyDraft = verifyDraft.copy(timeLimit = digits.toIntOrNull() ?: 0)
                            GroupAdminVerify.saveConfig(context, room, verifyDraft)
                        }
                    )
                }
            }
            item { SmallTitle(text = "验证文案（五段）") }
            item {
                SettingsCard {
                    Note(
                        "占位符：%name% 昵称、%ques% 题目、%maxerr% 答错上限、%limit% 限时说明" +
                            "（不限时为空）、%now% 已答错次数、%max% 答错上限、%sec% 限时秒数、" +
                            "%min% 限时分钟数（用于超时文案）；只替换这几处用到的占位符，" +
                            "写错或用不到的会原样发出，所以别写用不到的占位符。"
                    )
                    RowSpacer()
                    InputRow(
                        title = "入群提问",
                        summary = "新人入群时的第一段话（可用占位符）",
                        value = verifyDraft.textWelcome,
                        minLines = 3,
                        onValueChange = { value ->
                            verifyDraft = verifyDraft.copy(textWelcome = value)
                            GroupAdminVerify.saveConfig(context, room, verifyDraft)
                        }
                    )
                    RowSpacer()
                    InputRow(
                        title = "答错换题",
                        summary = "答错但未到上限时发的文案",
                        value = verifyDraft.textWrong,
                        minLines = 2,
                        onValueChange = { value ->
                            verifyDraft = verifyDraft.copy(textWrong = value)
                            GroupAdminVerify.saveConfig(context, room, verifyDraft)
                        }
                    )
                    RowSpacer()
                    InputRow(
                        title = "验证通过",
                        summary = "答对后发的文案",
                        value = verifyDraft.textPass,
                        minLines = 2,
                        onValueChange = { value ->
                            verifyDraft = verifyDraft.copy(textPass = value)
                            GroupAdminVerify.saveConfig(context, room, verifyDraft)
                        }
                    )
                    RowSpacer()
                    InputRow(
                        title = "答错移出",
                        summary = "答错到上限移出时发的文案（答错上限用 %max%）",
                        value = verifyDraft.textKick,
                        minLines = 2,
                        onValueChange = { value ->
                            verifyDraft = verifyDraft.copy(textKick = value)
                            GroupAdminVerify.saveConfig(context, room, verifyDraft)
                        }
                    )
                    RowSpacer()
                    InputRow(
                        title = "超时移出",
                        summary = "超时移出时发的文案（限时秒数用 %sec%）",
                        value = verifyDraft.textTimeout,
                        minLines = 2,
                        onValueChange = { value ->
                            verifyDraft = verifyDraft.copy(textTimeout = value)
                            GroupAdminVerify.saveConfig(context, room, verifyDraft)
                        }
                    )
                }
            }
            item { SmallTitle(text = "纳管") }
            item {
                SettingsCard {
                    ActionRow(
                        title = "从成员列表选择",
                        summary = "把成员批量加入当前名单（先返回名单页选定是哪一类）",
                        onClick = onPickMembers
                    )
                    RowSpacer()
                    ActionRow(
                        title = "从已纳管群移除",
                        summary = if (clearArmed) "再点一次确认：只移出面板登记，群内配置保留" else "点两次才会执行，防误触",
                        onClick = {
                            if (clearArmed) {
                                clearArmed = false
                                onForget()
                            } else {
                                clearArmed = true
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupAdminListPage(
    context: Context,
    room: String,
    kind: GroupAdminList,
    query: String,
    input: String,
    tick: Int,
    onQuery: (String) -> Unit,
    onInput: (String) -> Unit,
    onChanged: () -> Unit,
    onPickMembers: () -> Unit,
    onBack: () -> Unit,
    listState: LazyListState,
) {
    val scrollBehavior = MiuixScrollBehavior()
    var clearArmed by remember(room, kind) { mutableStateOf(false) }
    val config = remember(room, tick) { GroupAdminStore.group(context, room) }
    val stored = remember(room, kind, tick) { GroupAdminStore.listOf(config, kind).toList() }
    val filtered = remember(stored, query) { GroupAdminStore.search(stored, query) }
    val now = System.currentTimeMillis()

    PageScaffold(
        title = kind.title,
        largeTitle = kind.title,
        scrollBehavior = scrollBehavior,
        bottomBar = {
            BottomActionBar(
                primaryText = "从成员列表选择",
                onPrimaryClick = onPickMembers,
                secondaryText = "返回",
                onSecondaryClick = onBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { NoteCard(kind.hint) }
            item {
                SettingsCard {
                    InputRow(
                        title = "添加",
                        summary = "填 wxid 或群昵称；昵称重名时只记昵称并标「待确认」",
                        value = input,
                        onValueChange = onInput
                    )
                    RowSpacer()
                    ActionRow(
                        title = "加入本名单",
                        summary = if (input.isBlank()) "先在上一栏填 wxid 或昵称" else "加入：" + input.trim(),
                        onClick = {
                            if (input.isNotBlank()) {
                                val text = input.trim()
                                GroupAdminStore.update(context, room) { cfg ->
                                    val target = GroupAdminStore.listOf(cfg, kind)
                                    GroupAdminStore.addMember(cfg, target, text, "")
                                }
                                onInput("")
                                onChanged()
                            }
                        }
                    )
                }
            }
            item { SmallTitle(text = "名单（" + stored.size + " 条）") }
            item {
                SettingsCard {
                    InputRow(
                        title = "搜索",
                        summary = "按昵称或 wxid 匹配",
                        value = query,
                        onValueChange = onQuery
                    )
                }
            }
            if (filtered.isEmpty()) {
                item { NoteCard(if (stored.isEmpty()) "本名单还没有条目。" else "没有匹配「" + query + "」的条目。") }
            } else {
                item {
                    SettingsCard {
                        filtered.forEachIndexed { index, ref ->
                            if (index > 0) RowSpacer()
                            if (kind == GroupAdminList.BLACK) {
                                MemberRow(
                                    title = GroupAdminStore.label(ref),
                                    subtitle = GroupAdminStore.blackTimeText(ref.expireAt, now),
                                    trailing = "改时长",
                                    onTrailingClick = {
                                        GroupAdminStore.update(context, room) { cfg ->
                                            val current = cfg.black.firstOrNull { it.id == ref.id }
                                            if (current != null) {
                                                val currentHours = hoursOf(current.expireAt, now)
                                                val next = BLACK_HOURS[(BLACK_HOURS.indexOf(currentHours).coerceAtLeast(0) + 1) % BLACK_HOURS.size]
                                                current.expireAt = if (next <= 0) 0L else now + next * 3_600_000L
                                                if (next <= 0) current.expireAt = 0L
                                            }
                                        }
                                        onChanged()
                                    }
                                )
                            } else {
                                MemberRow(
                                    title = GroupAdminStore.label(ref),
                                    subtitle = if (ref.id.isBlank()) "写入时没匹配到唯一成员，点「移出」可删除" else "移出后不再命中",
                                    trailing = "移出",
                                    onTrailingClick = {
                                        GroupAdminStore.update(context, room) { cfg ->
                                            GroupAdminStore.removeMember(cfg, GroupAdminStore.listOf(cfg, kind), ref)
                                        }
                                        onChanged()
                                    }
                                )
                            }
                        }
                    }
                }
            }
            item {
                SettingsCard {
                    ActionRow(
                        title = "清空本名单",
                        summary = if (clearArmed) "再点一次确认清空 " + stored.size + " 条" else "点两次才会执行，防误触",
                        onClick = {
                            if (clearArmed) {
                                clearArmed = false
                                GroupAdminStore.update(context, room) { cfg ->
                                    GroupAdminStore.clearList(cfg, GroupAdminStore.listOf(cfg, kind))
                                }
                                onChanged()
                            } else {
                                clearArmed = true
                            }
                        }
                    )
                }
            }
        }
    }
}

/** 题库输入的只读体检：只出提示，不改解析口径、不阻断保存。 */
private fun questionListNotes(list: String): String? {
    val entries = list.split(';').map { it.trim() }.filter { it.isNotEmpty() }
    if (entries.isEmpty()) return "题库为空：进群时按兜底题「1+1=?」提问，答「2」可通过"
    val noComma = mutableListOf<Int>()
    val formula = mutableListOf<Int>()
    val wide = mutableListOf<Int>()
    entries.forEachIndexed { index, entry ->
        val comma = entry.indexOf(',')
        if (comma < 0 || entry.substring(comma + 1).trim().isEmpty()) {
            noComma.add(index + 1)
            return@forEachIndexed
        }
        val answer = entry.substring(comma + 1).trim()
        if (answer.any { it in "=+*/??" }) formula.add(index + 1)
        if (answer.any { it.code in 0xFF01..0xFF5E || it == '\u3000' }) wide.add(index + 1)
    }
    val parts = mutableListOf<String>()
    if (noComma.isNotEmpty()) parts.add("第 " + noComma.joinToString("、") + " 条没写逗号或答案是空白：该题永远答不对，条目会保留不会丢")
    if (formula.isNotEmpty()) parts.add("第 " + formula.joinToString("、") + " 条的答案像算式：成员一般只打结果，建议只填答案原文")
    if (wide.isNotEmpty()) parts.add("第 " + wide.joinToString("、") + " 条的答案含全角字符：建议改成半角")
    return if (parts.isEmpty()) null else parts.joinToString("；")
}

private fun hoursOf(expireAt: Long, now: Long): Int {
    if (expireAt <= 0L) return 0
    val left = expireAt - now
    return when {
        left <= 3_600_000L -> 1
        left <= 86_400_000L -> 24
        else -> 168
    }
}

@Composable
private fun GroupAdminPickMembersPage(
    context: Context,
    room: String,
    kind: GroupAdminList,
    mode: GroupAdminPickMode,
    query: String,
    selected: Set<String>,
    onQuery: (String) -> Unit,
    onSelected: (Set<String>) -> Unit,
    onConfirm: (Set<String>) -> Unit,
    onBack: () -> Unit,
    listState: LazyListState,
) {
    val scrollBehavior = MiuixScrollBehavior()
    var members by remember(room) { mutableStateOf(GroupAdminMemberCache.members(room)) }
    var loading by remember(room) { mutableStateOf(!GroupAdminMemberCache.isLoaded(room)) }

    LaunchedEffect(room) {
        if (GroupAdminMemberCache.isStale(room)) {
            loading = true
            GroupAdminMemberCache.refreshAsync(room) {
                members = it
                loading = false
            }
        }
    }

    val filtered = remember(members, query) { GroupAdminStore.search(members, query) }
    val stat = GroupAdminMemberCache.stat(room)

    PageScaffold(
        title = if (mode == GroupAdminPickMode.TEST) "选择成员试跑" else "选择成员",
        largeTitle = if (mode == GroupAdminPickMode.TEST) "选择成员试跑" else "选择成员",
        scrollBehavior = scrollBehavior,
        bottomBar = {
            BottomActionBar(
                primaryText = if (mode == GroupAdminPickMode.TEST) {
                    "试跑判定（" + selected.size + "）"
                } else {
                    "加入「" + kind.title + "」（" + selected.size + "）"
                },
                onPrimaryClick = { onConfirm(selected) },
                middleText = "全选",
                onMiddleClick = {
                    val all = filtered.map { it.id }.filter { it.isNotBlank() }.toSet()
                    onSelected(if (selected.containsAll(all) && all.isNotEmpty()) selected - all else selected + all)
                },
                secondaryText = "取消",
                onSecondaryClick = onBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { NoteCard(memberSummary(members.size, stat)) }
            item {
                SettingsCard {
                    InputRow(
                        title = "搜索",
                        summary = "按群昵称、好友备注或 wxid 匹配",
                        value = query,
                        onValueChange = onQuery
                    )
                }
            }
            if (loading) {
                item { NoteCard("正在读取群成员…") }
            } else if (filtered.isEmpty()) {
                item {
                    NoteCard(
                        if (members.isEmpty()) {
                            "没有读到群成员。先在该群页点「刷新群成员并结算」，或完全退出微信再进后重试。"
                        } else {
                            "没有匹配「" + query + "」的成员。"
                        }
                    )
                }
            } else {
                item {
                    SettingsCard {
                        filtered.forEachIndexed { index, ref ->
                            if (index > 0) RowSpacer()
                            val checked = selected.contains(ref.id)
                            MemberRow(
                                title = ref.name.ifBlank { ref.id },
                                subtitle = "wxid: " + ref.id,
                                trailing = if (checked) "已选" else "选择",
                                onTrailingClick = {
                                    onSelected(if (checked) selected - ref.id else selected + ref.id)
                                },
                                onClick = {
                                    onSelected(if (checked) selected - ref.id else selected + ref.id)
                                }
                            )
                        }
                    }
                }
            }
            item {
                NoteCard(
                    if (mode == GroupAdminPickMode.TEST) {
                        "已选中 " + selected.size + " 人。试跑只输出判定依据，不踢人、不发消息、不写配置。"
                    } else {
                        "已选中 " + selected.size + " 人。昵称只用于辨认，名单命中一律按 wxid。"
                    }
                )
            }
        }
    }
}

/**
 * 警告次数管理（5.5.19 要求 5）。
 *
 * 计数键 = `群|成员|规则`，即**每个成员 × 每个类型独立计数**（与 5.5.18 之前一致，
 * 代码位置：[GroupAdminStore.warnKey]）。清除只写 `KEY_WARN` 这一张表，
 * 不动黑名单/白名单/规则配置。
 */
@Composable
private fun GroupAdminWarnPage(
    context: Context,
    onBack: () -> Unit
) {
    val scrollBehavior = MiuixScrollBehavior()
    val listState = rememberLazyListState()
    var tick by remember { mutableStateOf(0) }
    var entries by remember { mutableStateOf<List<GroupAdminStore.WarnEntry>>(emptyList()) }
    LaunchedEffect(tick) {
        entries = withContext(Dispatchers.IO) { GroupAdminStore.warnEntries(context) }
    }
    PageScaffold(
        title = "警告次数管理",
        largeTitle = "警告次数管理",
        scrollBehavior = scrollBehavior,
        bottomBar = { BottomActionBar("返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item {
                NoteCard(
                    "每条计数 = 群 + 成员 + 类型，互相独立。类型名取自规则目录；" +
                        "点击一条即清除该条计数，踢人后该类型计数也已自动归零。"
                )
            }
            item { NoteCard("清除只影响警告次数，不会删除黑名单、白名单或任何规则配置。") }
            if (entries.isEmpty()) {
                item { NoteCard("当前没有任何警告计数。") }
            } else {
                item { ActionRow(title = "全部清除", summary = "共 " + entries.size + " 条", onClick = {
                    GroupAdminStore.warnClearAll(context)
                    tick++
                }) }
                entries.forEach { entry ->
                    item {
                        ActionRow(
                            title = (GroupAdminRuleCatalog.spec(entry.rule)?.title ?: entry.rule) +
                                " · " + entry.count + " 次",
                            summary = "群 " + entry.room + " · 成员 " + entry.wxId +
                                " · 规则 " + entry.rule + "（点击清除这一条）",
                            onClick = {
                                GroupAdminStore.warnClear(context, entry.room, entry.wxId, entry.rule)
                                tick++
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupAdminReportPage(
    title: String,
    lines: List<String>,
    onBack: () -> Unit
) {
    val scrollBehavior = MiuixScrollBehavior()
    val listState = rememberLazyListState()
    PageScaffold(
        title = title,
        largeTitle = title,
        scrollBehavior = scrollBehavior,
        bottomBar = { BottomActionBar("返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            if (lines.isEmpty()) {
                item { NoteCard("没有内容。") }
            } else {
                lines.forEach { line ->
                    if (line.isBlank()) {
                        item { Note("") }
                    } else {
                        item { NoteCard(line) }
                    }
                }
            }
            item { NoteCard("以上均为只读判定：没有任何配置、消息或群成员被改动。") }
        }
    }
}

@Composable
private fun GroupAdminPickGroupsPage(
    context: Context,
    onSaved: (List<Pair<String, String>>) -> Unit,
    onBack: () -> Unit,
    listState: LazyListState,
) {
    val scrollBehavior = MiuixScrollBehavior()
    var groups by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val known = remember { GroupAdminStore.entries(context).map { it.room }.toSet() }
    // 搜索与选择都是本页本地状态（InputRow 完全受控，必须由本页提供可变值）
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var groupFilterId by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        groups = withContext(Dispatchers.IO) { GroupAdminMemberCache.groups() }
        loading = false
    }

    // 模块统一模式：聊天分组取模块既有的会话分组（只显示所选分组及其子分组中的会话）。
    // 群列表本身仍用本页原来那一次读取，没有新增读取路径。
    val groupFilters = remember(groups) {
        if (groups.isEmpty()) {
            emptyList()
        } else {
            ConversationGroupPickerSupport.filters(context, groups.map { it.first })
        }
    }
    LaunchedEffect(groupFilters) {
        if (groupFilterId.isNotBlank() && groupFilters.none { it.id == groupFilterId }) {
            groupFilterId = ""
        }
    }
    val scoped = remember(groups, groupFilters, groupFilterId) {
        val ids = groupFilters.firstOrNull { it.id == groupFilterId }?.conversationIds
        if (ids == null) groups else groups.filter { it.first in ids }
    }
    val filtered = remember(scoped, query) { GroupAdminSearch.filterGroups(scoped, query) }
    val scopeLabel = remember(groupFilters, groupFilterId) {
        groupFilters.firstOrNull { it.id == groupFilterId }?.name ?: "全部"
    }
    val visibleIds = remember(filtered) { filtered.mapTo(linkedSetOf()) { it.first } }
    val allVisibleSelected = visibleIds.isNotEmpty() && selected.containsAll(visibleIds)

    PageScaffold(
        title = "添加群聊",
        largeTitle = "添加群聊",
        scrollBehavior = scrollBehavior,
        bottomBar = {
            BottomActionBar(
                primaryText = "保存选择（" + selected.size + "）",
                onPrimaryClick = {
                    if (selected.isEmpty()) {
                        Toast.makeText(context, "请先选择群聊", Toast.LENGTH_SHORT).show()
                    } else {
                        onSaved(groups.filter { selected.contains(it.first) })
                    }
                },
                middleText = if (visibleIds.isEmpty()) {
                    null
                } else {
                    if (allVisibleSelected) "取消全选" else "全选"
                },
                onMiddleClick = if (visibleIds.isEmpty()) {
                    null
                } else {
                    {
                        selected = if (allVisibleSelected) selected - visibleIds else selected + visibleIds
                    }
                },
                secondaryText = "返回",
                onSecondaryClick = onBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { NoteCard("只列出微信「选择群聊」里可见的群。勾选后点「保存选择」登记；再到该群页打开「纳入管理」才会生效。") }
            if (groupFilters.size > 1) {
                item {
                    SettingsCard {
                        PopupChoiceRow(
                            title = "聊天分组",
                            summary = "只显示所选分组及其子分组中的会话",
                            options = listOf(PopupChoice("全部群聊", "")) + groupFilters.map {
                                PopupChoice(it.name, it.id)
                            },
                            currentValue = groupFilterId,
                            onValueChanged = {
                                groupFilterId = it
                                query = ""
                            }
                        )
                    }
                }
            }
            item {
                SettingsCard {
                    InputRow(
                        title = "搜索",
                        summary = "昵称 / 群聊备注 / wxid",
                        value = query,
                        onValueChange = { query = it }
                    )
                }
            }
            item { PickerSectionHeader(text = scopeLabel + " · " + filtered.size + " 项") }
            when {
                loading -> item { NoteCard("正在读取群列表…") }
                groups.isEmpty() -> item { NoteCard("没有读到群聊。完全退出微信再进后重试。") }
                filtered.isEmpty() -> item {
                    NoteCard("没有匹配的群聊。换个群名关键词，或直接输入群 ID 试试。")
                }
                else -> items(items = filtered, key = { it.first }) { entry ->
                    ContactListCard(
                        option = ContactOption(
                            id = entry.first,
                            label = entry.second.ifBlank { entry.first },
                            group = true,
                            avatarUrl = "",
                            avatarBackupUrl = "",
                            extraSummary = if (known.contains(entry.first)) "已登记" else ""
                        ),
                        selected = selected.contains(entry.first),
                        multiSelect = true,
                        onClick = {
                            selected = if (selected.contains(entry.first)) {
                                selected - entry.first
                            } else {
                                selected + entry.first
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MemberRow(
    title: String,
    subtitle: String,
    trailing: String? = null,
    onTrailingClick: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = MiuixTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp
                )
            }
        }
        if (trailing != null && onTrailingClick != null) {
            Text(
                text = trailing,
                color = MiuixTheme.colorScheme.primary,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onTrailingClick() }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun RowSpacer() {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(text = "", fontSize = 1.sp)
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        fontSize = 12.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
private fun NoteCard(text: String) {
    SettingsCard { Note(text) }
}

/** 运行期状态：消息入口、双重前提、规则参数与最近处理记录（只读，便于真机验证）。 */
private fun runtimeStatusLines(context: Context, room: String, cfg: GroupConfig): List<String> {
    val lines = ArrayList<String>()
    lines.add(
        "【消息入口】" + (if (GroupAdminMessageRuntime.isInstalled()) "已接入（消息观察已订阅）"
        else "未接入：完全退出微信后重进模块再看（热重载会留旧 Hook，结果不可信）")
    )
    lines.add(
        "【双重前提】总开关=" + onOffText(GroupAdminSettings.isEnabled(context)) +
            " · 本群纳入管理=" + onOffText(cfg.master) +
            (if (cfg.master && GroupAdminSettings.isEnabled(context)) "（都满足，规则会执行）" else "（不满足，一律不处理）")
    )
    lines.add(
        "【已接入规则】" + GroupAdminRuleCatalog.IMPLEMENTED.joinToString("、") +
            "；另有规格 2.1 第 14 步的黑名单优先踢人。其余 23 条规则、群内指令、进群验证尚未接入。"
    )
    val long = GroupAdminRuleCatalog.ruleOf(cfg, GroupAdminRuleCatalog.LONG)
    val limit = GroupAdminRuleCatalog.paramInt(
        cfg, GroupAdminRuleCatalog.LONG, GroupAdminRuleCatalog.PARAM_LONG_LIMIT,
        GroupAdminRuleCatalog.DEFAULT_LONG_LIMIT
    )
    lines.add(
        "【long 参数】开关=" + onOffText(long.on) + " · 阈值=" + limit + " 字" +
            " · 警告模式=" + onOffText(long.warnEnable) + "（" + long.warnCount + " 次）" +
            " · 警告文案=" + long.warnText
    )
    lines.add("【long 理由】" + long.reason.ifBlank { GroupAdminRuleCatalog.defaultReason(GroupAdminRuleCatalog.LONG) })
    lines.add(
        "【long 拉黑】" + (if (long.unbanHours <= 0) "永久" else long.unbanHours.toString() + " 小时") +
            " · 本群「动作后在群里提示」=" + onOffText(cfg.notify) + " · 告别语=" + cfg.farewell
    )
    lines.add("【黑名单优先】命中即踢，名单 " + cfg.black.size + " 条（只比 wxid，理由固定「黑名单用户」，不重复拉黑）")
    lines.add("【豁免】" + GroupAdminStore.summaryOf(cfg, GroupAdminList.ADMIN, System.currentTimeMillis()) + " 管理员名单、" +
        cfg.agents.size + " 条代理、白名单 " + onOffText(cfg.wlMaster) + "（" + cfg.white.size + " 条）")
    val recent = GroupAdminMessageRuntime.recentLines()
    lines.add("【最近处理记录】" + (if (recent.isEmpty()) "暂无（在本群发一条超长消息试试，或让黑名单成员发言）" else "最近 " + recent.size + " 条："))
    for (line in recent.asReversed()) lines.add("· " + line)
    lines.add("说明：本页只读；记录只保留最近 20 条，来自本进程。")
    return lines
}

private fun onOffText(value: Boolean): String = if (value) "开" else "关"

private fun listBrief(cfg: GroupConfig): String =
    "管理员 " + cfg.admins.size + " · 代理 " + cfg.agents.size +
        " · 白名单 " + cfg.white.size + " · 黑名单 " + cfg.black.size

private fun memberSummary(total: Int, stat: GroupAdminMemberCache.MemberReadStat?): String {
    if (stat == null) {
        return if (total <= 0) "还没读到群成员。点「刷新群成员并结算」读取。" else "已缓存 " + total + " 人（按 id 匹配）"
    }
    return "已读到 " + stat.total + " 人（成员表 " + stat.memberlist + " 条 / 群昵称表 " + stat.roomNames +
        " 条），其中 " + stat.named + " 人取到群昵称或备注名"
}

/** 规则列表行摘要：开关状态、命中后动作、关键参数现值。 */
private fun ruleSummary(id: String, rule: RuleConfig): String {
    val spec = GroupAdminRuleCatalog.spec(id)
    val state = if (rule.on) "已开启" else "未开启"
    val action = if (id == "leavebl") {
        "只写黑名单"
    } else if (id == "mute" || rule.warnEnable) {
        "警告 " + rule.warnCount + " 次后踢"
    } else {
        "命中即踢"
    }
    val black = if (rule.unbanHours <= 0) "永久拉黑" else rule.unbanHours.toString() + " 小时拉黑"
    val params = spec?.params.orEmpty().mapNotNull { p ->
        val raw = rule.params[p.key]
        when {
            p.isBool -> p.title + "：" + (if (raw == "1" || raw == "true" || raw == "on") "开" else "关")
            p.isText -> p.title + "：" + (raw?.takeIf { it.isNotBlank() }?.let { if (it.length > 10) it.take(10) + "…" else it } ?: "未配置")
            else -> p.title + "：" + (raw?.toIntOrNull()?.coerceIn(p.min, p.max) ?: p.default).toString() + p.unit
        }
    }
    return state + " · " + action + " · " + black + if (params.isEmpty()) "" else " · " + params.joinToString("，")
}

/**
 * 单条规则配置页。
 *
 * 所有输入框都遵循 House 范式（第 5.5.15 轮定下的）：InputRow 是完全受控组件，必须
 * 「先写本地草稿（立刻回显）→ 再落存储 → 再 onChanged()」，否则重组会把旧值盖回来。
 */
@Composable
private fun GroupAdminRulePage(
    context: Context,
    room: String,
    ruleId: String,
    tick: Int,
    onChanged: () -> Unit,
    onBack: () -> Unit,
    templateId: String = "",
) {
    val scrollBehavior = MiuixScrollBehavior()
    val listState = rememberLazyListState()
    val spec = GroupAdminRuleCatalog.spec(ruleId)
    val isTemplateMode = templateId.isNotBlank()
    val config = remember(room, ruleId, tick, templateId) {
        if (isTemplateMode) GroupAdminTemplateStore.templateConfig(context, templateId)
        else GroupAdminStore.group(context, room)
    }
    val rule = remember(room, ruleId, tick) { GroupAdminRuleCatalog.ruleOf(config, ruleId) }
    var reasonDraft by remember(room, ruleId) { mutableStateOf(rule.reason) }
    var warnTextDraft by remember(room, ruleId) { mutableStateOf(rule.warnText) }
    var unbanDraft by remember(room, ruleId) { mutableStateOf(rule.unbanHours.toString()) }
    var warnCountDraft by remember(room, ruleId) { mutableStateOf(rule.warnCount.toString()) }
    var paramDrafts by remember(room, ruleId) {
        mutableStateOf(
            buildMap {
                spec?.params?.forEach { p ->
                    val raw = config.rules[ruleId]?.params?.get(p.key)
                    put(
                        p.key,
                        when {
                            p.isText -> raw ?: p.defaultText
                            p.isBool -> if (raw == null) (if (p.defaultBool) "1" else "0") else raw
                            else -> (raw?.toIntOrNull()?.coerceIn(p.min, p.max) ?: p.default).toString()
                        }
                    )
                }
            }
        )
    }

    fun saveRule(block: (RuleConfig) -> Unit) {
        if (isTemplateMode) {
            GroupAdminTemplateStore.updateTemplate(context, templateId) { cfg ->
                block(GroupAdminRuleCatalog.editable(cfg, ruleId))
            }
        } else {
            GroupAdminStore.update(context, room) { cfg ->
                block(GroupAdminRuleCatalog.editable(cfg, ruleId))
            }
        }
        onChanged()
    }

    fun saveParam(p: GroupAdminRuleCatalog.ParamSpec, raw: String) {
        saveRule { rc -> rc.params[p.key] = raw }
    }

    PageScaffold(
        title = spec?.title ?: ruleId,
        largeTitle = spec?.title ?: ruleId,
        scrollBehavior = scrollBehavior,
        bottomBar = { BottomActionBar("返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp
            )
        ) {
            item {
                NoteCard(
                    if (isTemplateMode) {
                        "正在编辑模板（模板 id：" + templateId + "）：改动写进模板，套用的群要点「同步到已套用的群」才生效"
                    } else {
                        "规则 id：" + ruleId + " · 判定位次第 " +
                        (GroupAdminRuleCatalog.DISPLAY_ORDER.indexOf(ruleId) + 1) + " 位" +
                            (spec?.typeDep?.let { " · 依赖：" + it } ?: "")
                    }
                )
            }
            if (!spec?.note.isNullOrBlank()) {
                item { Note(spec!!.note) }
            }
            item { SmallTitle(text = "开关") }
            item {
                SettingsCard {
                    SwitchRow(
                        checked = rule.on,
                        title = "启用本条规则",
                        summary = if (rule.on) "已开启：满足条件即按下面设定处理" else "未开启：本条规则不参与判定",
                        onCheckedChange = { value -> saveRule { it.on = value } }
                    )
                    if (ruleId == "nonadmin" && !isTemplateMode) {
                        RowSpacer()
                        SwitchRow(
                            checked = config.nonadminBoth,
                            title = "邀请人一并移出",
                            summary = "关闭时只移出被邀请人",
                            onCheckedChange = { value ->
                                GroupAdminStore.update(context, room) { it.nonadminBoth = value }
                                onChanged()
                            }
                        )
                    }
                    RowSpacer()
                    Note("命中后动作：" + ruleSummary(ruleId, rule))
                }
            }
            val params = spec?.params.orEmpty()
            if (params.isNotEmpty()) {
                item { SmallTitle(text = "本条规则参数") }
                item {
                    SettingsCard {
                        params.forEachIndexed { index, p ->
                            if (index > 0) RowSpacer()
                            when {
                                p.isBool -> SwitchRow(
                                    checked = paramDrafts[p.key] == "1",
                                    title = p.title,
                                    summary = p.summary.ifBlank { "默认" + (if (p.defaultBool) "开" else "关") },
                                    onCheckedChange = { value ->
                                        val raw = if (value) "1" else "0"
                                        paramDrafts = paramDrafts + (p.key to raw)
                                        saveParam(p, raw)
                                    }
                                )

                                p.isText -> InputRow(
                                    title = p.title,
                                    summary = p.summary.ifBlank { "留空表示不使用本参数" } +
                                        "；当前：" + (paramDrafts[p.key]?.ifBlank { "空" } ?: "空"),
                                    value = paramDrafts[p.key].orEmpty(),
                                    minLines = 2,
                                    onValueChange = { value ->
                                        paramDrafts = paramDrafts + (p.key to value)
                                        saveParam(p, value)
                                    }
                                )

                                else -> InputRow(
                                    title = p.title + "（" + p.unit + "）",
                                    summary = p.summary.ifBlank {
                                        "范围 " + p.min + "~" + p.max + "，默认 " + p.default + p.unit
                                    } + "；已生效：" + (paramDrafts[p.key]?.takeIf { it.isNotBlank() }
                                        ?: p.default.toString()) + p.unit,
                                    value = paramDrafts[p.key].orEmpty(),
                                    onValueChange = { value ->
                                        val digits = value.filter { it.isDigit() }.take(9)
                                        paramDrafts = paramDrafts + (p.key to digits)
                                        saveParam(p, digits)
                                    }
                                )
                            }
                        }
                    }
                }
            }
            item { SmallTitle(text = "警告与拉黑") }
            item {
                SettingsCard {
                    if (ruleId == "mute") {
                        Note("全员禁言固定走警告模式：警告次数到即踢。")
                    } else {
                        SwitchRow(
                            checked = rule.warnEnable,
                            title = "先警告，不直接踢",
                            summary = if (rule.warnEnable) {
                                "第 " + rule.warnCount + " 次命中才踢"
                            } else {
                                "命中即踢"
                            },
                            onCheckedChange = { value -> saveRule { it.warnEnable = value } }
                        )
                    }
                    RowSpacer()
                    InputRow(
                        title = "警告次数",
                        summary = "命中达到该次数后踢出；已生效：" +
                            (warnCountDraft.toIntOrNull()?.coerceIn(1, 999) ?: rule.warnCount) + " 次",
                        value = warnCountDraft,
                        onValueChange = { value ->
                            val draft = value.filter { it.isDigit() }.take(3)
                            warnCountDraft = draft
                            saveRule { it.warnCount = draft.toIntOrNull()?.coerceIn(1, 999) ?: 1 }
                        }
                    )
                    RowSpacer()
                    InputRow(
                        title = "警告文案",
                        summary = "限时警告时发给对方的文字；当前：" +
                            warnTextDraft.ifBlank { GroupAdminRuleCatalog.DEFAULT_WARN_TEXT },
                        value = warnTextDraft,
                        minLines = 2,
                        onValueChange = { value ->
                            warnTextDraft = value
                            saveRule { it.warnText = value }
                        }
                    )
                    RowSpacer()
                    InputRow(
                        title = "拉黑时长（小时）",
                        summary = "命中后拉黑该成员的小时数；0 = 永久；已生效：" +
                            when (val hours = unbanDraft.toIntOrNull()?.coerceIn(0, 87600) ?: rule.unbanHours) {
                                0 -> "永久"
                                else -> hours.toString() + " 小时"
                            },
                        value = unbanDraft,
                        onValueChange = { value ->
                            val draft = value.filter { it.isDigit() }.take(5)
                            unbanDraft = draft
                            saveRule { it.unbanHours = draft.toIntOrNull()?.coerceIn(0, 87600) ?: 0 }
                        }
                    )
                    RowSpacer()
                    InputRow(
                        title = "踢人理由",
                        summary = "踢出时记录的理由；留空用默认理由：" +
                            GroupAdminRuleCatalog.defaultReason(ruleId),
                        value = reasonDraft,
                        minLines = 2,
                        onValueChange = { value ->
                            reasonDraft = value
                            saveRule { it.reason = value }
                        }
                    )
                }
            }
            item { SmallTitle(text = "只读试跑") }
            item {
                SettingsCard {
                    Note("执行顺序：先发告别语（含 @ 目标）→ 移出群聊 → 写黑名单；本条只读，不会真的发送或移出。")
                    RowSpacer()
                    if (!isTemplateMode) {
                        Note("本群告别语：" + GroupAdminStore.group(context, room).farewell)
                        RowSpacer()
                    }
                    Note("默认理由：" + GroupAdminRuleCatalog.defaultReason(ruleId))
                }
            }
        }
    }
}

/** 模板列表页：显示每个模板的规则/媒体摘要与「N 个群在用」。 */
@Composable
private fun GroupAdminTemplatesPage(
    context: Context,
    tick: Int,
    listState: LazyListState,
    onChanged: () -> Unit,
    onOpenTemplate: (String) -> Unit,
    onBack: () -> Unit
) {
    val scrollBehavior = MiuixScrollBehavior()
    val templates = remember(tick) { GroupAdminTemplateStore.list(context) }
    PageScaffold(
        title = "模板",
        largeTitle = "模板",
        scrollBehavior = scrollBehavior,
        bottomBar = { BottomActionBar("返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp
            )
        ) {
            item {
                NoteCard(
                    "模板保存 24 条规则的全部设置（开关 / 阈值等参数 / 警告模式与次数 / 警告文案 / 踢人理由 / 拉黑时长）" +
                        "与媒体设置。套用后群内逐条规则被锁定，改模板需要点「同步到已套用的群」才会影响那些群。"
                )
            }
            item { SmallTitle(text = "模板（" + templates.size + "）") }
            if (templates.isEmpty()) {
                item { NoteCard("还没有模板。用下面的「新建模板」创建，或先在某个群里配好，再到「模板」里用「从群采集」反推。") }
            } else {
                templates.forEach { tpl ->
                    item(key = tpl.id) {
                        SettingsCard {
                            ActionRow(
                                title = tpl.name,
                                summary = GroupAdminTemplates.listSummary(
                                    tpl,
                                    GroupAdminTemplateStore.countBound(context, tpl.id)
                                ),
                                onClick = { onOpenTemplate(tpl.id) }
                            )
                        }
                    }
                }
            }
            item { SmallTitle(text = "操作") }
            item {
                SettingsCard {
                    ActionRow(
                        title = "新建模板",
                        summary = "初始状态为「24 条规则全部关闭」；想照抄某个群，用模板页里的「从群采集」",
                        onClick = {
                            val tpl = GroupAdminTemplateStore.create(context, "新模板")
                            onChanged()
                            onOpenTemplate(tpl.id)
                        }
                    )
                }
            }
        }
    }
}

/** 单个模板编辑页：改名 / 复制 / 删除 / 逐条规则 / 媒体设置 / 同步到已套用的群。 */
@Composable
private fun GroupAdminTemplateEditPage(
    context: Context,
    templateId: String,
    tick: Int,
    listState: LazyListState,
    onChanged: () -> Unit,
    onOpenRule: (String) -> Unit,
    onBack: () -> Unit
) {
    val scrollBehavior = MiuixScrollBehavior()
    val template = remember(templateId, tick) { GroupAdminTemplateStore.get(context, templateId) }
    var nameDraft by remember(templateId) { mutableStateOf(template?.name.orEmpty()) }
    var mediaOrderDialog by remember(templateId) { mutableStateOf(false) }
    val mediaSequence = remember(templateId, tick) {
        template?.media?.get(GroupLeaveMonitorSettings.KEY_MEDIA_SEQUENCE).orEmpty()
    }
    PageScaffold(
        title = "编辑模板",
        largeTitle = template?.name ?: "模板",
        scrollBehavior = scrollBehavior,
        bottomBar = { BottomActionBar("返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp
            )
        ) {
            if (template == null) {
                item { NoteCard("模板不存在或已删除。") }
            } else {
                item {
                    NoteCard(
                        "规则 " + template.onCount() + "/" + template.ruleCount() + " 开启 · 媒体 " +
                            template.mediaCount() + " 项 · " +
                            GroupAdminTemplateStore.countBound(context, templateId) + " 个群在用"
                    )
                }
                item { SmallTitle(text = "基本信息") }
                item {
                    SettingsCard {
                        InputRow(
                            title = "模板名称",
                            summary = "群页显示「已套用：名称」用的就是它",
                            value = nameDraft,
                            onValueChange = { value ->
                                nameDraft = value
                                GroupAdminTemplateStore.rename(context, templateId, value)
                                onChanged()
                            }
                        )
                        RowSpacer()
                        ActionRow(
                            title = "复制模板",
                            summary = "连同 24 条规则与媒体设置一起复制一份",
                            onClick = {
                                val copy = GroupAdminTemplateStore.duplicate(context, templateId)
                                onChanged()
                                // 复制后停留在本页（新模板出现在模板列表里）
                            }
                        )
                        RowSpacer()
                        ActionRow(
                            title = "删除模板",
                            summary = "会同时解除所有已套用群的绑定（那些群保留当前配置值，不回到默认）",
                            onClick = {
                                val freed = GroupAdminTemplateStore.remove(context, templateId)
                                onChanged()
                                onBack()
                            }
                        )
                    }
                }
                item { SmallTitle(text = "规则（24 条，编辑即改模板）") }
                GroupAdminRuleCatalog.DISPLAY_ORDER.forEachIndexed { index, id ->
                    val spec = GroupAdminRuleCatalog.spec(id)
                    item(key = "rule-" + id) {
                        SettingsCard {
                            ActionRow(
                                title = (index + 1).toString() + ". " + (spec?.title ?: id) + "（" + id + "）",
                                summary = ruleSummary(id, GroupAdminRuleCatalog.ruleOf(
                                    remember(templateId, tick) {
                                        GroupAdminTemplateStore.templateConfig(context, templateId)
                                    }, id
                                )),
                                onClick = { onOpenRule(id) }
                            )
                        }
                    }
                }
                item { SmallTitle(text = "媒体设置（套用时写到群级键）") }
                item {
                    SettingsCard {
                        PopupChoiceRow(
                            title = "从群采集媒体设置",
                            summary = "把某个群当前的媒体设置（媒体顺序 / 类型顺序 / 各类文件）抄进模板；当前 " +
                                template.mediaCount() + " 项",
                            options = listOf(PopupChoice("不采集", "")) +
                                GroupAdminMemberCache.groups().map { PopupChoice(it.second, it.first) },
                            currentValue = "",
                            onValueChanged = { room ->
                                if (room.isNotBlank()) {
                                    val snapshot = GroupAdminTemplateStore.mediaSnapshot(context, room)
                                    GroupAdminTemplateStore.get(context, templateId)?.let { tpl ->
                                        tpl.media = snapshot
                                        tpl.updatedAt = System.currentTimeMillis()
                                        GroupAdminTemplateStore.save(context, tpl)
                                    }
                                    onChanged()
                                }
                            }
                        )
                        RowSpacer()
                        ActionRow(
                            title = "媒体类型顺序",
                            summary = GroupMemberMediaOrder.summary(mediaSequence) + "（点按调整顺序）",
                            onClick = { mediaOrderDialog = true }
                        )
                        RowSpacer()
                        ActionRow(
                            title = "清空媒体设置",
                            summary = "清空后套用不再改写群里的媒体键，各群保持自己的媒体配置",
                            onClick = {
                                GroupAdminTemplateStore.get(context, templateId)?.let { tpl ->
                                    tpl.media = linkedMapOf()
                                    tpl.updatedAt = System.currentTimeMillis()
                                    GroupAdminTemplateStore.save(context, tpl)
                                }
                                onChanged()
                            }
                        )
                    }
                }
                item { SmallTitle(text = "同步") }
                item {
                    SettingsCard {
                        ActionRow(
                            title = "同步到已套用的 " + GroupAdminTemplateStore.countBound(context, templateId) + " 个群",
                            summary = "把模板当前的 24 条规则与媒体设置重新写到那些群（不改模板就不需要点）",
                            onClick = {
                                val count = GroupAdminTemplateStore.syncToBoundGroups(context, templateId)
                                onChanged()
                            }
                        )
                        RowSpacer()
                        Note("模板改动不会自动影响已套用的群：这是有意设计，避免「改了模板，群里的行为突然变了」。需要生效就点上面这一项。")
                    }
                }
            }
        }
    }
    if (mediaOrderDialog && template != null) {
        GroupMemberMediaOrderDialog(
            current = mediaSequence,
            onValueChange = { next ->
                GroupAdminTemplateStore.updateTemplateMedia(
                    context, templateId, GroupLeaveMonitorSettings.KEY_MEDIA_SEQUENCE, next
                )
                onChanged()
            },
            onClose = { mediaOrderDialog = false }
        )
    }
}
