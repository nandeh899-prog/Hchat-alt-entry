package com.aeonlab.toolkit.ui.miuix

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aeonlab.toolkit.hooks.items.groupleave.GroupLeaveMonitorSettings
import com.aeonlab.toolkit.hooks.items.groupleave.GroupMemberMediaOrder
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 「媒体类型顺序」弹窗。
 *
 * 单独成文件（而不是塞进 MiuixSettingsPage.kt）：那个文件的 Kotlin 文件门面类
 * MiuixSettingsPageKt 已接近 JVM 单个 class 的 64KB 上限，再多塞常量和 Composables
 * 会直接触发 ClassTooLargeException（2026-11-26 构建实测）。
 */
@Composable
internal fun GroupMemberMediaOrderDialog(
    current: String,
    onValueChange: (String) -> Unit,
    onClose: () -> Unit
) {
    var draft by remember(current) { mutableStateOf(GroupMemberMediaOrder.types(current)) }
    WindowDialog(
        show = true,
        title = "媒体类型顺序",
        onDismissRequest = onClose,
        content = {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(
                    text = "顺序只决定发送媒体时先发哪一类；媒体与提示的先后由上一项「媒体顺序」控制，具体文件在各类型行里选。各类型行标题前的序号就是它当前的位置，点按某一类把它上移一位。",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                SettingsCard {
                    draft.forEachIndexed { index, type ->
                        if (index > 0) InsetDivider()
                        ActionRow(
                            title = "${index + 1}. " + GroupMemberMediaOrder.label(type),
                            summary = if (index == 0) "已在最前" else "点按上移一位"
                        ) {
                            val next = GroupMemberMediaOrder.moveUp(draft, index)
                            if (next != draft) {
                                draft = next
                                onValueChange(GroupMemberMediaOrder.sequence(next))
                            }
                        }
                    }
                }
                ActionRow(
                    title = "恢复默认顺序",
                    summary = GroupMemberMediaOrder.summary(GroupLeaveMonitorSettings.DEFAULT_MEDIA_SEQUENCE)
                ) {
                    val preset = GroupMemberMediaOrder.types(GroupLeaveMonitorSettings.DEFAULT_MEDIA_SEQUENCE)
                    draft = preset
                    onValueChange(GroupMemberMediaOrder.sequence(preset))
                }
                BottomActionBar(primaryText = "完成", onPrimaryClick = onClose)
            }
        }
    )
}