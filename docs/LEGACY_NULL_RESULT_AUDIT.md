# 备忘：`param.result = null` 空转审计清单（暂不动，以后再修）

> 记录时间：2026-11-27。**当前状态：只登记，不改任何代码。**
> 背景：`baseline-1047` 修好了会话分组的四条回归，根因是兼容层 `isOverridden()` 只看
> `result != null`，导致 `param.result = null` 不跳过原方法（详见
> `docs/CONVERSATION_GROUP_EXIT_1047.md`）。分组目录已改成 `param.setResult(null)` 并真机验收；
> 本文件登记**其余目录**里同类写法的遗留风险。

## 一、总量

全模块（`app/src/main/java`）匹配 `param.result = null` 共 **53** 处，剔除 2 处（兼容层注释里的文字）
后为 **51 处真实代码**：

- **A 类「空转无害」15 处**：钩的是 void 菜单回调，原生 `default:` 分支本来就不做事；
  清理后行为应当不变，属于「把假拦截变真拦截」的代码卫生。
- **B 类「空转 = 功能实际没生效」31 处**：包括打字上报屏蔽、通话铃声屏蔽、表情游戏、
  消息拦截、静默通知/DND、收藏语音转发、设置页返回键等，属于**用户可感知但一直没生效**的遗留。
- **C 类「禁止改动」5 处**：构造函数、after 阶段的 Bitmap 返回、冻结项、故意的兜底分支。

> 注意：这 51 处**不是死代码**。改成 `setResult` 等于把该功能从「关闭」切到「开启」，
> 必须按功能逐项验证，不能批量替换。

## 二、A 类：空转无害，可选清理（15 处）

判定依据：被钩方法为 `onMMMenuItemSelected(MenuItem, int)` 等 **void 菜单回调**，
我们自己注册的 `itemId` 走原生 `switch` 的 `default:`，原生不会做任何事。

| 文件 | 行 |
|---|---|
| `hooks/items/grouplabel/QuickGroupChatLabelFeature.kt` | 152 |
| `hooks/items/quickmoments/QuickMomentsFeature.kt` | 194 |
| `hooks/items/quickcontactedit/QuickContactEditFeature.kt` | 440 |
| `hooks/items/messageforward/MessageForwardFeature.kt` | 271, 343 |
| `hooks/items/script/ScriptMenuDispatcher.kt` | 387, 422 |
| `hooks/items/shortvideo/FinderMediaDownloadFeature.kt` | 266, 274 |
| `hooks/items/selectedmessages/SelectedMessagesFeature.kt` | 150 |
| `hooks/items/multirecall/MultiRecallFeature.kt` | 96, 102, 113 |
| `hooks/items/customfriendavatar/CustomFriendAvatarFeature.kt` | 312 |
| `hooks/items/swipequote/SwipeQuoteFeature.kt` | 422 |

改法：`param.setResult(null)`。验证：每项点一次菜单，确认没有多弹一个原生对话框/多进一个页面。

## 三、B 类：空转 = 功能没生效（31 处，逐项单独出包）

