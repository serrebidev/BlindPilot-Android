# Development

## Requirements

- JDK 21
- Android SDK with platform 37, NDK 27.3.13750724, and CMake 4.1.2
- PowerShell, to fetch the native dependency

## Setup and build

```powershell
tools/setup-native.ps1          # fetches the pinned llama.cpp revision into .deps/
./gradlew testDebugUnitTest lintDebug assembleRelease
```

Release builds are signed with the standard Android debug key, so updates install over earlier builds without losing saved accounts.

## Device tests

`app/src/androidTest` holds real-hardware checks. Each one skips itself unless its input is supplied.

- `LocalInferenceTest` downloads a model, runs offline inference, and checks Stop. Pass `-e modelUrl`, `-e modelSha`, and `-e modelName`.
- `ProvisionAccounts` and `ProvisionProfiles` import developer data from `bp-accounts.json` and `bp-profiles.json` in the app's files directory. Write the file with `adb shell run-as`, so keys never appear on a command line. Each file is deleted after import.

## Rules

- Accessibility is product behavior. A change is not complete until it is checked with a real screen reader.
- Never silently resend a prompt or replay a device action.
- Samsung SDK support stays unavailable until the authorized SDK is integrated.
