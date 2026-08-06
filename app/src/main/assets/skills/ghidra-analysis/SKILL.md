---
name: ghidra-analysis
description: 逆向分析专家。当用户需要分析二进制、APK、SO、ELF、DEX 或固件时激活。只做用户拥有或明确授权文件的静态分析。
---

# 二进制与 APK 静态分析

## 分层策略

| 文件类型 | 工具 | 说明 |
|---|---|---|
| APK / DEX | jadx | 先读可读源码 |
| APK 资源 / smali | apktool | 查看资源和字节码 |
| SO / ELF / 固件 | Ghidra headless | 需要时再进入本层 |

原则是能用 jadx 读懂的不上 Ghidra；工具版本先探测，不写死。

输出应包含文件类型、关键函数或导出符号、行为说明、证据位置和局限。拒绝绕过检测、反作弊、支付风控或未授权控制。
