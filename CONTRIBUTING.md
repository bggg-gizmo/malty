# Contributing to Malty

The public `bggg-gizmo/malty` repository is for approved releases, issue reports, and public-facing source/documentation. The separate `malty-dev` repository remains private.

## Bug reports

Open a GitHub issue at https://github.com/bggg-gizmo/malty/issues and provide the Malty version, Android version, steps to reproduce, expected behavior, observed behavior, and logs with tokens, private posts, and other sensitive information removed.

For security-sensitive findings, do not publish credentials or exploit details in a public issue. Contact the maintainer through a private channel or GitHub's private vulnerability reporting facility if enabled for the repository.

## Pull requests

1. Open a targeted issue for substantial changes so behavior and acceptance criteria can be agreed on first.
2. Branch from the latest public `main`, and make changes without removing existing features or altering UI/UX outside the approved scope.
3. Include tests or an exact manual validation procedure, preserving all existing tests and functionality.
4. Open a pull request targeting `main`, describe the actual changes and any validation limits, and resolve review conversations before merging.
5. Do not commit signing keys, access tokens, private user data, development databases, or unreleased private-workspace artifacts.

Public releases are prepared and signed by the maintainers. Do not use paid GitHub Actions for this project.

## Licensing

The project has no declared blanket open-source license. Contributions and third-party materials are subject to their respective rights; contact the maintainer before submitting substantial code or assets for redistribution.