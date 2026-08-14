# HSUCODE Security Notice

## Security model

HSUCODE can invoke models, read and write files in configured workspaces, run terminal commands, access network services, and optionally use Root, Shizuku, accessibility automation, or an overlay. These capabilities are powerful by design and must be treated as local developer tooling, not as a sandbox or a security boundary.

Model output is untrusted input. Before allowing an action, verify the command, target path, destination host, data being shared, and requested permission. Do not grant broad permission merely because a task description sounds safe.

## Reporting a vulnerability

Please use the repository's private GitHub Security Advisory reporting channel when it is available:

<https://github.com/fixz232/HSUCODE/security/advisories/new>

If private reporting is unavailable, open a GitHub issue containing only a minimal, non-sensitive description and ask for a private contact channel. Do not publish API keys, personal files, device identifiers, exploit payloads, or steps that could harm other users.

Include the application version, Android version, device/ABI, reproduction steps, expected and actual behaviour, and whether Root, Shizuku, accessibility automation, MCP, or a custom provider was enabled.

## Supported source

Security fixes are made against the current `main` branch of [fixz232/HSUCODE](https://github.com/fixz232/HSUCODE). Builds made from modified forks, custom provider endpoints, third-party MCP servers, rooted device images, or unreviewed Skills require independent review by their maintainers.

## Scope and disclosure

Please report issues involving credential exposure, unauthorized file or command access, permission bypass, unsafe update handling, data loss, path traversal, insecure network transport, or unintended accessibility/overlay behavior. Allow maintainers reasonable time to investigate and release a fix before public disclosure.

HSUCODE is provided under GPL-3.0-or-later without warranty. The project does not guarantee that any model, Skill, MCP server, downloaded rootfs, external provider, or rooted-device integration is safe for every use case.
