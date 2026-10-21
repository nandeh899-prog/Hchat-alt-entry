# 安全说明（SECURITY）

本仓库用于构建 `com.aeonlab.toolkit`（Hchat 定制分支，可与官方 `h.Hchat` 共存）。
因为构建产物是 **Root/Xposed 模块 + 签名 APK**，仓库与凭据一旦失控，后果不可逆，故记录如下约定。

---

## 1. 敏感资产

| 资产 | 位置 | 说明 |
|---|---|---|
| 签名密钥库（keystore） | GitHub Actions secret `HCAT_KEYSTORE_BASE64` | base64 后的 `.jks` |
| 密钥别名 | secret `HCAT_KEY_ALIAS` | |
| 密钥口令 | secret `HCAT_KEY_PASSWORD` | |
| 密钥库口令 | secret `HCAT_STORE_PASSWORD` | |
| 构建触发 token | 本机 `/root/.ghtoken`（**不入库**） | 仅本机使用 |

> **keystore 是最高价值资产**：泄露后他人可用同一签名签发 APK，安卓会视作"同应用正版更新"。
> 轮换签名意味着所有已安装用户签名不匹配、必须卸载重装，**不可逆**，因此宁可不泄露。

---

## 2. Token 规范

1. 使用 **classic PAT，仅勾选 `repo` + `workflow`**（或 fine-grained：Contents / Actions / Workflows = Read and write，且只授权本仓库）；
2. **必须设置有效期**（建议 90 天），不要 `No expiration`；
3. 存放：`printf '%s' 'TOKEN' > /root/.ghtoken && chmod 600 /root/.ghtoken`；
4. git 走 credential helper 读取，**URL 中绝不内嵌 token**：
   ```bash
   git remote set-url origin https://github.com/nandeh899-prog/Hchat-alt-entry.git
   git config --local credential.helper '!f() { echo username=x-access-token; echo password=$(cat /root/.ghtoken); }; f'
   ```
5. 任何脚本/命令中使用 `$(cat /root/.ghtoken)`，**不要 echo、不要 head 预览、不要截图含 token 的页面**。

---

## 3. 两道闸门

### 3.1 云端：workflow 触发者校验

`android-test.yml` / `android-single.yml` 的第一个 step：

```yaml
      - name: 触发者与分支校验
        run: |
          set -e
          allowed_actor="nandeh899-prog"
          if [ "${GITHUB_ACTOR}" != "$allowed_actor" ]; then
            echo "::error::仅允许仓库所有者触发构建，当前触发者: ${GITHUB_ACTOR}"
            exit 1
          fi
          case "${GITHUB_REF_NAME}" in
            alt-entry|api102|main|master|v*) ;;
            *) echo "::error::不允许的分支: ${GITHUB_REF_NAME}"; exit 1 ;;
          esac
```

作用：即便 token 泄露、攻击者能 push，也无法用其自身账号触发构建；分支指向异常同样被拒。

### 3.2 本地：pre-push 守卫（不随仓库同步，换机需重建）

文件 `.git/hooks/pre-push`（`chmod +x`，并 `git config --local core.hooksPath .git/hooks`）：

```sh
#!/bin/sh
remote="$1"
while read -r local_ref local_sha remote_ref remote_sha; do
  [ -z "$local_sha" ] && continue
  case "$local_sha" in *[!0]*) ;; *) continue ;; esac
  if [ "$remote_sha" = "0000000000000000000000000000000000000000" ]; then
    base=$(git rev-list --max-parents=0 "$local_sha" 2>/dev/null | tail -1)
  else
    base="$remote_sha"
  fi
  [ -z "$base" ] && continue
  changed=$(git diff --name-only "$base" "$local_sha" 2>/dev/null)
  hit=$(printf '%s\n' "$changed" | grep -E '^(\.github/|app/build\.gradle\.kts|gradle\.properties|app/keystore/)|\.(jks|keystore)$' 2>/dev/null)
  if [ -n "$hit" ]; then
    echo "⚠️  [AeonLab 推送守卫] 本次推送改动了敏感文件："
    printf '%s\n' "$hit" | sed 's/^/      /'
    if [ -z "$AeonLab_ALLOW_SENSITIVE" ]; then
      echo "    已阻止推送。确认无误后用：AeonLab_ALLOW_SENSITIVE=1 git push"
      exit 1
    fi
    echo "    （已通过 AeonLab_ALLOW_SENSITIVE 放行）"
  fi
done
exit 0
```

覆盖范围：`.github/`、`app/build.gradle.kts`、`gradle.properties`、`app/keystore/`、`*.jks`、`*.keystore`。

---

## 4. 日常操作清单

- 触发构建 / 下载产物后，顺手清一次设备日志：`logcat -c`（宿主会把完整模型请求体写入 logcat，敏感串会跟着进去）；
- 不在对话、issue、截图中粘贴 token 或密钥；
- 定期核对仓库 secrets 名称与最近 commit 是否只有自己的提交：

  ```bash
  curl -s -H "Authorization: token $(cat /root/.ghtoken)" \
    "https://api.github.com/repos/nandeh899-prog/Hchat-alt-entry/commits?per_page=5" \
    | grep -E '"(sha|message)"'
  ```

---

## 5. 应急响应

### 5.1 token 疑似泄露

1. **先建新 token**（最小权限 + 有效期）；
2. 写入 `/root/.ghtoken` 并验证：`git ls-remote` / `git push --dry-run` / dispatch 一次（可立刻 cancel）；
3. 验证通过后**再吊销旧 token**（Settings → Developer settings → Personal access tokens）；
4. 复查：`.git/config` 无内嵌凭据、`logcat -c`、仓库 commit 历史无异常。

### 5.2 workflow 被篡改并执行过

1. 逐一 rotate 4 个 secrets（keystore 需重新生成，代价见 §1）；
2. 检查 Actions 运行记录与 release/tag 是否被创建；
3. 用 `git log -- .github/` 定位改动提交，revert。

### 5.3 产物可疑

构建产物仅在本地下载安装，安装前可比对：APK 的 `META-INF/xposed/module.prop`、`res` 中图标像素指纹、以及 `versionName`（`test.<run_number>` 与运行号对应）。

---

## 6. 与"去 Hchat 特征"相关的保留项

以下路径**故意保留** `Hchat` 命名，改动会破坏插件生态，勿随意"去特征"：

| 保留项 | 原因 |
|---|---|
| `/sdcard/Android/media/com.tencent.mm/Hchat/脚本插件/` | 插件实体目录，大量插件脚本内硬编码该绝对路径 |
| `<dataDir>/Hchat/proot_env` | proot Linux 环境根目录，递归迁移风险极高 |
| `Hchat_termux`（native 解压目录） | termux 二进制解压路径 |
| `SettingsInjector.MODULE_TAG = "Hchat"` | 注入用 Intent extra key，与宿主交互协议 |

已迁移项：FastKV 配置目录 `<dataDir>/Hchat` → `<dataDir>/AeonLab`，并带一次性自动迁移（复制不移动、全成功才切换、失败回落旧目录）。
