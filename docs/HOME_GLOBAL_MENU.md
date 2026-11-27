# 首页顶部全局批量入口（`A`）——现象、误判与最终做法

范围：`hooks/items/conversationgroup/ConversationGlobalMenu.kt`
真机环境：user 999 分身微信 `8.0.49 / 2600`，模块 `com.aeonlab.toolkit.coexist`。

## 1. 目标

在微信首页标题栏右侧加一个入口（显示为单字 `A`），点开是**全局**批量操作菜单：

```
所有消息标为已读 / 批量删除消息 / 批量屏蔽消息 / 消息免打扰 / 解除消息免打扰 / 发送 / 批量归组
```

菜单项用稳定 key 分发（`mark_read` / `clear_messages` / `block_messages` /
`dnd_on` / `dnd_off` / `send` / `regroup`），以后增删项不会整列错位。
「标已读 / 免打扰 / 发送」都先选目标会话，免打扰还会把**当前已开启**的会话预勾选。

## 2. 三次误判（都不要再走）

### 2.1 用 `addTextOptionMenu` / `addIconOptionMenu`（1050~1054）

- 现象：标题栏变成 **两个 🔍 两个 ＋**，而且 `A` 要进一次聊天再退出才出现。
- 结论：`iconRes=0` 时宿主会自己渲染一个默认图标，换透明资源也不生效；
  这条路径的外观不在我们控制范围内。

### 2.2 用 `plus_icon` / `meb` 作为锚点定位标题栏（1056）

- 日志里确实有 `WeImageView id=meb` / `id=plus_icon`，但它们**不是真 ActionBar 的图标**，
  而是会话 `ListView` 顶部那条「假标题栏」里的。
- 所以命中之后插进去的位置根本不对，且真标题栏仍然多两个图标。

### 2.3 用「只打印带 text/content-desc 的节点」来验证（1059 之后的错误结论）

- 那些多出来的图标是 `ImageView#meb` / `plus_icon`，**没有文字也没有 content-desc**，
  被过滤条件直接滤掉，于是得出「只有一个 🔍 一个 ＋」的错误结论。
- 正确做法：`uiautomator dump` 之后**不过滤**打印 `y` 小于标题栏高度的全部节点及其 `bounds`。
  这样才能看出同一块区域里有**两层**标题栏叠加。

## 3. 真正的结构（1059 无过滤 UI 树）

```
A) 真 ActionBar（窗口层）
   FrameLayout#ei [0,139][1240,288]
     ViewGroup#ef → RelativeLayout#hb
       LinearLayout#coz → LinearLayout#coy
         RelativeLayout#jha desc=搜索        [864,1013]
         RelativeLayout#jga desc=更多功能按钮 [1013,1162]
           └ ImageView#h5n（两种图标都用这个 id）
         TextView "A"（我们插的）            [1162,1240]

B) 会话 ListView#j8g 的第一行：复刻标题栏的「假标题栏」
   RelativeLayout → FrameLayout#wk [0,114][1240,288] → View#wi → RelativeLayout#cg7
     LinearLayout#actionbar_up_indicator
     RelativeLayout#gp → TextView#text1 "微信(123)"
     LinearLayout → ImageView#meb [992,1066]     ← 幽灵 🔍
                 → ImageView#plus_icon [1116,1190] ← 幽灵 ＋
```

正常时 B 被 A 完全盖住；主题把标题栏做成透明后 B 透了出来，
又因为插入 `A` 让真标题栏整体左移约 78px，两层错位暴露，于是看起来像「两个 🔍 两个 ＋」。

## 4. 最终做法

1. **入口不交给宿主画**：`mountOwnEntry` 直接往真标题栏的横排容器
   （`findTitleContainer`：`desc=更多功能按钮` 锚点 → 上溯 `LinearLayout` 且必须位于
   `ActionBarContainer`/`Toolbar` 内）`addView` 一个自绘 `TextView`。
   `ALLOW_LEGACY_FALLBACK = false`：宁可不出现，也不污染标题栏。
2. **隐藏幽灵图标**：`hideGhostTitleIcons` 按资源 id 名 `meb` / `plus_icon` **全树收集**
   （不用 `findViewById`，它只返回第一个命中），只对「祖先含 `ListView`/`RecyclerView`」
   的实例置 `INVISIBLE`；真 ActionBar 的 `h5n` 一概不动。
   另外挂 `OnPreDrawListener`（`installGhostGuard`）只遍历缓存住的几个 View（常量级），
   宿主把它们重新 `setVisible` 时压回去。
3. **切页面后自动补挂**：`LauncherUI` 是四 Tab 共用容器，切页面时 ActionBar 会重建，
   我们插的 View 随旧容器一起丢弃，而这类切换**不会**触发 `onCreate` / `onResume` /
   `onPostResume`（所以以前只能靠「切后台再回来」）。改为在 `decorView` 上挂
   `OnPreDrawListener`（`installEntryGuard`）：每帧 O(1) 判断
   `ownEntryRef` 是否 `isAttachedToWindow && isShown`，不在就节流 500ms 重挂；
   并且只在 `homeListVisible()`（首页会话列表可见）时补挂，避免 `A` 跑到别的 Tab 上。
   `installInFlight` 保证同一时刻只有一条 500ms 重试链（此前切页面会叠出多条，
   日志被「没找到可插入的标题栏容器」刷满）。
4. **未分组会话行刷新**：分组内的行由我们的投影绘制，未分组行是微信原生 adapter 画的。
   批量动作后不仅要 `ConversationGroupHomeProjection.refresh()`，还要让原生列表
   `notifyDataSetChanged()`（反射调用，ListView / RecyclerView 都覆盖）。
   又因为群聊免打扰走 RoomSDK 异步提交、私聊走联系人更新（都是「先落库再回调」），
   单次刷新拿到的是中间态，所以按 0 / 300 / 900 / 2000 / 3500ms 多轮刷新。

## 5. 真机验证记录

| 版本 | 验证内容 | 结果 |
| --- | --- | --- |
| 1062 | 无过滤 UI 树里 `meb` / `plus_icon` 计数 | 0 / 0（幽灵图标已消失） |
| 1062 | UI 树里 `text="A"` | 1 个，`[1162,139][1240,288]`，`clickable=true` |
| 1062 | 点 `A` 后菜单项 | 7 项齐全 + 取消 |
| 1063 | 进聊天再返回首页后 `A` / 幽灵图标 | `A`=1，幽灵=0/0 |
| 1063 | 桌面图标/布局 | 未改动 |

## 6. 待用户确认

- 切到其它页面（通讯录 / 发现 / 我）再回微信首页，`A` 是否自动恢复；
- 对**未分组**会话开启 / 解除免打扰后，行内图标是否自动跟着变（多轮刷新窗口约 3.5 秒内）。
