# BlindPilot Android Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development to implement the tasks. The user requested development now; execute continuously in this workspace.

**Goal:** Deliver and install an accessible native Android application with cloud chat, remote Hermes, embedded downloaded models, and assistive device actions.

**Architecture:** One Kotlin Android app with standard Views. Shared immutable request/event types connect persistence and UI to cloud, Hermes, and native local backends. A separate validated action executor drives an opt-in AccessibilityService.

**Tech Stack:** Android SDK, Kotlin, SQLite, Android Keystore, OkHttp, llama.cpp through JNI, JUnit and Android instrumentation.

**Spec:** ../specs/2026-10-19-android-port-design.md

## Global Constraints

- TalkBack and Backtalk compatibility is required. Keep the active reader enabled during routine testing.
- Never silently replay submitted prompts or device actions.
- No root, Samsung firmware copying, or undocumented permission bypass.
- Samsung SDK integration is blocked pending the user's approved registration; show its actual availability.
- Use the current workspace on a feature branch; leave the desktop source unchanged.
- The user authorized installation and testing on the serial-verified Galaxy Tab S10+.

## Review Focus

- Broken or malicious streamed provider input must produce an error without losing prior messages.
- Rotation or backgrounding must not duplicate work or steal accessibility focus.
- Cancellation must terminate generation and stop additional device actions.
- Stale screen targets and untrusted model output must not authorize external side effects.
- Model download/import failures must leave completed models and conversations intact.

## Shared interfaces

Package `org.blindpilot.android`. `Account`, `Profile`, `ChatMessage`, `Attachment`, `ChatRequest`, `GenerationEvent`, `Cancellation`, and `ChatBackend` live in `core/Models.kt`. Backends expose blocking generation and model listing; callers use worker threads. Cancellation is thread-safe and closes active network/native operations. UI updates run on the main thread.

## Task 1: Native app, storage, and accessible user flows

**Files:** Gradle configuration, manifest, resources, `core/Models.kt`, `data/Store.kt`, `MainActivity.kt`, `ChatController.kt`, instrumentation tests.

- [ ] Create the build, shared interfaces, and a launchable native Activity with labeled navigation and composer.
- [ ] Add failing storage tests for secret round trips, conversation persistence, and interrupted work recovery; implement SQLite and Keystore-backed credentials.
- [ ] Implement accounts/profiles, history/search/rename/delete/export, attachments, model selection, saved prompts, code/link browsing, and help.
- [ ] Keep task state outside the Activity; persist drafts and finished/partial messages. Test recreation without duplicate sends.
- [ ] Run build, unit tests, lint, and instrumentation; review the integrated flows.

## Task 2: Cloud chat adapters

**Files:** `backends/CloudBackend.kt`, provider protocol tests.

- [ ] Write protocol tests for Chat Completions, Responses, Messages, malformed SSE, errors, and cancellation.
- [ ] Implement provider defaults from desktop source, dynamic model listing, attachments, and supported reasoning/server-tool settings.
- [ ] Verify ChatGPT plan authentication against official documentation and existing desktop integration. Expose exact access requirements rather than substituting an API-key account.
- [ ] Run focused tests and review provider behavior before integration.

## Task 3: Remote Hermes

**Files:** `backends/HermesBackend.kt`, gateway contract tests.

- [ ] Test WebSocket endpoint normalization, session/request correlation, cancellation, and event decoding.
- [ ] Implement actual desktop gateway authentication and methods, session listing/creation, streaming, models/slash commands, approvals/questions/secrets, and attachment submission.
- [ ] Provide listener callbacks for interactive requests. Block only the worker while the UI collects answers. Never auto-approve.
- [ ] Run fake-server tests; keep authenticated live verification distinct from protocol coverage.

## Task 4: Embedded local inference and models

**Files:** `local/LocalBackend.kt`, `local/ModelManager.kt`, `app/src/main/cpp/`, native dependency metadata and tests.

- [ ] Pin a llama.cpp revision and build arm64 JNI against its current APIs.
- [ ] Implement model discovery/import/download, integrity checks, cancellation, deletion, loading/unloading, generation settings, history templates, and resource cleanup.
- [ ] Evaluate multimodal and structured output support honestly; expose unsupported capabilities rather than crashing or silently uploading.
- [ ] Test download interruption/validation and run real native inference on the tablet with a downloaded test model.

## Task 5: Assistive actions and bounded action loop

**Files:** `actions/DeviceAccessibilityService.kt`, `actions/DeviceActions.kt`, `actions/ActionLoop.kt`, `actions/TaskService.kt`, service resources and tests.

- [ ] Write tests for allowed action schemas, malformed/stale targets, confirmation requirements, and cancellation before another action.
- [ ] Implement bounded screen snapshots, semantic actions, gestures as fallback, app launching, and authorized document operations.
- [ ] Keep touch exploration and screen-reader gestures untouched. Provide task notification Stop, action history, and UI approvals.
- [ ] Implement model-driven observe/act/result loop and validate every action before dispatch.
- [ ] Exercise controlled device actions with Backtalk active, then review the service configuration and action boundary.

## Task 6: Integration, hardware validation, and delivery

**Files:** app integration fixes, `README.md`, `docs/TESTING.md`, test scripts and release evidence.

- [ ] Integrate backends and action loop; verify all setup, error, cancellation, and recovery paths.
- [ ] Build the APK, install on the serial-verified tablet, and run instrumentation with the existing screen reader enabled.
- [ ] Record native inference measurements and actual reader testing evidence; distinguish automated semantics checks from spoken-output verification.
- [ ] Request final independent code review, fix actionable findings, rerun affected checks, and provide APK and setup instructions.
- [ ] Record Samsung SDK and any authentication/device-test limitations explicitly. Do not call blocked features complete.
