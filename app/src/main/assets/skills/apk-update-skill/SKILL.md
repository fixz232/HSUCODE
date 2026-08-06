---
name: apk-update-skill
description: APK 自动化更新管理。当用户要检查 APK 或模块是否有新版本、从 GitHub Release 下载、安装更新或清理残留时激活。
license: MIT
---

# APK 更新管理

## 工作流

1. 读取本地 versionCode/versionName。
2. 从 GitHub Releases API 获取最新 tag 和资产。
3. 对比版本；有 SHA-256 时必须校验。
4. 下载到临时目录后安装，最后清理临时文件。

安装失败时区分签名不一致、版本回退、磁盘空间和共享存储权限问题，并给出可验证的下一步。
