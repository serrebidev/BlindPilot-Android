# BlindPilot for Android

Accessible AI chat for blind Android users, built for TalkBack and Backtalk. A native Kotlin port of [BlindPilot](https://github.com/serrebidev/BlindPilot).

## Features

- Cloud chat: OpenRouter, OpenAI, ChatGPT plan sign-in, Claude, Gemini, Z.AI, Moonshot AI, Kimi, DeepSeek, Demon Route, Command Code, OpenCode Go, and any OpenAI-compatible service. Streaming, cancellation, attachments (images, PDF, UTF-8 text), OpenRouter server tools, and reasoning effort.
- Remote Hermes over its WebSocket gateway: sessions, history, slash commands, questions, and approvals.
- On-device models: llama.cpp runs GGUF models offline. Browse Hugging Face's GGUF index by most downloaded, most liked, trending, or recently updated. Or import a file or download from an address. Every download is checked against the publisher's SHA-256.
- Device assistance: an opt-in accessibility service lets a model read the screen and propose one action at a time. You approve every action, and Stop is always available in the app and in the notification. Your screen reader stays on.
- Profiles with system prompt, generation settings, and a default account and model. Conversation history with search, rename, export, and delete. Saved prompts.
- Model lists sort A to Z, Z to A, newest first, or oldest first.
- Screen reader actions: swipe up or down on a message or conversation in TalkBack or Backtalk to choose an action. Messages offer read all, copy, edit, edit and resend, resend, regenerate, save as prompt, new chat from here, share, links, code blocks, and delete. Conversations offer open, rename, export, and delete. Long press shows the same actions as a menu.

Keyboard: Control+Enter sends, Control+N starts a new chat, Control+L lists conversations, Control+M opens the menu, and Escape stops a running task.

## Status

This is a pre-release. Samsung Galaxy AI integration waits on Samsung's partner SDK approval. Full TalkBack and Backtalk acceptance testing is still in progress.

## Install

Download the APK from the latest release and open it on the device. Android 9 or later on arm64 is required.

## Build

See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).
