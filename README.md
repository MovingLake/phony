# 🦀 PhoneClaw

> **nanoclaw for your phone** — a voice-driven AI agent that runs on Android, sees your screen, and acts on your behalf.

[![Android](https://img.shields.io/badge/Android-11%2B-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-blue.svg)](https://kotlinlang.org)
[![Gemini](https://img.shields.io/badge/Gemini-2.0_Flash-orange.svg)](https://aistudio.google.com)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

---

## What it does

Tap the floating 🦀 button on your screen, speak a command, and PhoneClaw takes care of it:

| You say | PhoneClaw does |
|---------|---------------|
| *"Open Maps and find good taco spots nearby"* | Opens Google Maps → searches "tacos" → reads top results → speaks them back |
| *"What meetings do I have today?"* | Opens Calendar → reads today's events → tells you |
| *"Open my notes and add 'call dentist'"* | Opens Keep/Notes → creates a new note |
| *"Play something chill on Spotify"* | Opens Spotify → taps Play |
| *"Check my storage in Settings"* | Opens Settings → navigates to Storage → reads available space |

PhoneClaw is an **agent** — it loops: screenshot → Gemini (reasoning + tool calls) → execute actions → screenshot → repeat until done. Just like Claude Code on your terminal, but on your screen.

---

## How it works

```
You speak ──→ Android STT
                  │
                  ▼
         Screenshot of current screen
                  │
                  ▼
         Gemini 2.0 Flash ◄──── Tool results (loop)
              │
              ▼ Tool calls
         ActionExecutor
         ┌─────────────────────────────┐
         │ tap(x, y)                   │
         │ swipe(...)                  │
         │ type_text(text)             │
         │ open_app("Google Maps")     │
         │ find_and_click_text("Search")│
         │ get_screen_text()           │
         │ speak_to_user("Top 3...")   │
         └─────────────────────────────┘
              │
              ▼
         task_complete ──→ TTS speaks result back to you
```

---

## Setup

### Prerequisites
- Android phone running **Android 11+** (API 30)
- [Gemini API key](https://aistudio.google.com/app/apikey) (free tier is plenty)
- Android Studio or `adb` to sideload the APK

### Build & Install
```bash
git clone https://github.com/yourorg/phoneclaw
cd phoneclaw

# Build debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug
```

### First-time Setup (3 steps)

1. **Enter your Gemini API key** in the app
2. **Grant permissions** — the app walks you through each:
   - Accessibility Service (Settings → Accessibility → PhoneClaw → Enable)
   - Draw over other apps
   - Microphone
3. **Tap the floating 🦀** — it appears on every screen once the service is active

---

## Architecture

PhoneClaw inherits nanoclaw's core philosophy: **tool calls are the sole IO surface**. The agent only communicates through structured tool calls — no sideband state, no implicit context, just the loop.

```
service/PhoneclawAccessibilityService.kt   ← manages lifecycle + FAB
service/FloatingButtonManager.kt           ← draggable overlay, 5 visual states
agent/AgentLoop.kt                         ← the tool-use loop
agent/GeminiClient.kt                      ← Gemini API + 14 function declarations
agent/ActionExecutor.kt                    ← gestures, node clicks, app launches
agent/AppResolver.kt                       ← "Maps" → com.google.android.apps.maps
agent/ScreenshotManager.kt                 ← takeScreenshot() + compress for API
speech/SpeechManager.kt                    ← STT + TTS
data/PreferencesManager.kt                 ← API key + model settings
```

---

## FAB States

The floating button changes color and icon to show what PhoneClaw is doing:

| State | Color | Meaning |
|-------|-------|---------|
| 🟣 Idle | Purple | Ready — tap to speak |
| 🔴 Listening | Red (pulsing) | Recording your voice |
| 🔵 Thinking | Blue (spinning) | Gemini is reasoning |
| 🟢 Acting | Green | Executing actions on screen |
| 🔴 Error | Dark red | Something went wrong |

> **Tip**: Tap the FAB while it's running to cancel the current task.

---

## Privacy & Security

- **Your screen stays on your device** — screenshots are sent to Gemini's API only during active tasks, not stored elsewhere
- **API key stored in DataStore** — encrypted at rest on Android 11+
- **No background recording** — microphone only activates when you tap the FAB
- **Open source** — read every line of what runs on your phone

---

## Roadmap

- [ ] **Local models** — Gemma 2B via MediaPipe (no API key, no network)
- [ ] **iOS port** — same architecture, different native layer
- [ ] **Scheduled tasks** — "remind me at 5pm to check the oven"
- [ ] **Saved macros** — record and replay multi-step workflows
- [ ] **Conversation memory** — "same restaurant as last time"

---

## Contributing

Issues and PRs welcome. See [CLAUDE.md](CLAUDE.md) for the full technical guide including architecture decisions, API surface, and common issues.

---

## License

MIT — same as nanoclaw. Build cool things.
