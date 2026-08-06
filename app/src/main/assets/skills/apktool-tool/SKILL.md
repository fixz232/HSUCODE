---
name: apktool-tool
description: APK 反编译与回编译工具。当用户需要查看资源、smali、AndroidManifest，或修改后重新打包时激活。
---

# APK 反编译与回编译

只处理用户自己拥有或明确授权修改的 APK，不绕过授权、付费、风控或安全校验。

```bash
apktool d <目标.apk> -o <输出目录> -f
apktool b <反编译目录> -o <新包.apk>
```

回编译后签名会失效。安装前需要用用户自己的签名重新签名，并验证包名、权限和签名状态。
