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
- **待用户确认**：所指的「群管理」是下面哪一个，以免我查错对象：
  - (a) 模块内的群聊功能（群成员昵称、退群监控、群公告通知、群聊标签…）；
  - (b) 会话分组（首页分组/分组页）的修复；
  - (c) 脚本插件里的「群管理助手 / 群娱乐机器人 / 批量退群 / 新人进群验证」
    （插件位于 `/data/media/<用户>/Android/media/com.tencent.mm/Hchat/脚本插件/`，不在本仓库）。
