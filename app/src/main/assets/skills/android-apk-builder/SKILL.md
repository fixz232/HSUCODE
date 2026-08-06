---
name: android-apk-builder
description: 在终端中为 Android/Gradle 项目编译 Debug/Release APK 与 AAB。当用户要构建 APK、运行 Gradle、定位构建产物或安装到设备时激活。
---

# Android APK / AAB 构建

## 工作流

1. 先检查 settings.gradle、Gradle wrapper、JDK 17+ 和 Android SDK。
2. 根据项目配置选择 `:app:assembleDebug`、`:app:assembleRelease` 或 `:app:bundleRelease`。
3. 构建失败时读取第一个真实错误，不反复重试同一命令。
4. 输出 APK/AAB 的绝对路径和大小。

```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:assembleRelease
./gradlew :app:bundleRelease
```

## 安全注意

- 不修改 Gradle 配置来强行让构建变绿。
- 不打印或提交 keystore.properties、JKS 和 API 密钥。
- 正式包安装前说明签名状态，用户自己的密钥只在本地使用。
