# 待办（TODO）

> 规则：每条待办必须写「现象 / 已知证据 / 下一步最小动作 / 禁止做法」，
> 并且受 `docs/THEME_REGRESSION_FREEZE.md` 约束（先把冻结项读完再动手）。

## 进行中

### 1. 通讯录页底部 174px 出现横向色带（三态都出现）

- **现象**：隐藏原生底栏后，通讯录页最底部 174px 是一条与上方列表不连续的色带。
  - 壁纸全开：那条是壁纸的底部图案（有横向分界线）；
  - 只关通讯录页 / 关「启用页面背景」：那条是 `#EDEDED` 浅灰（截图实测 y=2577 起到底）。
- **已知证据**（真机日志，16:41）：
  ```
  color=#ffededed 来源=Paint.setColor  ← View.drawBackground ← WxViewPager.draw
  color=#ffededed 来源=Canvas.drawRect ← ColorDrawable.draw ← AbsListView.draw
  ```
  即该区域是**不透明纯色底**，不是「内容链没铺到底」；拖底-710k 一直在正常执行（内容链确实是 0..2772）。
- **已被证伪的做法（禁止再试）**：
  - 停用/绕过拖底-710k（1033）；
  - 把空页格填充改成白色或任何其他颜色（1029b）；
  - 整片清掉「页面/列表大小 + `#ededed`」的视图背景（1035，真机「全乱了」：壁纸从行间、分节标题后面透出来）；
  - 把 `MMWeUIBounceView` 拉 `MATCH_PARENT`（710g）。
- **下一步最小动作**：出**只取证版**——
  在通讯录页，对 y∈[2577,2772] 区域内的每个视图，打印：
  `类名 + id + 背景 drawable 类型与颜色 + 是否在我们改过的登记表里 + 祖先链（到 ViewPager/RecyclerView 为止）`；
  **不修改任何背景**。拿到点名结果后，再决定是否只对**单个**视图做处理。
- **判定标准**：用户肉眼确认「底部与列表连续、无色带」，且重启后仍成立，才允许写进冻结表。

## 待确认

### 2. 分组修复 / 群管理 在基线中的覆盖情况

- 已核实：`c97a05c`（baseline-1028）**包含**会话分组与群聊标签相关全部代码：
  - `hooks/items/conversationgroup/`（18 个文件）、`hooks/items/grouplabel/`、`hooks/items/aeonlabextra/`
    合计 33 个 Kotlin 文件、13,936 行；
  - 相关提交共 37 条，全部是基线的祖先（含 `b69c6df` 隐藏分组页空白聊天层、`860fcf6` 首页点击吞虚拟分组行、
    `3bfda5d` 分组页自有 extra、`df4c362` 关闭分组页返回拦截 等）；
  - `FeatureRegistry` 中已注册 `ConversationGroupFeature()`、`GroupChatLabelFeature()`、
    `QuickGroupChatLabelFeature()`，`AeonLabExtraFeature` 内注册 `GroupMemberHistorySettingsProvider()`。
- **结论（2026-11-26 17:40 查实）：不是基线丢代码，是「两条分支各缺一半」**

  | | `alt-entry`（我们构建的基线分支） | `fix/5.5.103-587-theme`（主题实验分支，本地 worktree `/root/work/theme`） |
  |---|---|---|
  | 群管理 `hooks/items/groupadmin/`（21 个文件） | **从来没有**（全历史无此目录） | **有** |
  | 会话分组修复（`ConversationGroupRuntime.kt` 700 行级修复：`df4c362`/`3bfda5d`/`860fcf6`/`84e7301`/`b69c6df` 等） | **有** | **没有**（该文件比基线少约 591 行，这 8 条提交全部「不在」theme-work） |
  | `grouplabel/` | 有 | 有（两边一致，无差异） |

- **证据**：
  - `git rev-list --count origin/alt-entry..theme-work` = 60，反向 = 164，`merge-base` 为空（仓库为浅克隆，可见历史在 11-12 处被截断）；
  - `git ls-tree origin/alt-entry .../items/` 与 `baseline-1028` 均无 `groupadmin`，`theme-work`/`theme-fresh-start` 有；
  - `git diff baseline-1028:.../conversationgroup theme-work:.../conversationgroup` → `ConversationGroupRuntime.kt | 109 +, 700 -`；
  - `/sdcard/Download` 下 40 个 APK 全部 `GroupAdmin=0`，唯独**手机当前装的那一版**（`versionCode=587`，
    `base.apk` 21,176,887 字节，写入时间 17:27）`GroupAdmin=1313`。

