# BlindPilot for Android: first-release design

Date: 2026-10-19

Status: Approved 2026-10-19. Pre-release 0.1.0 implements everything here except Samsung SDK integration (waiting on partner approval) and the full screen-reader acceptance pass.

## Purpose and approved scope

BlindPilot for Android is an accessible AI chat and assistive device-control application for blind users. TalkBack and Backtalk compatibility are core product requirements. The user approved chat backends, remote Hermes, downloadable on-device models, Samsung's built-in models, and local device actions for the first release. These are release requirements, not a roadmap that silently postpones device control or Samsung integration.

The Android app is a native Kotlin application with standard Android Views. The desktop Python/wxPython application supplies behavior and protocol references; its desktop UI and subprocess-based coding agents are not embedded in Android. Remote Hermes remains an agent running on its host. Local models can drive Android assistive actions through the app's device-action loop.

The current Android workspace is empty. Reference source is the sibling `BlindPilot` repository, currently at desktop version 0.48.2. Its `accessible_ai` models, providers, chat services, storage, and UI establish the chat parity baseline. Its `hermes_backend.py`, `hermes_worker.py`, and protocol tests establish the remote Hermes baseline.

## Architectural choice

Use a native UI, a shared conversation model, provider-specific network adapters, an embedded local inference runtime, and a separate device-action executor. Start with one Android app module and the native inference dependency; use packages for these boundaries instead of introducing a plugin framework or many Gradle modules.

A WebView wrapping the desktop UI would not reuse wxPython and would complicate accessible navigation. A computer-hosted inference-only client would not satisfy offline local models. Neither alternative meets the requested scope.

UI screens cover conversations, message composition and attachments, accounts and profiles, model management, Hermes connections and sessions, device actions and approvals, settings, and help. Every operation must have a labeled control accessible through the active screen reader and keyboard. Tablet resizing, rotation, split-screen, and DeX must preserve state and useful focus.

## Chat parity

Port the desktop provider inventory: OpenRouter, OpenAI, ChatGPT plan, Claude, Gemini, Z.AI, Moonshot AI, Kimi, DeepSeek, Demon Route, Command Code, OpenCode Go, and custom OpenAI-compatible services. Verify each provider's current protocol and authentication before implementation; desktop URLs or credentials are not proof of a supported Android authentication flow. ChatGPT plan sign-in is distinct from an OpenAI API key. Any unavailable authentication flow is a documented release blocker, not a working-looking substitute.

Support accounts, default account, model discovery and manual model IDs, profiles, system prompts, generation settings, streaming, cancellation, conversation history, search, rename, deletion, copy, code and link navigation, saved prompts, and export. Support image, text, and PDF attachments with explicit provider capability checks and useful size/type errors.

Preserve applicable OpenRouter server tools and reasoning controls from the desktop implementation. Clearly identify remote execution and remote data transfer. Server tools are not automatically equivalent to Android-local actions. Models that lack vision, tools, or a requested output type must produce an accessible explanation before sending an incompatible request.

## Conversation and persistence model

Keep accounts, profiles, conversations, messages, attachments, and task states separate. Persist local history and configuration in SQLite, using Android facilities and a small schema with migrations. Store attachment content in app-private files and keep references in the database. Do not store large model binaries in SQLite.

Store secrets using Android Keystore-backed encryption. Exclude secrets from diagnostics, exports, screenshots captured for tests, and ordinary backup. Avoid plaintext credentials and avoid logging authentication headers.

Run network, disk, inference, and document processing away from the main thread. Preserve drafts and completed messages through rotation and process recreation. Persist partial responses as interrupted when continuation cannot be guaranteed. Never silently resubmit a prompt or replay a device action after reconnection or restart.

## Remote Hermes

Implement the existing TUI gateway JSON-RPC protocol over WebSocket. Support the desktop authentication modes, endpoint normalization, connection testing, sessions, streamed response and tool events, attachments, model selection, slash command discovery, questions, approvals, and secret requests. Validate current upstream behavior against desktop protocol tests and a real server.

Keep request IDs and session IDs associated with the session that produced each event. Do not guess approval values or hardcode a slash-command catalog. Disconnect must produce a concise accessible status. Reconnect must reconcile session state before allowing another send. A connection failure must not duplicate a submitted turn.

