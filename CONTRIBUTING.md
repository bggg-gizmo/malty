# Contributing to MALTY

**Fork it. Inspect it. Improve it. Give credit.** Malty welcomes pull requests,
reviews, testing, security findings, documentation, and new functionality.

## Community workflow

1. Fork the public repository `bggg-gizmo/malty` and branch from current `main`.
2. Identify the behavior your change affects. Preserve existing functionality,
   UI/UX, data formats, app ID, and release-signing continuity unless the change
   is explicitly approved by the maintainers.
3. Test locally: Android source/build checks and any relevant backend, Java,
   JavaScript, or device test suites. Include exact tested commands and results;
   never claim device testing without a connected device.
4. **Append your public name/handle and a concise truthful contribution to
   `CREDITS.md`. Keep AmICanceled, Background Gremlin Group, and earlier
   credited contributors intact and in their original order.** This is an
   official-project pull-request acceptance requirement.
5. State which files changed and whether they incorporate outside material.
   Preserve applicable copyright notices and clearly mark modifications.
6. Open a pull request to `main`; maintainers review and merge. Official
   maintainers alone prepare and sign public release APKs.

Use the [issue templates](.github/ISSUE_TEMPLATE/) for reproducible bug reports
and feature proposals. Include app version, Android version, expected behavior,
steps to reproduce, and sanitized diagnostics. Do not attach credentials,
sessions, private messages, signing keys, or personal database contents.

## Licensing and attribution

The public project is licensed under [Apache License 2.0](LICENSE), subject to
any separate third-party notices attached to individual included components.
Apache-2.0 lets users modify, redistribute, fork, and use the licensed code,
while requiring retention of applicable notices and a notice of modified files.
[NOTICE](NOTICE) records the original developer and BGGG. The official project
requires PR authors to extend [CREDITS.md](CREDITS.md).

The copyright owner of each original contribution remains its author unless
separately agreed. Contributor identities must be accurate; usernames are fine.
An independent fork is welcome to link back to the official project, but
contributor attribution does not imply endorsement by AmICanceled or BGGG.

## Security and build hygiene

Do not commit `.keystore`, `.jks`, `.p12`, `.pem`, private release keys, `.env`
secrets, databases, device logs containing user information, or archive imports
from private development workspaces. Use local toolchains; **no paid GitHub
Actions or paid builds** are necessary or authorized by this project.

Security-sensitive reports should use [SECURITY.md](SECURITY.md), not public issues.
