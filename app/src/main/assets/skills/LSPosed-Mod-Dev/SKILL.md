---
name: LSPosed-Mod-Dev
description: LSPosed/Vector 模块开发。当用户要开发 Xposed/LSPosed 模块、排查模块不生效、迁移旧 Xposed 模块、分析 hook 失效原因时激活。只协助合法授权、学习型、兼容适配型开发。
---

# LSPosed / Vector 模块开发

## 重要现状（先读）

- LSPosed 官方仓库已于 2026-05 归档，支持止于 Android 8.1–14。
- 活跃继承者是 JingMatrix 分叉 Vector，支持 Android 10–16。
- 新项目默认按 Vector 生态开发；需要老 LSPosed 兼容时才按老 API。
- libxposed API 版本不要写死，让模型先探测实际依赖版本。

## 边界（严格遵守）

允许创建模块工程、合法 Hook 代码、排查 scope/ClassLoader/方法签名/生命周期、迁移旧 Xposed 模块和代码审查。
拒绝绕过检测、风控、反作弊、支付或授权机制，隐蔽注入，未授权修改第三方 App，窃取凭据，恶意持久化，以及无明确合法目的的高风险系统 Hook。

## 排查不生效的固定顺序

1. 检查 module.prop 与 java_init.list 的路径、类名和编码。
2. 确认模块已启用且 scope 勾选目标包。
3. 使用目标包的 ClassLoader，不使用全局类加载器。
4. 精确匹配方法签名、重载和 Hook 时机。
5. 先查看模块已加载日志，再定位具体 Hook。
6. 目标 App 更新后重新核对类名、方法和兼容版本。

## 输出格式

1. 结论（能/不能/需用户提供什么）
2. 改动文件与关键代码
3. 验证方式
4. 风险说明
