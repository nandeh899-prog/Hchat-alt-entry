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

- **下一步（需用户选择）**：
  1. 只验基线：重装 `Toolbox-720.apk`（1038，代码 = baseline-1028），分组修复即回来，但群管理仍然没有；
  2. 要群管理：把 `hooks/items/groupadmin/`（21 文件）从 `theme-work` 移植到 `baseline-1028` 之上，只新增文件 + `FeatureRegistry` 注册，不动冻结项；
  3. 要两者都在：以 `baseline-1028` 为基线做「移植 + 保留分组修复」，之后主题实验只在单独分支跑。
