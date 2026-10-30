# Malty v0.9.3 build and verification checkpoint

The next hardened version is **v0.9.3**, `versionCode=12`, package `org.canceledagent.app`. The previously published signed v0.9.2 APK remains unchanged and is not the hardened v0.9.3 build.

Both Android build paths are aligned to version 0.9.3 and application ID org.canceledagent.app. The direct builder preserves Android minSdk 26 and targetSdk 28.

For a full local build, install JDK 17, Android API 36, Android Build Tools 36, Python backend dependencies and Node.js, then execute `bash scripts/verify-and-build-v0.9.3.sh` from the project checkout. The runner must pass the full Python backend suite, original feature/parity checks, solver tests, Java contracts, security HMAC/replay/TLS checks, integration audit, Android compilation, APK signature verification, and v0.9.3 package-version inspection before reporting completion.

Local development signing is not update-compatible with the official v0.9.2 signing identity. The existing authorized release key must be used for an install-over-update build. The signing key must never be put in public GitHub.

This checkpoint stages build metadata and validation automation. **It does not claim that the full v0.9.3 APK has been built, physically installed, or production signed.** No GitHub Actions are used.
