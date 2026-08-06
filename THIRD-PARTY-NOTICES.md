# 第三方声明 / Third-Party Notices

HSUCODE 主体代码采用 **GPL-3.0-or-later** 许可(见 LICENSE)。除此之外,以下第三方素材与依赖被打包/引用,依其各自许可协议使用与再分发。

## 字体 / Fonts

- **JetBrains Mono** — Copyright 2020 The JetBrains Mono Project Authors。
  - 许可:SIL Open Font License 1.1。
  - 全文:[`licenses/JetBrainsMono-OFL-1.1.txt`](licenses/JetBrainsMono-OFL-1.1.txt)
  - 位置:`app/src/main/res/font/jetbrains_mono.ttf`

## 像素素材 / Pixel Assets

- **角色 sprite(characters)** — JIK-A-4「MetroCity」免费 topdown 角色包(https://jik-a-4.itch.io/metrocity-free-topdown-character-pack)。
  - 许可:Creative Commons Zero v1.0 Universal(CC0)。
  - 全文:[`licenses/MetroCity-CC0.txt`](licenses/MetroCity-CC0.txt)
  - 位置:`app/src/main/assets/pixel/characters/`

- **地板 / 家具 / 装饰 / 地毯** — 来自 pixel-agents(https://github.com/pixel-agents-hq/pixel-agents),Copyright (c) 2026 Pablo De Lucca。
  - 许可:MIT License。
  - 全文:[`licenses/pixel-agents-MIT.txt`](licenses/pixel-agents-MIT.txt)
  - 位置:`app/src/main/assets/pixel/floors/`、`furniture/`、`decor/`、`carpets/`
  - 详见 [`app/src/main/assets/pixel/ATTRIBUTION.txt`](app/src/main/assets/pixel/ATTRIBUTION.txt)

## 供应商图标 / Provider Icons

- **RikkaHub provider icon assets** — copied from [`rikkahub/rikkahub`](https://github.com/rikkahub/rikkahub) commit `4b6449e37854809302cdf5f1e72231b420f57382`.
  - 许可:GNU Affero General Public License v3.0。
  - 全文:[`licenses/RikkaHub-AGPL-3.0.txt`](licenses/RikkaHub-AGPL-3.0.txt)
  - 位置:`app/src/main/assets/provider_icons/`
  - 引入文件:`claude-color.svg`、`deepseek-color.svg`、`gemini-color.svg`、`kimi-color.svg`、`longcat-color.svg`、`minimax-color.svg`、`nvidia-color.svg`、`ollama.svg`、`openai.svg`、`openrouter.svg`、`qwen-color.svg`、`siliconflow.svg`、`stepfun-color.svg`、`vercel.svg`、`xai.svg`、`xiaomimimo.svg`、`zhipu-color.svg`。
  - 这些标识可能另受各对应供应商的商标规则约束；仅用于在用户配置中识别相应服务，不表示与该供应商存在关联或认可关系。

## 改编代码与功能参考 / Adapted Code and Feature References

除上面列明的直接复制素材和二进制外，HSUCODE 还包含下列上游项目的改编代码或实现参考。改编部分保留来源说明，并按对应许可证随本仓库提供。

### Operit

- 项目：[AAswordman/Operit](https://github.com/AAswordman/Operit)
- 参考提交：`4d753eb024b420df2a0f32f7a748bf1a8f61d0d4`（2026-08-06）
- 许可证：GNU Lesser General Public License v3.0（LGPL-3.0）；全文见 [`licenses/Operit-LGPL-3.0.txt`](licenses/Operit-LGPL-3.0.txt)。
- HSUCODE 中基于其公开实现改编或参考的重点包括 GitHub OAuth 设备流、身份/角色卡字段，以及智能体、Skill、MCP 工作台的部分交互和能力组织方式。对应适配位置包括 `app/src/main/java/com/hsucode/app/GithubAuth.kt`、`data/src/main/java/com/hsucode/data/IdentityEntity.kt` 及相关 app/provider 层代码。
- 这些代码已适配 HSUCODE 的包名、Room 数据层、权限模型和 Compose UI；没有把 Operit 整个仓库作为子模块或依赖打包进应用。Operit 的原始版权和 LGPL 条款继续适用于其改编部分。

### RikkaHub

- 项目：[rikkahub/rikkahub](https://github.com/rikkahub/rikkahub)
- 参考提交：`4b6449e37854809302cdf5f1e72231b420f57382`。
- 许可证：GNU Affero General Public License v3.0（AGPL-3.0）；全文见 [`licenses/RikkaHub-AGPL-3.0.txt`](licenses/RikkaHub-AGPL-3.0.txt)。
- 除供应商 SVG、PRoot 启动器和 Termux 终端依赖外，HSUCODE 还改编了其公开的聊天主页布局、供应商选择体验、免 Root 工作区/PTY 接入思路。相关适配位置包括 `app/src/main/java/com/hsucode/app/ChatScreen.kt`、`ProviderLogo.kt`、`ModelCenterScreen.kt`、`SupplierConfigScreen.kt`、`ModelMarketScreen.kt`、`TermuxPtyTerminal.kt`、`TermuxPtySessionManager.kt` 和 `ProotLinuxEnvironment.kt`。
- HSUCODE 对上述部分做了 Kotlin 包名、Room 数据模型、权限审批、错误处理和 UI 结构改写；直接复制的资源、二进制、SHA-256 与源文件位置在本文件对应章节中逐项列出。发行包含这些部分的版本时，应同时遵守 AGPL-3.0 的源代码和许可证义务。

## PRoot Ubuntu 工作区 / PRoot Ubuntu Workspace

- **RikkaHub PRoot launcher binaries** — copied from [`rikkahub/rikkahub`](https://github.com/rikkahub/rikkahub) commit `4b6449e37854809302cdf5f1e72231b420f57382`, `workspace/src/main/jniLibs/`.
  - 许可:GNU Affero General Public License v3.0。
  - 全文:[`licenses/RikkaHub-AGPL-3.0.txt`](licenses/RikkaHub-AGPL-3.0.txt)；对应源代码可从上述固定提交取得。
  - 位置:`app/src/main/jniLibs/arm64-v8a/` 与 `app/src/main/jniLibs/x86_64/`。
  - SHA-256:`arm64-v8a/libproot_exec.so` `d4ffbd19e20614c908be774af5dcd9da306094482f556713db037563c353219c`；`arm64-v8a/libproot_loader.so` `44ef39c1e1a18c09f6e4c4b5d6f8bba82d30596598bd155ec162d05c5122ff04`；`x86_64/libproot_exec.so` `0fb53e69e939b8d50cdd73af9936a7f2972849ebe6827ef8d5176cfabc41122d`；`x86_64/libproot_loader.so` `914564ea1c66f50b38f18cac857fcf814c6b1ab027789178880fca1d530599b3`。
  - HSUCODE 的安装、校验、解压、状态与 UI 接入代码位于 `app/src/main/java/com/hsucode/app/ProotLinuxEnvironment.kt`，基于该项目公开的 PRoot 工作区行为独立适配；Ubuntu rootfs 由用户设备首次运行时从 Ubuntu 官方发布页下载，并以官方 `SHA256SUMS` 校验。

## Termux PTY 终端

- **Termux terminal-emulator / terminal-view 0.118.0** — built from the
  `termux/termux-app` v0.118.0 source tree
  ([source](https://github.com/termux/termux-app/tree/v0.118.0)). The terminal
  emulator is GPLv3-only with the upstream Apache-2.0 terminal-emulator
  exception; see [`licenses/Termux-GPL-3.0.md`](licenses/Termux-GPL-3.0.md)
  and the upstream source notices.
  - Bundled artifacts: `app/libs/terminal-emulator-0.118.0.aar` and
    `app/libs/terminal-view-0.118.0.aar`.
  - SHA-256: `0EBBE04A201640FD2F2884317D8549C62178994808A9194BBF64EF60F94A9917`
    (terminal-emulator), `6770F35C340E19E8EA8954D1C904235F9278387EA9486660FBF59B18BA42C446`
    (terminal-view).
  - The AAR includes the upstream `libtermux.so` PTY JNI library. HSUCODE's
    `TermuxPtyTerminal.kt` and `ProotLinuxEnvironment.kt` provide the app-side
    session wiring and do not copy the upstream Kotlin UI implementation.

## Maven 依赖 / Maven Dependencies

- **AndroidX & Jetpack Compose**(activity-compose、material3、compose-ui、room、work-runtime-ktx 等)、**Kotlin stdlib / coroutines**、**OkHttp** — Apache License 2.0
- **libsu**(com.github.topjohnwu.libsu:core) — Apache License 2.0
- **Rhino**(org.mozilla:rhino:1.7.14) — Mozilla Public License 2.0(弱 copyleft:仅依赖使用只需归属;若修改 Rhino 源码本身需按 MPL 披露被改文件)
- **AndroidSVG**(com.caverock:androidsvg-aar:1.4) — Apache License 2.0
- **jsoup**(如引用) — MIT License

各依赖具体版本以 `app/build.gradle.kts`、模块 `build.gradle.kts` 为准。

## 启动图标 / Launcher Icon

`app/src/main/res/mipmap-*/ic_launcher.png` 与 `ic_launcher_round.png` 由项目作者提供,版权与授权与项目主体一致(GPL-3.0-or-later)。

## 致谢 / Acknowledgements

HSUCODE 在设计过程中参考了多个开源 AI 智能体的公开设计思路,以下项目对本工程的架构与能力选型有启发,谨此致敬:

- [xai-org/grok](https://github.com/xai-org)(工具调用循环与终端 agent 交互思路)
- [NousResearch](https://github.com/NousResearch)Hermes 系列(自进化学习闭环、精编记忆、零上下文工具-RPC 等设计思路)
- [pixel-agents-hq/pixel-agents](https://github.com/pixel-agents-hq/pixel-agents)(像素办公室场景灵感与素材来源)

HSUCODE 为纯 Kotlin 原生 Android 实现,并未搬运/移植上述任何项目的代码——上述致谢仅表明部分能力思路受其启发(inspired by)。
