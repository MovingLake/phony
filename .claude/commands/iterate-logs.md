Pull the latest Phony logs from the connected Android device and iterate on any issues found.

## Steps

### 1. Pull logs by PID

```bash
PID=$(~/Android/Sdk/platform-tools/adb shell pidof com.phoneclaw.debug 2>/dev/null | tr -d '\r')
echo "PID: $PID"
```

If PID is empty, tell the user the app isn't running — open Phony and enable the accessibility service first.

If PID is found, pull logs:
```bash
~/Android/Sdk/platform-tools/adb logcat -d -v time --pid=$PID 2>&1
```

If output is empty, fall back to tag filtering:
```bash
~/Android/Sdk/platform-tools/adb logcat -d -v time -s AgentLoop:V GeminiClient:V ClaudeClient:V OpenAIClient:V ActionExecutor:V PhoneclawService:V ScreenshotManager:V SpeechManager:V 2>&1
```

If still empty, tell the user the buffer probably rolled over and suggest running this before their next test:
```bash
~/Android/Sdk/platform-tools/adb logcat -G 16M && ~/Android/Sdk/platform-tools/adb logcat -c
```

### 2. Analyze the logs

For each task run (separated by `── Task start ──` markers), identify:
- What the user said
- Which tools were called and in what order
- Any tool failures (`ok=false`)
- Any reasoning loops (nudge messages fired, `nudging (N/4)`)
- Any system-echo events (`System-echo suppressed`)
- Any API errors (4xx, 5xx)
- Whether the task completed via `task_complete` or stopped early
- Any unexpected behavior (wrong element clicked, loop never ended, etc.)

### 3. Identify root causes

Common patterns:
- `find_and_click_text` failing → label mismatch, add text variant to system prompt
- Nudge fired → model narrating instead of acting, check `looksLikeReasoning()`
- 400 API error → session history format issue
- `tap` wrong target → coordinate scaling issue
- No `task_complete` → `maxIterations` hit or early exit
- `get_screen_text` returning Phony UI text → own-package filter issue
- 429 → API credits depleted, switch provider or get a free-tier key

### 4. Implement fixes

Apply fixes directly to source files, prioritising:
1. Crashes / API errors
2. Wrong behavior
3. Unnecessary round-trips / performance

### 5. Summarise

- 🔴 **[issue]** → **[fix applied]**
- 🟡 **[observation]** → no fix needed
