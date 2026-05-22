# Phony 🤖📱

**An open-source AI agent that operates your Android phone by voice.**

Phony runs as an Android Accessibility Service and puts a small floating button over every app on your phone. Tap it, speak a command, and Gemini takes over — it sees your screen, taps buttons, types text, scrolls through content, and speaks the result back to you. Hands-free.

> *"Open Robinhood and tell me how my portfolio is doing"*  
> *"Search Spotify for lo-fi beats and play the first result"*  
> *"Open Maps, find the best-reviewed taco place nearby, and read me the top 3"*  
> *"Open Calendar and tell me what I have tomorrow"*

---

## How it works

```
You speak  →  Screenshot + command  →  Gemini 2.0/3.5 Flash
                                              ↓
                                     Tool calls (tap, swipe, type…)
                                              ↓
                                     Fresh screenshot (automatic)
                                              ↓
                                     Next tool call … (loop)
                                              ↓
                                     task_complete → spoken result
```

Gemini sees the screen as an image, plans its actions as **function calls**, and Phony executes them in real-time using the Accessibility API. No screen overlays, no image templates, no hardcoded UI — it figures out any app on its own.

---

## Features

- 🎙️ **Voice-first** — tap the FAB, speak, done. No typing required.
- 👁️ **Visual understanding** — Gemini reads the screen as a screenshot on every step, so it adapts to any app and any state.
- 🤖 **Agentic loop** — multi-step tasks that span multiple screens and apps.
- 💬 **Session memory** — follow-up commands remember what you just did (1-minute session TTL).
- 🔐 **Login-aware** — if a fingerprint or PIN screen appears mid-task, Phony pauses and waits for you.
- 📢 **Speaks results back** — using Android TTS, so you never have to look at the screen.
- 🧭 **General assistant** — for non-phone questions ("tell me a joke", "what's 20% of $47") it just answers conversationally.
- ⚡ **Fast** — uses `thinkingBudget: 0` on Gemini 3.5 Flash to skip reasoning chains; direct tool calls with no warmup.

---

## Available Tools

| Tool | What it does |
|------|-------------|
| `tap(x, y)` | Tap at screen coordinates |
| `long_press(x, y)` | Long press |
| `swipe(startX, startY, endX, endY)` | Swipe gesture |
| `type_text(text)` | Type into the focused field |
| `find_and_click_text(text)` | Find a UI element by label and click it (more reliable than coordinates) |
| `get_screen_text` | Extract all text from the accessibility tree |
| `scroll(direction, x, y)` | Scroll UP / DOWN / LEFT / RIGHT |
| `open_app(app_name)` | Launch any app by natural name |
| `press_back / home / recents` | Android navigation |
| `take_screenshot` | Refresh the agent's view |
| `speak_to_user(message)` | Speak to you mid-task |
| `wait(seconds)` | Pause for animations or authentication |
| `task_complete(summary)` | End the loop, speak the result |

---

## Setup

### Prerequisites
- Android device running **Android 11+** (API 30)
- A free **Gemini API key** from [Google AI Studio](https://aistudio.google.com/app/apikey)
- Android Studio Ladybug (2024.2+) or newer

### Build & install
```bash
git clone https://github.com/MovingLake/phony.git
cd phony
./gradlew installDebug
```

### First run
1. Open **Phony** and paste your Gemini API key.
2. Grant **Accessibility Service** permission (Settings → Accessibility → Phony).
3. Grant **Draw over other apps** (Settings → Apps → Special app access).
4. Grant **Microphone** when prompted.
5. The floating button appears. Tap it and speak.

---

## Architecture

```
com.phoneclaw/
  MainActivity.kt                     # Onboarding + permissions (Compose)
  service/
    PhoneclawAccessibilityService.kt  # Core service, lifecycle, STT trigger
    FloatingButtonManager.kt          # WindowManager overlay, drag, state
  agent/
    AgentLoop.kt                      # Agentic loop, session history, TTL
    GeminiClient.kt                   # Gemini REST API, tool declarations
    ActionExecutor.kt                 # Tool calls → Android gestures/actions
    ScreenshotManager.kt              # takeScreenshot() wrapper + JPEG encoding
    AppResolver.kt                    # "Spotify" → package name + launch intent
    AgentTools.kt                     # ToolName enum, ToolCall, ToolResult
  data/
    PreferencesManager.kt             # DataStore — API key, model selection
  speech/
    SpeechManager.kt                  # SpeechRecognizer (STT) + TextToSpeech
```

**Why direct REST API instead of the Gemini SDK?**  
Gemini 3.5 Flash embeds a `thought_signature` inside every `functionCall` part. When replaying history, the API requires that signature to be present. SDK 0.9.0 silently drops it on serialization → 400 errors on every follow-up. By calling the REST API directly we set `thinkingBudget: 0`, which disables thought signatures entirely. No SDK limitations, full control over the request shape.

---

## Roadmap

- [ ] **Local model support** — Gemma 3 via MediaPipe LLM Inference API (no cloud, no API key)
- [ ] **Scheduled tasks** — "Remind me at 5pm to check my email" (background agent)
- [ ] **Saved macros** — record a multi-step workflow, replay it with one tap
- [ ] **iOS port** — same architecture, different native layer
- [ ] **Custom tool plugins** — user-defined shortcuts and integrations

---

## Contributing

PRs welcome. The cleanest way to add capability is to:
1. Add a new entry to `ToolName` in `AgentTools.kt`
2. Declare it in `GeminiClient.buildToolsJson()`
3. Implement it in `ActionExecutor.kt`
4. Handle it in `AgentLoop.executeToolCall()`

The agent system prompt lives in `GeminiClient.SYSTEM_PROMPT` — app-specific UI patterns go there.

---

## License

Apache 2.0 — see [LICENSE](LICENSE).