Use encrypted transport for remote credentials. Document any explicitly configured local development connection separately. Local-only tokens and network username/password authentication must retain their distinct server semantics.

Hermes host actions and Android actions have distinct execution locations. Sending a message to Hermes must not silently grant it tablet control. The same validated Android action executor may serve explicitly authorized remote tool requests if the actual Hermes integration supports them; protocol support must be proven, not assumed from basic chat connectivity.

## Downloadable on-device models

Use an embedded Android inference runtime, initially evaluating the upstream llama.cpp Android binding and GGUF support. Pin a tested upstream revision. Provide model import through the system file picker, resumable downloads from a configured source, progress and cancellation, integrity validation, deletion, loading and unloading, and an offline operation check.

Support conversation templates and system prompts, streaming and cancellation, context limits, generation settings, and model capability information. Provide text, vision, and structured tool use through compatible models and runtime support. Do not label a text-only model as multimodal or claim image generation from a text-generation runtime.

Choose tested models and context sizes from measurements on the Tab S10+. Check available memory and storage before loading. Handle low-memory conditions, thermal throttling, interrupted downloads, and unavailable acceleration without losing conversations. Keep only the needed model loaded. CPU support is the baseline; GPU acceleration is enabled only after device validation. Do not assume Snapdragon-specific acceleration on this tablet.

Downloaded-model inference must work without a cloud connection after installation. Network-dependent tools such as web search remain network-dependent and must say so. No silent cloud fallback.

## Samsung built-in AI

Samsung's Galaxy On-Device AI Partner Portal requires an API access request, approval, and app registration before SDK integration. The user will perform registration. Obtain the authorized SDK and its device, feature, signing, distribution, and permission requirements before declaring this backend implemented.

Integrate the operations actually exposed by the authorized SDK on the Tab S10+. Present supported tasks and language/model constraints explicitly. Do not assume the SDK provides unrestricted chat, vision, tool calling, every consumer Galaxy AI feature, or access to model weights. Query availability and readiness where supported, and provide accessible download/setup/error states.

Project Apex is a proprietary Magisk module containing Samsung firmware components. It is a reference about device feature enablement, not an app inference SDK. Do not root the tablet, install Apex, copy Samsung firmware, or bypass package permissions as part of this port.

Samsung integration remains a first-release requirement. If SDK access or required capabilities cannot be obtained, report the exact blocker and resolve scope with the user; do not ship an unavailable backend under a claim of complete support.

## Local assistive device actions

Provide an opt-in Android AccessibilityService for inspecting accessible window content and acting on the user's behalf. Prefer node-based click, long-click, scroll, text-entry, and supported global actions. Use supported gestures only when semantic actions cannot perform the requested task. Include app launching and user-selected document operations through Android APIs.

The service must coexist with TalkBack and Backtalk. It must not request touch exploration, replace the default screen reader, intercept screen-reader gestures, or disable another accessibility service. It must not continuously speak over the active reader. The app UI must remain accessible when its own service is disabled.

Run a bounded observe, propose action, validate, approve where needed, execute, and observe-result loop. Use structured action types and validated arguments. Re-resolve targets against the current window before execution. Reject stale targets, malformed requests, and unsupported actions. Model output and screen text are untrusted input, not permission grants.

Support multi-step tasks with a readable action history, cancellation, a step/time limit, and a recoverable error state. Approval must identify the action, destination, and data where relevant. Require explicit confirmation for sending messages, purchases, deletions, credential entry, and other consequential actions. Do not repeatedly execute a failed mutation or treat a click's return value as proof of task success.

Provide an accessible Stop action in the app and through a persistent task notification while actions run. Stop prevents new actions and reports any action already completed. Do not promise rollback for external actions that Android cannot undo.

Use system-authorized screenshot access only when needed for vision; respect protected windows and sensitive fields. Use the Storage Access Framework for selected documents. An ordinary Android app cannot read every app's private data or run unrestricted shell commands. Computer-hosted ADB/DroidMind capabilities are a separate, explicitly connected execution path, not privileges granted by enabling the accessibility service.

## Screen-reader requirements

Use labeled native controls, meaningful roles and states, heading semantics, and logical traversal. Explain actions through text rather than icons, color, placement, or gesture-only instructions. Include help for touch, keyboard, and supported braille operation.

