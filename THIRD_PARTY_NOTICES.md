# 第三方来源与许可说明（THIRD_PARTY_NOTICES）

本文件记录本模块（`com.aeonlab.toolkit`，自用共存包 `com.aeonlab.toolkit.coexist`）的来源与第三方组件信息，
用于标注出处、避免来源纠纷。所有字段均取自上游公开仓库的实测结果、本仓库源码与移植规格原文，未作推测。

## 1 上游原作者项目（模块本体来源）

| 项 | 值 | 实测依据（2026-11-06） |
| --- | --- | --- |
| 项目名 | Hchat（alt-entry 单频道） | 上游 README 标题 `# Hchat alt-entry`；仓库名 `Hchat-alt-entry` |
| 原作者署名 | 「。。」（真实署名，逐字两个全角句号 U+3002 U+3002，非占位符）＋ GitHub 账号 `ljh520134` | 署名取值依据：本仓库维护者 2026-11-06 确认；账号取自上游仓库元数据 `owner.login`（GitHub 资料页 `name` 字段为空） |
| 上游仓库地址 | https://github.com/ljh520134/Hchat-alt-entry | 上游仓库元数据 `full_name` / `html_url` |
| 默认分支 | `alt-entry` | 上游仓库元数据 `default_branch` |
| 项目说明 | 「Hchat alt-entry 单频道参考源码」 | 上游仓库元数据 `description` |
| 许可证 | 上游未提供任何许可证文件：`LICENSE`/`LICENSE.md`/`LICENSE.txt`/`COPYING`/`NOTICE` 均 404，GitHub 元数据 `license: null` | 逐名实测 HTTP 状态与元数据 |
| 上游第三方目录 | 上游根目录含 `third_party/`（内容为 `dexkit-memory`） | 上游仓库目录接口 |

**本仓库是上述项目的派生（fork），不是原作者发布物。** 见下一节。

## 2 本 fork 与「自用修改版」声明

| 项 | 值 | 实测依据 |
| --- | --- | --- |
| 本 fork 仓库 | https://github.com/nandeh899-prog/Hchat-alt-entry | 仓库元数据 `full_name`/`html_url`，`fork: true` |
| 上游父仓库 | `ljh520134/Hchat-alt-entry` | 仓库元数据 `parent.full_name` |
| 本 fork 默认分支 | `alt-entry` | 仓库元数据 `default_branch` |
| 本模块（共存包） | `com.aeonlab.toolkit.coexist`，基于上游源码的自用修改版 | 本仓库 `app/build.gradle.kts`（namespace/applicationId） |

**声明**：本修改版（含 `groupadmin` 群管理功能、去特征改动与署名改动）由 fork 维护者自行制作，
**非上游原作者发布，也未获其背书**。模块内「致谢」卡片顶部「上游项目 / 上游仓库 / 本版本」三行
即为此声明的可见入口；其中「上游项目」行文案为「Hchat alt-entry · 。。 · ljh520134」，
「本版本」行文案为「基于上游的自用修改版，非原作者发布」。

本模块的 `groupadmin` 群管理功能由本仓库维护者自行编写（AI 辅助），**不是任何第三方的插件移植产物**，
因此不在本文件中作为外部来源署名。

## 3 第三方组件（原「致谢」清单，保持不动）

| 组件 | 作者 / 维护者 | 地址 |
| --- | --- | --- |
| KavaRef | HighCapable | https://github.com/HighCapable/KavaRef |
| DexKit | LuckyPray | https://github.com/LuckyPray/DexKit |
| FastKV | BillyWei01 | https://github.com/BillyWei01/FastKV |
| WeChat Pad | lovejiuwu | https://github.com/Xposed-Modules-Repo/top.hookvip.wxtablet |
| LSPosed | LSPosed | https://github.com/LSPosed/LSPosed |
| Miuix | YuKongA | https://github.com/compose-miuix-ui/miuix |
| BeanShell-Android | CopyLibs | https://github.com/CopyLibs/BeanShell-Android |
| Silk Codec | YunJavaPro | https://github.com/YunJavaPro/Silk-Codec-Android |
| AndroidLiquidGlass | Kyant0 | https://github.com/Kyant0/AndroidLiquidGlass（`ui/miuix/liquid/CombinedBackdrop.kt`、`InnerShadow.kt` 文件头注明 Adapted from） |
| BeanShell 内置 ASM | objectweb | 许可证原文保留于 `app/src/main/java/bsh/org/objectweb/asm/LICENSE.txt` |

上游 `third_party/`（`dexkit-memory`）未包含在本分支快照中；如需其许可文本，请从上游仓库对应目录获取。

## 4 去特征与边界声明（与署名改动的关系）

本次署名改动**未**恢复上游任何一项被去特征的内容：上游更新检查（进入设置页即查询更新并弹窗导流）、
导流弹窗、Telegram 入口、任何联网上报。署名行只使用 GitHub 地址，且点击行为与既有「致谢」各组件行一致
（调用既有 `openExternalLink` 打开系统浏览器），不触发任何后台请求。
规则判定、消息处理、UI 导航均未改动；`hooks/items/script/market/PluginMarketSettings.kt` 的插件市场链路保持原样。

## 5 仍缺失、需维护者补充的字段

- 本仓库自身根目录的 LICENSE 文件：上游与本 fork 均无许可证文件，是否添加及采用何种条款由维护者决定（本文件不代为声明）。
