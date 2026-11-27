# 基线与回退点登记

> 规则：每次开新功能前，先在**当前可跑通的状态**上打一个回退点（tag + branch 同名，push 必须显式 refspec）。
> 回退只允许 `git reset --hard <tag>`，不允许 `git revert` 一串（会把上下文搅乱）。

## 现行清单

| 名字 | 提交 | 内容 | 用途 |
|---|---|---|---|
| `baseline-1028` | `c97a05c` | 无群管理的干净基线 | 对照用，**永不删** |
| `baseline-1042` | `43f2386` | 群管理移植完成、分组半修复 | 回到「群管理刚并进来」的状态 |
| `baseline-1047` | `9e74f4d` | 会话分组四条回归修好 + 兼容层 `setResult(null)` 修正（真机验收通过） | **代码基线**，与手机上 1047 APK 一致 |
| `rollback-1047` | 见 `git rev-parse rollback-1047` | 在 `baseline-1047` 之上再加文档（修复记录 + 空转审计备忘 + 本文件） | **开新功能前的回退点**（首页全局菜单之前） |

同名前缀说明：`baseline-*` 是「验收过的功能基线」，`rollback-*` 是「动手前的临时回退点」。
两者内容可能只差文档，回退时优先用 `baseline-*`。

## 常用命令

```bash
# 看某基线指向哪
cd /root/work/theme-710 && git rev-parse 'baseline-1047^{commit}'

# 回退到某个基线（会丢弃未提交改动，先确认 git status）
git reset --hard 'baseline-1047^{commit}'

# 打一个新的回退点（tag 与 branch 同名）
git tag -a rollback-YYYYMMDD -m '说明' HEAD
git branch -f rollback-YYYYMMDD HEAD
git push origin refs/heads/rollback-YYYYMMDD:refs/heads/rollback-YYYYMMDD \
                 refs/tags/rollback-YYYYMMDD:refs/tags/rollback-YYYYMMDD
```

> tag 与 branch 同名会让 `git log/rev-parse` 报 `refname is ambiguous`，
> 所以命令里统一写 `'名字^{commit}'` 或 `refs/tags/名字`。

## 配套 APK

| 版本 | 文件 | 说明 |
|---|---|---|
| 1047 | `/sdcard/Download/Toolbox-convgroup-1047.apk` | 与 `baseline-1047` 同一份代码构建（21,336,471 字节），分组四条回归已验收 |

> APK 与 tag 不是强绑定（workflow 的 `versionCode = run_number + 1000`），
> 但同一版调试时以「tag 对应代码 + 最近一次构建」为准，必要时重新 dispatch 构建来复现。

## 装机固定姿势（踩坑后定型）

```bash
cp /sdcard/Download/xxx.apk /data/local/tmp/xxx.apk && chmod 644 /data/local/tmp/xxx.apk
pm install -r --user 999 /data/local/tmp/xxx.apk
am force-stop --user 999 com.tencent.mm   # 装完必须重启微信进程，否则跑的还是旧代码
```

`/sdcard` 直装会报 `System server has no access to read file context u:object_r:fuse:s0`，不要试。