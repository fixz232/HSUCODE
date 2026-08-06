# HSUCODE 开源声明

HSUCODE 是基于 [kusesad-1122/XINCODE-Public](https://github.com/kusesad-1122/XINCODE-Public) 修改的 Android 项目。主体代码采用 GPL-3.0-or-later，原始版权、许可证和已有归属声明均应保留。

本项目同时包含或改编了以下上游项目的部分代码、资源或实现：

- [AAswordman/Operit](https://github.com/AAswordman/Operit)，LGPL-3.0。改编/参考范围包括身份卡字段、GitHub OAuth 设备流，以及部分智能体工作台能力组织方式。许可证全文见 [`licenses/Operit-LGPL-3.0.txt`](licenses/Operit-LGPL-3.0.txt)。
- [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub)，AGPL-3.0。改编/包含范围包括聊天主页与模型中心交互、供应商图标、免 Root PRoot 工作区、PTY 终端接入及随包二进制。许可证全文见 [`licenses/RikkaHub-AGPL-3.0.txt`](licenses/RikkaHub-AGPL-3.0.txt)。
- [termux/termux-app](https://github.com/termux/termux-app) v0.118.0，GPLv3-only，并包含终端模拟器 Apache-2.0 例外；完整例外条款见 [`licenses/Termux-shared-LICENSE.md`](licenses/Termux-shared-LICENSE.md)。
- `codegraph-kernel` 与其 Tree-sitter 语法源码，原生内核采用 MIT，语法目录中的各自许可证继续适用。

固定提交、适配文件、直接复制的资源和二进制 SHA-256、商标免责声明以及再分发说明，统一见 [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md)。对应上游项目的代码和许可证可从各自 GitHub 仓库获取。

HSUCODE 不代表上述项目的官方发行版，也不表示与其作者或服务商存在赞助、认证或背书关系。

## 对应源码与安装信息

`main` 分支就是 HSUCODE 的对应源码发布位置，包含应用源码、数据库 schema、第三方许可证、资源归属和构建脚本。Release APK 使用维护者本地密钥签名，私钥不进入仓库；修改后应使用自己的密钥重新构建，并以独立包名或卸载旧版本安装。