- **由此解释用户两个现象**（同一根因）：手机上 17:27 被装成了 **587（主题分支构建）**，不是我们的 1038：
  - 「设置里找不到群管理」→ 我们构建的 alt-entry/1038 本来就没有 groupadmin（历史从未有过）；
  - 「分组修复回归到未修复前状态」→ 587 是主题分支，里面 `ConversationGroupRuntime` 缺那 700 行修复。

- **已执行（2026-11-26 18:35，用户选 B）**：
  - 以 `baseline-1028` 为基线，新增 `hooks/items/groupadmin/`（21 文件）、
    `hooks/items/groupleave/GroupMemberMediaOrder.kt`、`ui/miuix/GroupMemberMediaOrderDialog.kt`，
    `FeatureRegistry` 注册 `GroupAdminFeature()`，`MiuixSettingsPage` 补 import / PREFS_NAME / 群组分节 /
    搜索关键词 / 页面路由；
  - 期间踩到并记录三个编译问题：`ContactListCard`/`ContactOption`/`PickerSectionHeader` 原为 `private`
    （改 `internal`）、漏写 `@Composable`、`MiuixSettingsPageKt` 门面类超 64KB 触发
    `ClassTooLargeException`（把 `featureSubSearchTerms` 176 行整段拆到 `ui/miuix/FeatureSubSearchTerms.kt` 腾余量）；
  - 构建 run 42（`45800944040`，head `4bbbf3c`）success，产物 21,330,479 字节，
    已装 999 分身：`versionCode=1042`，装机包内 `GroupAdmin` 字符串 1313 处；
  - **当时尚未真机验证功能行为**（后由用户验收，见下方时间线）：待用户开「设置 → 实用 → 群组 → 群管理」确认页面可进入、开关可保存、规则能生效。

- **当前基线（2026-11-26 19:05 起）：`baseline-1042`** = `56ba396`（= 已验收的 1042 代码）；
  tag 与 branch 同名，push 须显式 refspec：
  `git push origin refs/heads/x:refs/heads/x refs/tags/x:refs/tags/x`。
- **回退方式**：
  - 回到已验收 1042：`git reset --hard baseline-1042`；
  - 回到无群管理的干净基线：`git reset --hard baseline-1028`（= `c97a05c`，保留不删）；
  - 只想摘掉群管理：revert `dd0b01a`、`de0b69c`、`980a301`、`4bbbf3c` 四个提交。
- **真机验收（2026-11-26 18:50，用户原话）**：「分组已修复」「群管理界面正常」。
  即 B 方案两个目标均达成——会话分组修复保留、群管理设置页可进入。
  **仍未验证**：群管理内部规则的真实生效范围（见下一条末尾）。

### 3. 群管理副标题仍写「只读校准版」（纯文案问题，本轮不改）
- **现象**：设置 → 实用 → 群组 → 群管理，功能卡片副标题显示
  「按群生效的名单、豁免与踢人动作（**当前为只读校准版**）」，
  用户据此误以为整个群管理是只读的。
- **已知证据**：
  - 文案出处 `hooks/items/groupadmin/GroupAdminSettingsProvider.kt:9`，属「只读校准」阶段的遗留描述；
  - `GroupAdminRuleCatalog.kt:90` `val IMPLEMENTED = DISPLAY_ORDER` =
    `CHAIN_ORDER` 20 条（mute/fakeowner/atmany/keyword/repeat/long/video/qrcode/image/card/
    mini/article/voice/location/emoji/file/music/redpacket/transfer/note）
    + `EVENT_ORDER` 4 条（pat/rename/nonadmin/leavebl）= 24 条；
  - `GroupAdminRuleEngine.kt`（465 行）逐条实现上述判定，不是占位；
  - `GroupAdminMessageRuntime.kt` 的 `dispatch()` 已按 `GRACE_WARN` / `BLACKLIST_ONLY` /
    `GM_HIT`（警告 N 次后踢）/ `KICK_NOW` 四条动作分发；踢人走 `kickNow()` →
    `GroupAdminActions.kick()`（先发告别语 → 移出群 → 写黑名单），
    `dryRun()` 只是面板「只读试跑」按钮的独立只读路径；
  - 「只读」字样只出现在面板**局部区块**：`GroupAdminMiuixPage.kt:671`（只读校准（不踢人、不发消息））、
    `:688`（全只读）、`:1244`（以上均为只读判定）、`:1771`（只读试跑按钮），
    指的是那几个分区，不是整个功能；
  - 另一处同类过期描述：`GroupAdminFeature.kt` 顶部注释仍写
    「本轮真正会执行的规则只有 `long` 一条……其余规则、指令、进群验证仍未接入」，
    与 `IMPLEMENTED = DISPLAY_ORDER` 矛盾（该注释写于规格第 4 步，后续未同步）。
