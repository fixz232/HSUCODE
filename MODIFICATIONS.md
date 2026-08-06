# Modification Record

## Upstream Project

HSUCODE is a modified work based on the following upstream project:

- Repository: [`kusesad-1122/XINCODE-Public`](https://github.com/kusesad-1122/XINCODE-Public)
- Upstream URL: <https://github.com/kusesad-1122/XINCODE-Public>

## Additional Open-Source Adaptations

- Portions of the agent workbench, identity-card fields, and GitHub OAuth device-flow implementation were adapted from [AAswordman/Operit](https://github.com/AAswordman/Operit), reference commit `4d753eb024b420df2a0f32f7a748bf1a8f61d0d4`, under LGPL-3.0. See [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) and [`licenses/Operit-LGPL-3.0.txt`](licenses/Operit-LGPL-3.0.txt).
- Portions of the chat/model-center UI, provider assets, no-root workspace, PRoot launchers, and PTY integration were adapted from [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub), reference commit `4b6449e37854809302cdf5f1e72231b420f57382`, under AGPL-3.0. See [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) and [`licenses/RikkaHub-AGPL-3.0.txt`](licenses/RikkaHub-AGPL-3.0.txt).

## Modification Log

### 2026-08-05 (UTC+08:00)

- Simplified the settings surface with a compact common-actions strip, collapsible categories, section descriptions/counts, and search-driven expansion. Permission controls now live inside the security section instead of appearing as a detached block.
- Reworked the chat home surface toward the RikkaHub layout: conversation title and assistant/provider subtitle in the top bar, dedicated new-chat and overflow actions, provider avatar composer, right-aligned context/send controls, and voice input. HSUCODE execution, workspace, mode, and prompt-expansion actions remain available in the lower toolbar.
- Selectively synchronized upstream `kusesad-1122/XINCODE-Public` through commit `28dc9eb` while preserving the HSUCODE namespace and local features. Bundled six upstream skills, added idempotent asset import, parallel group replies with quote snapshots, Room schema 42 migration, and an app-private default workspace for rootless execution.
- Hardened runtime path normalization on Windows/JVM so backslash and parent-directory forms cannot bypass the self-protection policy.
- Added a complete app-private, no-root PRoot Ubuntu workspace. It downloads and SHA-256 verifies the upstream Ubuntu base rootfs, safely extracts it through a staging directory, preserves an existing working environment on cancellation/failure, and bind-mounts the persistent HSUCODE workspace at `/workspace`.
- Terminal, `env_exec`, environment tool installation, Git CLI setup, the health center, and the home workspace status now prefer the no-root PRoot Ubuntu environment. The older root+chroot implementation remains an optional fallback for rooted devices.
- Copied the arm64-v8a and x86_64 PRoot launcher binaries from `rikkahub/rikkahub` commit `4b6449e37854809302cdf5f1e72231b420f57382`; AGPL-3.0 source/notice and binary hashes are retained in `THIRD-PARTY-NOTICES.md`.
- Added an app-private, no-root Android Shell workspace. The terminal and `env_exec` now execute lightweight agent tasks there whenever the optional root+chroot Ubuntu environment is unavailable.
- Added a compact workspace status strip on the chat surface and reworked the environment page to distinguish the default no-root workspace from the optional Ubuntu enhancement.
- Imported the listed provider SVG assets from `rikkahub/rikkahub` commit `4b6449e37854809302cdf5f1e72231b420f57382`; the copied asset list and AGPL-3.0 license are preserved in `THIRD-PARTY-NOTICES.md` and `licenses/RikkaHub-AGPL-3.0.txt`.

### 2026-08-04 21:20 (UTC+08:00)

- Consolidated provider setup, model catalog, functional assignments, and usage health into a single HSUCODE Model Center.
- Added per-model capability metadata, verified provider connection diagnostics, explicit fallback routing, and non-sensitive preset import/export.
- Added local-model connection guidance for Ollama and LM Studio, while keeping API keys out of portable presets.

### 2026-08-04 19:23 (UTC+08:00)

- Rebuilt the agent command room as a native run console with a compact, collapsible pixel-office scene.
- Added stable worker-run identities, cancellation, retry, stop-all, terminal-state cleanup, and keyed permission approvals.
- Added persisted command-room run and event history with Room schema version 41 and interrupted-run recovery.
- Hardened the local WebView bridge, reduced idle animation work, and clarified safe read-only network and skill operations.
- Fixed the sidebar search-field layout and added the user-provided square HSUCODE profile image.

### 2026-08-04 17:26 (UTC+08:00)

- Added password-protected, encrypted full backup and cross-device restore for HSUCODE-owned data, including the database, attachments, and portable credential recovery.
- Added backup and restore user-interface entry points and startup recovery sequencing before the Room database opens.
- Improved persisted task-cursor checkpoints and safe task resumption, avoiding automatic replay when a side-effecting tool result is unknown.
- Added Room support and unit tests for backup encryption and task-cursor state handling.

## Copyright And License Preservation

- The upstream project's original copyright, author, and license notices are retained.
- [`LICENSE`](LICENSE) remains the project license and must not be removed or replaced.
- Third-party license files, including [`codegraph-kernel/LICENSE.codegraph`](codegraph-kernel/LICENSE.codegraph), remain in place.
- The native CodeGraph kernel and vendored Tree-sitter grammar notices are listed in [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md); rebuilds must preserve the per-grammar license files and `Cargo.lock` versions.
- Existing source-file copyright, license, and author headers must remain intact when files are modified.
