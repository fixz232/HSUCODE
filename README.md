# HSUCODE

**纯 Kotlin 原生 Android AI 智能体。** 一个运行在手机上的自主 AI Agent:多供应商接入、工具调用循环、子智能体并行协作、长期记忆、内置 root/Ubuntu 环境与终端,以及像素风「智能体指挥室」实时动画。原生 Android 工程,无跨端框架。

> 版本:**1.14**(versionCode 114) · 许可:**GPL-3.0-or-later**（第三方组件按各自许可证）

源码仓库：[fixz232/HSUCODE](https://github.com/fixz232/HSUCODE) · 默认分支：`main`

---

## 上游与修改声明

本项目基于 [kusesad-1122/XINCODE-Public](https://github.com/kusesad-1122/XINCODE-Public) 修改而来。上游仓库地址为 <https://github.com/kusesad-1122/XINCODE-Public>。

本项目部分功能和代码改编自 [AAswordman/Operit](https://github.com/AAswordman/Operit)（LGPL-3.0）与 [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub)（AGPL-3.0）。改编范围、固定提交、许可证全文、直接复制的资源和二进制清单见 [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) 与 [`NOTICE.md`](NOTICE.md)。

AGPL/LGPL 改编部分的对应源码、资源许可证和构建说明随 `main` 分支一起发布；Release 签名私钥不公开，修改后的构建需要使用自己的签名密钥安装。

- 修改记录与时间:见 [`MODIFICATIONS.md`](MODIFICATIONS.md)。
- 原始版权、许可证和作者声明均予以保留；不得删除 [`LICENSE`](LICENSE)、第三方许可证或源码中的既有归属声明。
- 当前修改包含加密全量备份与跨设备恢复、恢复流程集成、智能体任务状态恢复、智能体指挥室的运行控制与权限审批，以及统一模型中心和智能路由。

---

## ✨ 主要能力

- **智能体核心** — OpenAI 兼容 / DeepSeek / Anthropic 等多供应商,自定义端点与模型清单;工具调用循环 + 结构化输出 + Prompt 缓存纪律。
- **计划 / 协作模式** — 计划模式可视化任务卡;协作模式下主脑把任务并行派发给多个专职子智能体,汇总回主脑。
- **指挥室** — 像素风「智能体指挥室」(WebView + HTML5 Canvas),每个子智能体一个工位与像素小人,派活即联动动画。
- **环境与终端** — 默认提供免 Root Android Shell 工作区，Agent 可执行轻量任务并实时显示输出；Root 设备可部署完整 Ubuntu(root + chroot)与常用开发工具。
- **记忆** — 长期记忆(FTS 全文检索 + 向量语义检索,自动沉淀);普通对话之间**记忆互通**,项目内对话**按项目隔离**;精编两文件记忆(用户画像 / 近况)由后台复盘分身维护。
- **上下文与成本** — 输入框旁上下文圆环(绿→蓝→黄→红)、实时 token / 缓存命中、可配置压缩阈值、人民币成本显示(缓存感知)。
- **效率与自动化** — 多引擎联网搜索(必应 / 百度 / 搜狗 / DuckDuckGo 融合 + 正文抓取)、Goal/Work 多任务、定时任务(cron / WorkManager)、语音转写、视觉与深度推理委托副模型。
- **技能与 MCP** — 内置基础技能 + 技能管理,`/技能名` 主动调用;MCP 支持(stdio 本地传输 + HTTP),`@服务器名` 优先使用其工具。
- **安全** — 权限模式(询问 / 完全访问)、敏感操作确认卡、审计日志。

完整清单见 [`CHANGELOG.md`](CHANGELOG.md)。

---

## 🧱 工程结构

多模块 Gradle 工程(Kotlin DSL):

| 模块 | 职责 |
| --- | --- |
| `app` | Android 入口、Compose UI、智能体编排、指挥室 WebView |
| `core` | AgentCore、工具注册表、调度循环 |
| `provider` | 各 LLM 供应商客户端(OpenAI 兼容 / Anthropic 等) |
| `data` | Room 持久化、记忆(FTS + 向量)、记忆抽取 |
| `security` | 权限模式、敏感操作门控、审计 |
| `tools` | 内置工具实现 |
| `service` | 前台服务、后台任务、通知 |
| `ui` | 共享 UI 组件与主题 |

- 语言:Kotlin · UI:Jetpack Compose · 存储:Room
- `minSdk 28` · `targetSdk 34` · `compileSdk 34`

---

## 🔨 从源码构建

### Debug 包(无需签名密钥)

```bash
git clone https://github.com/fixz232/HSUCODE.git
cd HSUCODE
./gradlew :app:assembleDebug
# 产物:app/build/outputs/apk/debug/app-debug.apk
```

需要 JDK 17 与 Android SDK(Platform 34)。若本机未装 SDK,在项目根新建 `local.properties` 指向 SDK 路径:`sdk.dir=/path/to/Android/Sdk`。

### Release 签名包(在你自己的电脑上)

签名密钥是你的私有机密,**永远不进仓库**(`keystore.properties`、`*.jks`、`*.keystore` 均已在 `.gitignore` 中)。

1. 复制模板:`cp keystore.properties.example keystore.properties`
2. 若还没有密钥,生成一个:
   ```bash
   keytool -genkeypair -v -keystore hsucode-release.jks \
     -alias hsucode -keyalg RSA -keysize 2048 -validity 10000
   ```
3. 把 `keystore.properties` 里的四项填成你的真实值(`storeFile` / `storePassword` / `keyAlias` / `keyPassword`)。
4. 打签名包:
   ```bash
   ./gradlew :app:assembleRelease
   # 产物:app/build/outputs/apk/release/app-release.apk
   ```

> ⚠️ 请**离线备份** `.jks` 与这些口令——一旦丢失,将无法再为同一应用发布更新。
> 若 `keystore.properties` 不存在,构建脚本会自动跳过签名配置(Debug 仍可正常构建)。

---

## 🔐 权限说明

| 权限 | 用途 |
| --- | --- |
| `INTERNET` | 调用 LLM API、联网搜索、抓取正文 |
| `RECORD_AUDIO` | 语音转写(仅在你主动使用语音输入时) |
| `POST_NOTIFICATIONS` | 后台任务完成通知 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | 后台推进长任务(Goal/Work、定时任务) |

应用不含广告与第三方追踪。API Key 等敏感配置仅保存在本机;`android:allowBackup="false"` 已关闭系统自动备份。

---

## ⚙️ 使用前配置

首次启动后,进入设置填写你自己的 LLM 供应商信息(端点 + API Key + 模型)。HSUCODE 不内置任何托管密钥,所有请求都直连你配置的供应商。

---

## 📄 许可与第三方声明

- 本项目主体代码采用 **GNU General Public License v3.0 or later**(见 [`LICENSE`](LICENSE))。
- `codegraph-kernel` 原生库及其中的 Tree-sitter 语法源码按各自 MIT/上游许可证发布，许可证文件保留在 `codegraph-kernel/`。
- 改编自 Operit 和 RikkaHub 的部分代码、资源与二进制按其 LGPL-3.0 / AGPL-3.0 条款保留来源和再分发义务；具体范围以 [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) 为准。
- 打包/引用的第三方字体、像素素材与 Maven 依赖,依其各自许可使用,详见 [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) 与 [`licenses/`](licenses/)。
  - JetBrains Mono 字体 — SIL OFL 1.1
  - 角色 sprite(MetroCity)— CC0 1.0
  - 地板/家具/装饰/地毯(pixel-agents)— MIT

## 🙏 致谢

感谢以下项目、作者和社区提供的代码、素材、协议或公开设计思路。HSUCODE 与这些项目没有官方隶属、赞助或背书关系；具体改编文件、固定提交和许可证以 [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) 为准。

| 项目 / 作者 | 在 HSUCODE 中的用途 | 关系与许可证 |
| --- | --- | --- |
| [kusesad-1122/XINCODE-Public](https://github.com/kusesad-1122/XINCODE-Public) | 原始 Android Agent 基线、核心功能和历史代码 | 修改基础；原始版权与 GPL 声明保留 |
| [AAswordman/Operit](https://github.com/AAswordman/Operit) | 身份/角色卡、GitHub OAuth 设备流、部分智能体工作台能力 | 部分代码改编与功能参考；LGPL-3.0 |
| [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub) | 聊天主页与模型中心交互、供应商图标、免 Root 工作区、PRoot/PTY 接入 | 部分代码改编；资源和二进制按 AGPL-3.0 声明 |
| [termux/termux-app](https://github.com/termux/termux-app) | Termux terminal-emulator / terminal-view PTY 运行时 | 依赖其 GPLv3 与终端模拟器例外条款 |
| [CodeGraph / Tree-sitter](https://github.com/tree-sitter/tree-sitter) | 原生代码索引与多语言解析内核 | `codegraph-kernel` 自有代码 MIT；各语法和 Rust crate 按上游许可证 |
| [xai-org/grok](https://github.com/xai-org) | 工具调用循环、终端 Agent 交互思路 | 公开设计思路参考 |
| [NousResearch](https://github.com/NousResearch) Hermes 系列 | 自进化学习、精编记忆、工具 RPC 等设计思路 | 公开设计思路参考 |
| [pixel-agents-hq/pixel-agents](https://github.com/pixel-agents-hq/pixel-agents) | 像素办公室地板、家具、装饰和场景灵感 | 部分素材按 MIT 声明 |
| [JIK-A-4 / MetroCity](https://jik-a-4.itch.io/metrocity-free-topdown-character-pack) | 指挥室角色 sprite | CC0 1.0 |
| [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono) | 终端与模型 ID 等宽字体 | SIL Open Font License 1.1 |
| [Ubuntu](https://ubuntu.com/) | 免 Root PRoot 工作区的运行时 rootfs | 运行时从 Ubuntu 官方发布页下载并校验，不把 rootfs 打进仓库 |
| Android Open Source Project / AndroidX / Jetpack Compose | Android 平台、UI、Room 和后台任务基础设施 | 依各组件 Apache-2.0 等许可证 |

感谢所有上游维护者、贡献者和开源社区的工作。第三方许可证全文和归属信息随源码发布，不应从源码或发行包中删除。
