# NOTICE

本仓库是个人自用构建，代码**衍生自 Hchat（`alt-entry` 频道）的公开源码快照**。

## 上游来源

- 上游仓库：`https://github.com/ljh520134/Hchat-alt-entry`
- 上游默认分支：`alt-entry`
- 本仓库（`nandeh899-prog/Hchat-alt-entry`）为上游的 fork，所有修改仅存在于本 fork
- 上游代码与资源的原始版权归原作者及贡献者所有

## 授权状态

上游公开快照根目录**未附带 LICENSE 文件**。上游 README「许可证和第三方代码」一节明确说明：

> 准备再分发源码、发布二次修改版或合并大规模第三方代码前，请先确认项目作者和各第三方依赖的授权要求。仓库中的部分代码和资源保留各自的版权声明，不能因为仓库公开就默认获得任意再分发权。

因此本仓库**不主张对上游代码的任何额外授权**。若要再分发源码或发布二次修改版，请先自行取得上游授权。

## 本 fork 的修改范围

改动仅用于**个人设备自用**，不公开发布、不上传插件市场：

1. 包名改为 `com.aeonlab.toolkit`，可与官方 Hchat 本体共存
2. 去除 Hchat 命名、图标、文案、日志标识、类名与资源名等可识别特征
3. 迁移到 libxposed API 102 现代入口（保留 legacy 回退）
4. 修复「消息显示时间」安装误报、「自动抢礼物」静默等运行问题
5. 构建与签名加固：Actions 触发者校验、pre-push 守卫、凭据规范（见 `SECURITY.md`）

## 有意保留的 Hchat 标识

下列标识**保持不变**，因为它们是插件生态的公共坐标或与已部署环境绑定，改名会破坏现有功能：

- `Android/media/<微信包名>/Hchat/脚本插件/` —— 脚本插件根目录；插件源码会扫描该目录进行互相发现
- `Hchat/proot_env/` —— proot 环境根（已部署的 Ubuntu 环境）
- `Hchat_termux/` —— termux native 库解压目录
- 部分 `hchat_*` 配置键与 `hchat.*` AI 工具名 —— 与既有配置数据、插件提示词契约绑定

## 第三方组件

上游已包含并遵循各自许可的第三方代码与二进制，包括但不限于：

- BeanShell（`bsh`）解释器
- DexKit / DexFinder 定位引擎
- Miuix UI 组件
- termux / proot（`libproot.so`、`libloader.so`、`liblink2symlink.so`）
- silk 语音编解码（`libsilk_codec.so`）
- fastjson2 及其他依赖

这些组件的许可与版权归各自作者所有，本仓库不对其做任何再授权主张。