- **下一步最小动作（与下次改动一起做，本轮不动）**：
  1. `GroupAdminSettingsProvider.kt:9` 副标题改为「按群生效的名单、豁免与踢人动作」——去掉括注；
  2. 同步改写 `GroupAdminFeature.kt` 顶部注释为当前真实状态（24 条规则已接线、四条动作执行链；
     指令 / 进群验证是否接入须按代码逐个确认后再写，不许照抄规格）；
  3. 一次构建（复用 `theme-999-test` workflow）装机，肉眼确认卡片副标题正确、面板其余文案未变。
- **禁止做法**：
  - 不要为了「文案统一」去改 `GroupAdminMiuixPage.kt` 里那几处「只读试跑 / 只读判定 / 只读校准」分区标题
    —— 它们描述的是**真实存在的只读路径**，改了反而误导；
  - 不要在改文案时顺手把「24 条规则全部可用」写成已验证结论。
- **未验证声明**：24 条规则的真实生效范围**尚未逐条真机验证**；
  `IMPLEMENTED` 只代表「代码里已接线」，不代表「真机实测通过」。逐条验证请另立待办。

---
## 已闭环
### 4. 会话分组「退出闪屏 / 两次返回 / 开关不释放」（1047 修好，已并入基线）
- **状态**：2026-11-27 真机验收通过（用户原话「这次测试全过」），已合入 `baseline-1047`（= `adbcbc9`）。
- **根因一句话**：兼容层 `isOverridden()` 只看 `result != null`，所以分组里所有
  `param.result = null` 的「吞点击 / 拦虚拟会话」全是空转——微信自己也开了一个分组页（要返回两次）、
  也照样建了空白聊天层（退出闪屏），`startChatting` 拦截同样无效（1042「点不进群聊」）。
- **修法**：兼容层补 `returnEarly`，`setResult(...)` / `setThrowable(...)`（含 null）真正跳过原方法；
  分组文件 12 处吞拦截改用 `param.setResult(null)`。保留「非空 result 即覆盖」旧语义，
  因此其他功能里 `param.result = 非空值` 的拦截行为不变。
- **完整记录（现象 / 证据 / 改动清单 / 验证清单 / 回退）**：`docs/CONVERSATION_GROUP_EXIT_1047.md`。
- **禁止做法**：
  - 不要再写 `param.result = null` 表达「吞掉」；只能写 `param.setResult(null)`；
  - 不要再靠 `hideChattingLayer` / 按返回键补救「拦截没生效」的后果——先确认拦截真的生效。
- **回退**：`git reset --hard baseline-1042`（43f2386）；只想摘兼容层改动 `git revert adbcbc9`。
### 5. 其余 51 处 `param.result = null` 空转（已审计完毕，**暂不动**）
- **背景**：1047 修掉的兼容层根因（`isOverridden()` 只看非空 result）在其余目录里同样存在，
  共 51 处真实代码（另 2 处是兼容层注释文字）。分三类：
  - A 类 15 处「空转无害」（void 菜单回调，原生 default 分支本来不做事）；
  - B 类 31 处「空转 = 功能实际没生效」（打字上报、通话铃声、表情游戏、消息拦截、
    静默通知/DND、收藏语音、设置页返回键 等）；
  - C 类 5 处「禁止改动」（构造函数 / after 阶段的 Bitmap / 冻结项 / 故意兼底）。
- **用户决定（2026-11-27）**：「暂时不动，把这些现象整理做一个备忘，以后再修」。
- **完整清单与修复流程**：`docs/LEGACY_NULL_RESULT_AUDIT.md`（含逐文件行号、每项改法要点、
  建议顺序、禁止做法）。
- **以后开工前必须做的**：先用探针打印被钩方法的 `returnType`，**不准猜**；一次只动一个功能、
  一版测试包；对象返回类型不准返 null；构造函数不准 setResult。
