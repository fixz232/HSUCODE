# Modification Record

## Upstream Project

HSUCODE is a modified work based on the following upstream project:

- Repository: [`kusesad-1122/XINCODE-Public`](https://github.com/kusesad-1122/XINCODE-Public)
- Upstream URL: <https://github.com/kusesad-1122/XINCODE-Public>

## Modification Log

### 2026-08-04 17:26 (UTC+08:00)

- Added password-protected, encrypted full backup and cross-device restore for HSUCODE-owned data, including the database, attachments, and portable credential recovery.
- Added backup and restore user-interface entry points and startup recovery sequencing before the Room database opens.
- Improved persisted task-cursor checkpoints and safe task resumption, avoiding automatic replay when a side-effecting tool result is unknown.
- Added Room support and unit tests for backup encryption and task-cursor state handling.

## Copyright And License Preservation

- The upstream project's original copyright, author, and license notices are retained.
- [`LICENSE`](LICENSE) remains the project license and must not be removed or replaced.
- Third-party license files, including [`codegraph-kernel/LICENSE.codegraph`](codegraph-kernel/LICENSE.codegraph), remain in place.
- Existing source-file copyright, license, and author headers must remain intact when files are modified.
