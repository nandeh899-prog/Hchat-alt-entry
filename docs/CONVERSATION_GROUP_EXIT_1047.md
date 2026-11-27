# 会话分组「退出闪屏 / 两次返回 / 开关不释放」修复记录（1047）

> 真机验收：2026-11-27，用户原话「这次测试全过」。
> 基线：`baseline-1047` = `adbcbc9`（含群管理，接在 `baseline-1042` 之上）。

## 一、用户报的四条症状

1. 关闭「聊天分组」总开关后，分组内的会话没有回到首页（要摸一下屏幕才出现，甚至不出现）。
2. 分组页里点群聊，进不去聊天界面（1042 及之前）。
3. 退出分组页（**没有**点开任何群聊）会连续出现两次分组页，要按两次返回才回首页。
4. 微信偶发黑屏闪退（`logcat -b crash` 里两次，均为首页列表 ClassCastException）。

## 二、真正的根因：`param.result = null` 从来没生效

`app/src/main/java/com/aeonlab/toolkit/xposed/compat/XC_MethodHook.kt` 旧实现：

```kotlin
internal fun isOverridden(): Boolean = result != null || throwable != null
```

`RuntimeBridge` 在 `beforeHookedMethod` 之后用 `isOverridden()` 决定是否 `return`（跳过原方法）。
写 `param.result = null` 时 `result` 依然是 `null` —— **判定为“未覆盖”，原方法照常执行**。

于是分组功能里所有“吞掉点击 / 拦掉虚拟会话”的拦截点都是空转：

| 拦截点 | 代码意图 | 真实效果（1042～1046） |
|---|---|---|
| `installHomeListClickHook`（首页 onItemClick） | 吞掉虚拟分组行，只由我们 `showGroup()` 开页面 | 我们的 `showGroup()` 开了**一个**，微信自己又开**一个** → 两层分组页，要返回两次 |
| `LauncherUI.startChatting`（虚拟 talker） | 拦掉，不建空白聊天页 | 没拦住，微信照建 → 1042 “点不进群聊” |
| `getStringExtra(GROUP_TALKER_EXTRA)` 退出窗口切断 | 返回 null 切断父会话 | 没切断 → 退出时 LauncherUI 挂出空白聊天视图 |
| `nativeHideConversation`（隐藏分组内会话） | 吞掉原生隐藏 | 用的是 `param.result = true`（非空）→ 这条**一直是生效的** |

这也解释了历史上一串补救提交：`hideChattingLayer`、`sweepAction` 按返回键、
`beginGroupPageExit` 5s/20s 复位 —— 都是在补救“拦截没生效”的后果，不是根因。

## 三、改动清单（`baseline-1042..adbcbc9`，共 5 个提交）

| 提交 | 内容 |
|---|---|
| `ce83c4a` | 关闭总开关的还原计划修正（`assigned = emptyMap()` 而非自己映射自己）+ 新增 `showChattingLayer` + `onResume` 条件隐藏 |
| `de1f7cf` | 删虚拟父行前回查引用（`loadVirtualRowReferences` 护栏，移植自主题支线） |
| `977afbe` | 根级还原强制写 `parentRef NULL`（微信首页按 `is null` 查，写空串查不到） |
| `762ced4` | 开关切换后**无条件**刷新首页投影（`ConversationGroupHomeProjection.refresh()`），不再依赖宿主通知；退出分组页首帧先藏空白聊天层 |
| `adbcbc9` | **兼容层补 `returnEarly`**：`setResult(...)`/`setThrowable(...)`（含 null）真正跳过原方法；`isOverridden()` 改为 `returnEarly \|\| result != null \|\| throwable != null`；分组文件 12 处吞拦截改用 `param.setResult(null)` |

约束：`isOverridden()` 保留“非空 result 即覆盖”的旧语义，因此**其他功能里
`param.result = 非空值` 的拦截行为不变**，只有 `setResult(null)` 这条以前空转的路径被修好。

## 四、真机证据

- 修复前 `已隐藏分组页内预建的空白聊天层: ConvBoxServiceConversationUI` 一次进页出现 2 次；
  同一次会话里 `editTextProbe#1 host=ConvBoxServiceConversationUI` 出现 3 次（= 3 个 ConvBox 实例）。
- 每次点击日志同时出现两条不同来源的“吞点击”记录，且 `param.result = null` 之后原逻辑仍在跑。
- 崩溃（两次，均为 `1045/1046` 时期）：
  ```
  java.lang.ClassCastException: java.lang.Object cannot be cast to com.tencent.mm.ui.conversation.o3
    at com.tencent.mm.ui.conversation.q3.getView
    at android.widget.HeaderViewListAdapter.getView
    at AbsListView$FlingRunnable.run
  ```
  与“同一个点击被处理两遍 / 两层 ConvBox 共用一层聊天视图”相关；`adbcbc9` 之后未再复现。
- 修复后用户验收：分组开关归拢/释放正常且**不用摸屏幕**；退出分组页一次返回即回首页；
  分组内进群聊、退出均正常；无黑屏闪退。

## 五、真机验证清单（下次回归用）

1. 开总开关 → 首页把 25 个会话收进 2 个分组；关总开关 → 25 个会话当场回首页，无需触摸。
2. 点分组 → 只出现**一层**分组页；直接返回 → 一次回首页。
3. 分组内点群聊 → 能进、能退；退出后首页无残留空白页。
4. 反复进出 3 次以上，`logcat -b crash` 无新增 `com.tencent.mm` FATAL。

## 六、回退方式

```bash
git reset --hard baseline-1047   # 回到本次验收版（adbcbc9）
git reset --hard baseline-1042   # 回到上一基线（43f2386，分组半修复状态）
git reset --hard baseline-1028   # 回到无群管理的干净基线（c97a05c，保留不删）
```

只想摘掉兼容层改动：`git revert adbcbc9`（会同时退回 12 处 `setResult(null)` 调用）。

## 七、易踩坑

- 兼容层语义改动影响**所有**功能，改 `isOverridden()` 必须保留“非空 result 即覆盖”，
  否则大量 `param.result = 0/false/true` 的拦截会静默失效。
- 不要再用 `param.result = null` 表达“吞掉”；只能写 `param.setResult(null)`。
- 非 `void` 返回类型仍建议按类型给值（`0` / `false` / `null`），避免宿主空拆箱。
