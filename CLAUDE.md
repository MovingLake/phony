# PhoneClaw — Claude Code Project Guide

## What is PhoneClaw?

PhoneClaw is an Android AI agent harness — a "nanoclaw for your phone". It runs as an accessibility service, shows a floating FAB button over all apps, and lets users give voice commands that Gemini then executes as real actions on the phone screen.

**Hero use case**: "Open Maps and find me good taco spots nearby" → PhoneClaw opens Google Maps, searches for tacos, reads the results, and speaks back the top 3 — all hands-free.

## Architecture

```
User speaks → STT (Android SpeechRecognizer)
           → Screenshot + command → Gemini 2.0 Flash (tool-use loop)
           → Tool calls executed (tap/swipe/type/open_app/…)
           → Fresh screenshot → Gemini → … (loop)
           → task_complete → TTS speaks result
```

### Key design principle (inherited from nanoclaw)
Tool calls are the **sole IO surface**. The agent loop sends screenshots and text to Gemini, receives tool calls back, executes them, and feeds results back. Nothing else. No sideband state, no direct UI manipulation outside of tool execution.

### Package structure
```
com.phoneclaw/
  MainActivity.kt                  # Compose onboarding + permissions
  PhoneclawApp.kt                  # Application class, notification channel
  data/
    PreferencesManager.kt          # DataStore — API key, model selection
  service/
    PhoneclawAccessibilityService.kt  # Core service — lifecycle, FAB, STT trigger
    FloatingButtonManager.kt          # WindowManager overlay, drag, state animation
  agent/
    AgentLoop.kt                   # Main tool-use loop (suspend, coroutine-based)
    GeminiClient.kt                # Gemini API, function declarations, system prompt
    ActionExecutor.kt              # Translates tool calls → Android gestures/actions
    AppResolver.kt                 # Natural app name → package name + launch intent
    ScreenshotManager.kt           # AccessibilityService.takeScreenshot() wrapper
    AgentTools.kt                  # ToolName enum, ToolCall, ToolResult data classes
  speech/
    SpeechManager.kt               # SpeechRecognizer (STT) + TextToSpeech (TTS)
  ui/
    theme/Theme.kt                 # Material3 dark theme
    screens/OnboardingScreen.kt    # API key input + permission checklist
```

## Build

### Prerequisites
- Android Studio Ladybug (2024.2+) or newer
- JDK 17
- Android device / emulator running API 30+ (Android 11+)

### Build from command line
```bash
# Requires ANDROID_HOME to be set
./gradlew assembleDebug
./gradlew installDebug
```

### Build from Android Studio
Open the project, wait for Gradle sync, then Run > Run 'app'.

## Key Android APIs Used

| API | Purpose |
|-----|---------|
| `AccessibilityService` | Core — reads screen, performs actions |
| `AccessibilityService.takeScreenshot()` | Screen capture (API 30+, requires `canTakeScreenshot="true"`) |
| `GestureDescription` | Tap, long press, swipe |
| `AccessibilityNodeInfo` | Read screen text, find UI elements by text, click them |
| `WindowManager` | Floating overlay button |
| `SpeechRecognizer` | Voice input (no extra API key, uses Google STT) |
| `TextToSpeech` | Speak results back to user |
| `DataStore` | Store Gemini API key securely |

## Agent Tools (Gemini Function Declarations)

| Tool | What it does |
|------|-------------|
| `take_screenshot` | Refresh the agent's view of the screen |
| `tap(x, y)` | Tap at pixel coordinates |
| `long_press(x, y)` | Long press at coordinates |
| `swipe(startX, startY, endX, endY, durationMs)` | Swipe gesture |
| `type_text(text)` | Type into focused field via ACTION_SET_TEXT |
| `open_app(app_name)` | Launch app by natural name |
| `press_back` | Android back button |
| `press_home` | Android home button |
| `press_recent_apps` | Open multitasking view |
| `scroll(direction, x, y)` | Scroll UP/DOWN/LEFT/RIGHT |
| `find_and_click_text(text)` | Find element by accessibility text and click |
| `get_screen_text` | Extract all text from accessibility tree |
| `speak_to_user(message)` | TTS — speak to user mid-task |
| `task_complete(summary)` | End the loop, speak final summary |

## Permissions Required

1. **Accessibility Service** — granted in Settings → Accessibility → PhoneClaw
2. **Draw over other apps** (SYSTEM_ALERT_WINDOW) — granted in Settings → Apps → Special access
3. **Microphone** (RECORD_AUDIO) — runtime permission, prompted normally

## Configuration
- Gemini API key stored in DataStore (encrypted at rest on Android 11+)
- Model selection: default `gemini-2.0-flash`, configurable via `PreferencesManager`
- Max agent iterations per task: 20 (configurable in `GeminiClient`)

## Future Roadmap
- [ ] Local model support (Gemma via MediaPipe LLM Inference API)
- [ ] iOS port (same architecture, different native layer)
- [ ] Conversation history across tasks (short-term memory)
- [ ] Custom tool plugins (user-defined shortcuts)
- [ ] Background scheduled tasks ("remind me at 5pm to…")
- [ ] Multi-step workflows / saved macros

## Common Issues

**"Screenshot failed"**: Ensure the accessibility service is enabled and `canTakeScreenshot="true"` is in the service config.

**"No element found with text"**: The app may use custom views without accessibility labels. Fall back to `tap(x, y)` using screenshot coordinates.

**FAB not showing**: Ensure "Draw over other apps" permission is granted for PhoneClaw.

**STT not working**: Ensure microphone permission is granted and Google app is installed (needed for on-device STT).
