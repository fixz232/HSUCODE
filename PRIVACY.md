# HSUCODE Privacy Notice

Last updated: 2026-08-14

HSUCODE is a self-hosted Android application. This notice describes the data paths created by features that a user enables. It is a technical notice, not a promise that external services will follow HSUCODE's policies.

## Local data

The application stores its own configuration, conversations, task state, local memory, attachments, workspace metadata, and logs on the device. Encrypted full backups are created only when the user requests them and are protected by the password chosen for that backup. Android system backup is disabled with `android:allowBackup="false"`.

API keys are configured by the user for their selected provider. They are not published in this repository and are excluded from source control. Do not place credentials in prompts, source files, custom headers, screenshots, or exported non-sensitive presets.

## External requests

HSUCODE sends data outside the device only when a corresponding feature is configured or invoked:

- A configured model provider receives the conversation content, model/tool request data, and any attachments or workspace content that the model is instructed to read.
- Web search, web fetch, browser, download, Git, MCP, LAN discovery, and update features contact their selected endpoint or peer.
- Installing the optional Ubuntu workspace downloads a rootfs and integrity metadata from the configured upstream release source.
- Sharing, opening an external link, or exporting a file uses the Android activity or document provider selected by the user.

Each external provider, website, MCP server, repository host, or LAN peer has its own privacy policy and retention practices. Review them before sending sensitive content.

## Optional high-privilege features

Root mode, Shizuku, UI automation, the accessibility service, microphone input, notifications, and the overlay window are optional capabilities. Android requires separate system or runtime approval where applicable. Enabling one grants the scope shown by Android or Shizuku; it does not make model output safe. Review each command, target application, file path, and permission request before approval.

## Your choices

You can remove provider configurations, clear conversations and local data, revoke Android permissions, disable the accessibility service or overlay permission, disable Shizuku/Root mode, and delete exported backups from their chosen storage location. Removing the app may remove app-private local data; export or back up data you need before uninstalling.

## No advertising or analytics SDK

The HSUCODE source tree does not include an advertising SDK or behavioural analytics SDK. This does not prevent a configured external service from logging requests sent to it.

## Contact and updates

This notice applies to the source distributed at [fixz232/HSUCODE](https://github.com/fixz232/HSUCODE). Security concerns are covered by [`SECURITY.md`](SECURITY.md). Changes to this notice are recorded through the repository history and release notes.