Keep input focus and accessibility focus stable during streaming. Announce concise task start, completion, failure, and approval-needed events. Do not make every generated token a live-region announcement or force the user into a large response. Let the user navigate complete messages, paragraphs, code, and links and read or copy them on demand.

Dialogs must expose their title, message, controls, initial focus, and focus return. Permission onboarding must explain why access is needed and allow cancellation. Authentication, file pickers, model setup, provider errors, and the Stop action must be independently usable without sighted assistance.

Real TalkBack and Backtalk tests are mandatory. A successful build, automated accessibility check, or UI hierarchy dump alone does not establish screen-reader compatibility. Keep the currently active reader enabled during routine testing. Any test that switches readers needs a verified recovery path.

## Verified tablet baseline

Read-only wireless ADB checks on 2026-10-19 confirmed:

- Model SM-X820, Galaxy Tab S10+; serial matched the intended tablet.
- Android 16, API 36; SoC identifier MT6989; arm64-v8a ABI.
- Approximately 11.3 GiB physical RAM and 4.9 GiB available at inspection. Availability changes with workload.
- Approximately 198 GB available in the reported `/data` filesystem.
- Backtalk enabled; Samsung TalkBack installed.
- Samsung AI Core, SCS, vision intelligence, and interpreter packages installed.
- Thermal status 0 at inspection. No inference performance measurement has been made.

Installed Samsung packages do not prove permission to call their APIs. Use a serial-verified ADB target for all tablet operations; other Android devices are connected to the same host. Preserve other devices and the tablet's existing accessibility settings.

## Validation and release acceptance

Build and test each component before combining it. Keep representative protocol fixtures for all three chat API shapes, streamed events, Hermes session routing and approvals, cancellation, and error handling. Test action validation, stale targets, denied permissions, and stopping a task before the next mutation.

Run Android instrumentation tests on the Tab S10+ for conversation persistence, attachments, downloads, rotation, backgrounding, process recreation, and device actions against a controlled test surface. Test provider request behavior with mocks, then authenticated smoke tests using configured accounts. Do not claim a provider works from a settings screen alone.

Measure local model loading, first-token latency, generation throughput, memory, thermals, and cancellation with Backtalk active. Repeat essential workflows with TalkBack active. Record each tested model, quantization, context length, runtime revision, and acceleration mode. Select defaults from these measurements rather than a generic RAM estimate.

For both screen readers, complete onboarding, account setup, model setup, chat, attachment selection, message navigation, history and export, Hermes questions/approvals, a local multi-step action, error recovery, and Stop without visual assistance. Verify streaming does not disrupt navigation or braille reading. Record failures; fix them before claiming accessibility support.

The first public release requires working chat providers and applicable extras, remote Hermes, downloaded offline models, authorized Samsung AI integration, local device actions, and both screen-reader acceptance passes. A blocker in any required area prevents a claim of complete first-release scope. Prepare a reproducible signed APK and accessible setup/help instructions after these checks; store publication and account submissions require their own concrete release step.

## Implementation sequence

The following sequence organizes work; every stage remains part of the first release:

1. Resolve Samsung SDK registration and capability access while establishing the native app foundation and accessible navigation.
2. Port conversation persistence, provider protocols, accounts/profiles, and chat parity.
3. Port remote Hermes and exercise session, event, approval, and reconnect contracts.
4. Embed and benchmark downloadable models; complete model management and offline workflows.
5. Implement the shared validated Android action executor and model-driven action loop; integrate Samsung SDK tasks once authorized.
6. Run complete hardware and screen-reader acceptance checks, fix failures, and prepare the signed release artifact.

## References

- Desktop baseline: https://github.com/serrebidev/BlindPilot
- Android View accessibility techniques: https://github.com/cvs-health/android-view-accessibility-techniques
- Android assistive service APIs: https://developer.android.com/guide/topics/ui/accessibility/service
- Android document access: https://developer.android.com/guide/topics/providers/document-provider
- llama.cpp Android binding: https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md
- Samsung partner registration: https://aisdk.developer.samsung.com/overview
- Computer-hosted Android MCP/ADB reference: https://github.com/hyperb1iss/droidmind
- Image-analysis workflow reference: https://github.com/UniverseKing4/AI-Vision
- Apex context: https://github.com/Llucs/project_apex
