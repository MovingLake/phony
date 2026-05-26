Pull the latest Phony logs from the connected Android device and iterate on any issues found.

## Steps

1. **Pull logs from the device** using adb. Run this command and capture the output:
   ```
   adb logcat -d -v time -s AgentLoop:V GeminiClient:V ClaudeClient:V OpenAIClient:V ActionExecutor:V PhoneclawService:V ScreenshotManager:V SpeechManager:V AppResolver:V
   ```
   If adb is not found, try `~/Android/Sdk/platform-tools/adb logcat -d -v time -s AgentLoop:V GeminiClient:V ClaudeClient:V OpenAIClient:V ActionExecutor:V PhoneclawService:V ScreenshotManager:V SpeechManager:V AppResolver:V`

   If the output is very long (>300 lines), focus on the last 300 lines — they contain the most recent test run.

2. **Read and analyze the logs**. For each logged task (separated by "── Task start ──" markers), identify:
   - What the user said
   - Which tools were called and in what order
   - Any tool failures (`ok=false`)
   - Any reasoning loops (nudge messages fired)
   - Any system-echo events (suppressed text)
   - Any API errors (4xx, 5xx)
   - Whether the task completed correctly or stopped early
   - Any unexpected behavior (wrong app opened, wrong element clicked, loop that never ended, etc.)

3. **Identify the root causes**. Common patterns to look for:
   - `find_and_click_text` failing repeatedly → element label mismatch, need text variant in system prompt
   - Model emitting text instead of tool calls → reasoning nudge fired, check `looksLikeReasoning()`
   - 400 API errors → session history format issue (functionCall/functionResponse mismatch)
   - `tap` hitting wrong coordinates → coordinate scaling issue or wrong element
   - Task stopping after N steps without `task_complete` → `maxIterations` hit or early exit
   - `get_screen_text` returning PhoneClaw's own UI text → own-package filter not working
   - App not found → `AppResolver` doesn't know the package name

4. **Implement fixes** directly in the relevant source files. Prioritize by impact:
   - Crashes and API errors first
   - Wrong behavior (tapping wrong thing, missing content) second
   - Performance / unnecessary round-trips third

5. **Summarize** what you found and what you changed, with a short entry per issue:
   - 🔴 **[issue]** → **[fix applied]**
   - 🟡 **[observation]** → **[no fix needed / monitoring]**