| 功能 | 位置 | 现状推测 | 正确改法要点 |
|---|---|---|---|
| 屏蔽「正在输入」 | `typingreport/TypingReportBlockFeature.kt:76` | **没生效**（`sendTyping` 为 void） | `setResult(null)`，验证对方端不再看到「正在输入」 |
| 通话铃声屏蔽 | `callmedialimit/CallRingtoneBlockFeature.kt:102, 121` | **没生效** | 需先探明两处返回类型；102 在 `onSuccess` 回调里，可能是 void；121 可能是 boolean（→`false`） |
| 表情游戏拦截 | `gameemoji/GameEmojiFeature.kt:110, 114` | **没拦**（若返回 boolean 则空转后原生照跑） | 先探返回类型：boolean→`false`；void→`setResult(null)`；同时确认 `pending` 逻辑否则会重复弹窗 |
| 消息拦截 | `messageblock/MessageBlockFeature.kt:63` | 存疑（要看它是否另有「先入库再删」的兜底） | 先确认返回类型与后续 `postBlockedMessage` 语义，**不能**让它变成「拦截了但库里也删了」 |
| 通知静默 / DND | `customnotify/CustomNotificationRuntime.kt:193, 205, 210, 217, 220, 264, 278, 355` | **大概率没压掉** | 这几处原方法多返回 `Notification`/`RemoteViews`，**禁止返 null**；应在更上游 void 入口拦，或调用宿主「取消通知」API |
| 收藏语音转发 | `voiceforward/VoiceForwardFeature.kt:707, 712, 720, 744, 750, 755, 777, 793, 799, 805, 844` | 菜单项是我们自己的 id，原生 default 不做事，但中途 `return` 时没真正吞掉 | 逐处判断是「已处理完要吞掉」还是「只是提前 return」；前者 `setResult(null)`，后者不动 |
| 朋友圈去广告 | `moments/RemoveMomentsAdsFeature.kt:62` | 钩的是**构造函数**，`setResult` 非法 | 只能用 `setThrowable` 或改钩生产/填充链上的上层方法；**不要**换 `setResult` |
| 伪转发 / 伪互动 | `momentsfake/MomentsFakeForwardRuntime.kt:829, 890`、`MomentsFakeInteractionRuntime.kt:326` | 多为「别走原生删除/转发分支」的保护性写法 | 改前先确认原方法返回类型；890 的注释明确是「失败时别落到服务端删除」，属高风险逻辑 |
| 脚本发送按钮 | `script/ScriptSendButtonHook.kt:144` | 吞发送动作以走自定义链路 | **高风险**：返回类型若不匹配，可能出现「发两遍」或「输入框没清」。改前必须逐行读整段链路 |
| 设置页返回键 | `ui/miuix/MiuixSettingsPage.kt:1149` | **没吞掉**，`handleBack()` 之后原生返回仍执行 | 与分组同款「多余一层/要按两次」风险；`setResult(null)`（`onBackPressed` 是 void） |

## 四、C 类：禁止改动（5 处）

| 位置 | 原因 |
|---|---|
| `customfriendavatar/CustomFriendAvatarFeature.kt:198` | **after 阶段**，原方法返回 Bitmap；真正生效的是 `setImageBitmap`，这里空转正是想要的 |
| `customfriendavatar/CustomFriendAvatarFeature.kt:259` | 同上（`Drawable.draw` 路径） |
| `theme/ThemeFeature.kt:9168` | 属冻结清单范围（渐变绘制）；有 `willNotDraw` 兜底在生效，动了可能出白块 |
| `conversationgroup/ConversationGroupRuntime.kt:1178` | 故意的 `else ->` 兜底：未知返回类型时宁可空转，避免空拆箱 |
| `moments/RemoveMomentsAdsFeature.kt:62`（**构造函数**） | Xposed 不允许对构造函数 `setResult`（会抛 `UnsupportedOperationException`）；归入 B 类的「另找入口」处理 |

## 五、以后修复的流程（不要跳步）

1. **先探返回类型，不猜**：对每个待改点用一次性探针打印
   `method.toGenericString() + method.returnType`，只读不改。
2. **按类给值**：
   - `void` → `param.setResult(null)`
   - `boolean` → `param.setResult(false)`
   - `int/long` → `0` / `0L`
   - **对象类型（Notification/Bitmap/Cursor/Intent…）→ 不准返 null**，改钩更上游的 void 入口
   - **构造函数 → 不准 setResult**
3. **一次只动一个功能，出一版测试包**，用 `theme-999-test` workflow 装到 user 999 验证；
   通过后再动下一个，避免「一起改完出事不知道是谁」。
4. **建议顺序（风险从低到高）**：A 类 15 处 → 设置页返回键 → 打字上报 → 表情游戏 →
   通话铃声 → 消息拦截 → 收藏语音 → 伪转发/伪互动 → 通知静默（最难，最后做）。
5. 每一项都要写进 `docs/TODO.md` 的「已闭环」并注明验证方式，不要只留代码改动。

## 六、禁止做法

- ❌ 全文件 `sed` 一把梭：会把 C 类和「提前 return」的语义一起改掉。
- ❌ 对返回对象的方法返 `null`：宿主拆箱/空判会直接崩（历史上 693 版就是 `Boolean` 空拆箱崩的）。
- ❌ 对构造函数 `setResult`。
- ❌ 在没探明返回类型前就改 B 类任何一条。
- ❌ 顺手「统一风格」把 A 类和 B 类混在一起提交。

## 七、相关记录

- 根因与修复：`docs/CONVERSATION_GROUP_EXIT_1047.md`
- 基线：`baseline-1047` = `9e74f4d`（含本备忘的提交后续会更新 hash）
- 兼容层位置：`app/src/main/java/com/aeonlab/toolkit/xposed/compat/XC_MethodHook.kt`
  （`returnEarly` 标志已在 `adbcbc9` 加入）