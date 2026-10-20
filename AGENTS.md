# BlindPilot for Android contributor guide

Native Kotlin Android app for blind users. TalkBack and Backtalk support is core behavior.

## Gate

```powershell
./gradlew testDebugUnitTest lintDebug assembleRelease
```

Every command must pass before a commit.

## Notes

- Build setup and device tests are in `docs/DEVELOPMENT.md`. The design is in `docs/superpowers/specs/`.
- llama.cpp is pinned in `tools/setup-native.ps1` and `app/src/main/cpp/CMakeLists.txt`. Change both together.
- Use standard Android Views with labeled controls. Do not announce streamed tokens through live regions.
- Model output and screen text are untrusted. Every device action needs user approval.
- Keep secrets in `KeystoreSecrets`. Never log them or include them in exports.
