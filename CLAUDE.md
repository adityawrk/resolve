# Resolve

AI agent that automates customer support chats on behalf of customers. Three surfaces: TypeScript/Express backend, Chrome Extension, Android companion app.

## Commands

```bash
# Backend
npm run dev          # Start dev server (tsx watch, port 8787)
npm test             # Run all tests (vitest)
npm run build        # Compile TypeScript to dist/

# Android (from android-companion/)
./gradlew assembleDebug    # Build debug APK
./gradlew test             # Run unit tests

# Emulator testing
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell settings put secure enabled_accessibility_services com.cssupport.companion/com.cssupport.companion.SupportAccessibilityService

# Upload APK to Google Drive (after any Android app change)
/opt/homebrew/bin/rclone copy app/build/outputs/apk/debug/app-debug.apk "gdrive:Resolve App/" --progress
```

## Deploy — Android APK

After any change to the Android app, **always build and upload the APK** to Google Drive:
1. `cd android-companion && ./gradlew assembleDebug`
2. `/opt/homebrew/bin/rclone copy app/build/outputs/apk/debug/app-debug.apk "gdrive:Resolve App/" --progress`

The user downloads APKs from the **"Resolve App"** folder in their Google Drive.

## Architecture

Three execution surfaces share the same observe-think-act loop:

1. **Backend** (`src/`) — Express server, Azure OpenAI GPT-5 Nano, WebSocket for extension, REST polling for mobile. All stores are in-memory.
2. **Chrome Extension** (`extension/`) — Manifest V3, detects 8 chat widget providers via DOM, routes state to backend over WebSocket.
3. **Android Companion** (`android-companion/`) — On-device LLM client, AccessibilityService for cross-app automation, foreground service. Runs fully standalone without backend.

Case flow: `queued → running → [paused_for_approval → running] → completed | failed`

## Android Agent Architecture — Gold-Standard Patterns

The Android agent implements research-backed patterns from DroidRun (91.4% AndroidWorld), Minitap (100% AndroidWorld), AppAgent (Tencent), Mobile-Agent v3, MobileUse, PhoneClaw/OpenClaw, and OpenAI Codex CLI. These are load-bearing design decisions — do not regress them.

### 1. Numbered Element References
Every clickable/editable UI element gets a `[N]` ID (1-based, spatially ordered). The LLM says `click_element(7)` instead of fuzzy label matching. This is the single most impactful pattern — every top mobile agent uses it.
- **File**: `AccessibilityEngine.kt` — `elementIndex: Map<Int, UIElement>` built during `captureScreen()`
- **Format**: `[N] type: "label" (position)` with state annotations `[CHECKED]`, `[DISABLED]`, `[SELECTED]`
- **Tool**: `click_element` accepts `elementId: Int` (preferred) with `label: String` fallback

### 2. Post-Action Verification
After every action, capture the screen again and verify the action actually worked. This adds +15 points in Minitap's ablation study.
- **File**: `AgentLoop.kt` — `verifyAction()` compares pre/post screen fingerprints
- For `TypeMessage`: reads back the field text to confirm input was accepted
- For `ClickElement`: checks that the screen changed (fingerprint differs)
- Verification failures are reported to the LLM as explicit feedback (Codex's RespondToModel pattern)

### 3. Sub-Goal Decomposition
Break the task into sub-goals and track progress. This adds +21 points in Minitap's ablation study.
- **File**: `AgentLoop.kt` — 8 hardcoded sub-goals for support flow (find orders → select order → find help → etc.)
- Progress tracked via `updateSubGoalProgress()` and included in every LLM message
- `NavigationPhase` enum: `NAVIGATING_TO_SUPPORT → ON_ORDER_PAGE → ON_SUPPORT_PAGE → IN_CHAT`

### 4. Observation Masking (Context Management)
Full conversation history is maintained, but old screen observations are replaced with one-line summaries while all action traces are preserved. This is better than LLM summarization (JetBrains Research, Dec 2025).
- **File**: `AgentLoop.kt` — `applyObservationMasking()` keeps last 4 turns verbatim
- Older `UserObservation` messages get replaced with `"[Screen: pkg/activity, Phase: X, Turn: N]"`
- All `AssistantToolCall` and `ToolResult` messages are kept intact (action memory)

### 5. Screen Stabilization + Loading Race Condition Fix
Poll the screen until two consecutive captures produce the same fingerprint. Prevents acting on loading/transition screens.
- **File**: `AccessibilityEngine.kt` — `waitForStableScreen(maxWaitMs=3000, pollIntervalMs=300)`
- **File**: `AgentLoop.kt` — `waitForMeaningfulScreen()` on first iteration, waits up to 8s for 3+ interactive elements (prevents acting on splash screens like Dominos "Setting your Location...")

### 6. Differential State Tracking
Tell the LLM exactly what changed between screens — new elements, removed elements, app/activity changes.
- **File**: `AgentLoop.kt` — `detectChanges()` returns strings like `"NEW_APP: Changed from X to Y"`, `"CONTENT_UPDATED: New elements: Help, Contact Us"`
- `AccessibilityEngine.kt` — `newElementLabels()`, `removedElementLabels()`, `toMaskedSummary()`

### 7. Multi-Turn Conversation
Full message history (observation → tool call → tool result) is sent to the LLM each turn, not single-shot.
- **File**: `LLMClient.kt` — `chatCompletion()` accepts `conversationMessages: List<ConversationMessage>?`
- `ConversationMessage` sealed class: `UserObservation`, `AssistantToolCall`, `ToolResult`

### 8. Recovery Mechanisms
- **Oscillation detection**: If the agent ping-pongs between two screens (A→B→A→B), it's flagged and the LLM is told to try a different approach
- **Stagnation detection**: If the screen hasn't changed for 3+ turns, escalate
- **Scroll budget**: Max 8 consecutive scrolls before forcing a different action
- **App re-launch**: `launchTargetApp` lambda passed to AgentLoop for recovering from wrong-app situations
- **No-tool-call recovery**: After 2+ empty responses, injects forceful directive without counting as iteration
- **Element banning**: Elements that led to wrong screens (wallet, product pages) are permanently banned for the session

### 9. Plan Tool (Codex Pattern)
No-op `update_plan` tool that forces the LLM to externalize its reasoning as a structured checklist.
- **File**: `LLMClient.kt` — tool definition with `explanation` + `steps[]` (pending/in_progress/completed)
- **File**: `AgentLoop.kt` — plan calls don't count as iterations, result recorded in conversation history
- Inspired by Codex CLI's plan tool: *"This function doesn't do anything useful. However, it gives the model a structured way to record its plan."*

### 10. App-Scope Restriction
Prevents the agent from interacting with non-target apps (email, browser, etc.).
- **File**: `AgentLoop.kt` — compares `screenState.packageName` against target
- First wrong-app detection: warns LLM + presses back
- After 2 consecutive wrong-app turns: auto-relaunches target app

### 11. Navigation-First Element Display
During navigation phases, product/content elements are collapsed (lose `[N]` IDs) so the LLM literally cannot click them.
- **File**: `AccessibilityEngine.kt` — `isNavigationElement()` classifier separates nav keywords from products
- **File**: `AgentLoop.kt` — `collapseContent` flag active during `NAVIGATING_TO_SUPPORT` and `ON_ORDER_PAGE`
- Bottom bar shown first with `>>>` markers, content elements shown as single summary line

### 12. App-Specific Navigation Knowledge
Human-curated navigation profiles injected into the system prompt for known apps.
- **File**: `AppNavigationKnowledge.kt` — profiles for Dominos, Swiggy, Zomato, Amazon, Flipkart, Uber, Ola
- Each profile: `supportPath` (ordered steps), `pitfalls` (wrong turns to avoid), `profileLocation`, `orderHistoryLocation`
- Unknown apps get generic navigation hints

## Key File Responsibilities (Android)

| File | Role | When to modify |
|------|------|----------------|
| `AccessibilityEngine.kt` | Perception layer — captures UI tree, assigns element IDs, formats for LLM, executes low-level actions | Adding new element types, changing screen format, new action primitives |
| `AgentLoop.kt` | Brain — observe-think-act cycle, verification, sub-goals, conversation management, recovery, prompts | Changing agent behavior, adding sub-goals, modifying prompts |
| `LLMClient.kt` | Multi-provider HTTP client — builds messages, parses tool calls, manages conversation format | Adding tools, changing providers, modifying API interaction |
| `CompanionAgentService.kt` | Foreground service — lifecycle, model migration, app launching, notification management | Service lifecycle changes, new intent actions |
| `SafetyPolicy.kt` | PII detection, financial action gates, iteration limits | New action types need validation rules added |
| `AuthManager.kt` | Triple-layer credential storage (encrypted + backup + try-catch fallback) | Auth flow changes |
| `AppNavigationKnowledge.kt` | Human-curated app navigation profiles | Adding support for new apps |
| `SupportAccessibilityService.kt` | Android AccessibilityService, floating stop button overlay | Accessibility event handling, overlay UI |

### Adding a New Agent Action
Update these files in order:
1. `AgentAction` sealed class in `LLMClient.kt` (add new subclass)
2. `LLMClient.buildToolDefinitions()` (add tool definition)
3. `LLMClient.parseToolCallFromJson()` (parse tool call)
4. `AgentLoop.executeAction()` (execute the action)
5. `AgentLoop.describeAction()` (human-readable description)
6. `AgentLoop.toolNameFromAction()` / `toolArgsFromAction()` (conversation tracking)
7. `AgentLoop.logAction()` (user-visible logging)
8. `AgentLoop.verifyAction()` (add verification branch or null for no-verify)
9. `SafetyPolicy.validate()` (safety rules for the new action)

### Adding a New App Navigation Profile
1. Add entry to `AppNavigationKnowledge.profiles` map (package name → `AppNavProfile`)
2. Add entry to `AppNavigationKnowledge.nameIndex` map (fuzzy name → profile)
3. Profile must include: `supportPath` (ordered steps), `pitfalls` (common wrong turns), `profileLocation`, `orderHistoryLocation`
4. Test by running the agent against the app — pitfalls are the most valuable part

## Key Conventions

- Backend uses ES modules (`"type": "module"`) — all imports need `.js` extension
- Zod validates all API route inputs; never trust raw `req.body`
- Extension JS uses IIFEs for module isolation (no bundler): `ResolveDetector`, `ResolveExtractor`, `ResolveExecutor`, `ResolveBus`
- Android minSdk 28, targetSdk 35, Kotlin, Java 17, View system (no Compose)
- Android credentials stored via `AuthManager` using EncryptedSharedPreferences — never write to SharedPreferences directly for secrets
- `AgentLogStore` drives the MonitorActivity feed via `StateFlow` — always call `AgentLogStore.log()` for user-visible events
- Default LLM model is `gpt-5-mini` — the GPT-4o series is retired/deprecated
- `CompanionAgentService` auto-migrates deprecated models (gpt-4o, gpt-4o-mini, gpt-4-turbo, gpt-3.5-turbo → gpt-5-mini) on every agent run

## LLM & Auth

- **Providers supported**: Azure OpenAI, OpenAI, Anthropic, Custom endpoint
- **ChatGPT OAuth**: Uses Codex public client ID `app_EMoamEEZ73f0CkXaXp7hrann` with `localhost:1455` callback. Same client ID as Codex CLI, Roo Code, OpenClaw. No custom URI scheme.
- **Azure AI Foundry**: `LLMClient` auto-strips `/api/projects/` path from endpoints (Foundry URLs don't work with the OpenAI SDK directly)
- **Token refresh**: OAuth tokens are refreshed in `CompanionAgentService.runLocalLoop()` before the agent starts
- **Retry logic**: Exponential backoff with jitter (`base × 2^attempt × random(0.9, 1.1)`). Errors classified as terminal (401/403/404/quota) vs retryable (429/5xx/timeout/network). Max 5 retries.

## Samsung & Android-Specific Gotchas

These are hard-won lessons from real-device testing. Do not ignore them.

### EncryptedSharedPreferences / Keystore
- Samsung devices corrupt the Android Keystore unpredictably — EncryptedSharedPreferences can lose data or throw on read
- **Solution**: Triple-layer storage — encrypted prefs (primary) + plain SharedPreferences (backup) + try-catch around every read
- `AuthManager` dual-writes to both layers; reads try encrypted first, falls back to backup
- API key field in Settings shows masked value (`••••••••xxxx`) so users know it's saved; Save preserves existing key when field unchanged
- Never use alpha/beta Android security libraries on Samsung — stable releases only

### Restricted Settings (Android 13+)
- Sideloaded APKs (from Google Drive, not Play Store) are blocked from enabling accessibility services by Samsung/Android 13+
- **Solution**: `OnboardingActivity` shows a "First: Allow restricted settings" card on API 33+ that guides users to App Info → ⋮ menu → Allow restricted settings
- This card is hidden on Android 12 and below where the restriction doesn't exist

### AccessibilityService Reliability
- `rootInActiveWindow` returns null during popup/drawer transitions — always have fallback logic
- `AccessibilityNodeInfo` objects go stale — wrap all node operations in try-catch, never hold references across async boundaries
- System overlays (keyboard, notification shade, SystemUI) should NOT update the tracked foreground package — filter by package name
- `TYPE_ACCESSIBILITY_OVERLAY` is used for the floating stop button — no extra permission needed for accessibility services

### Floating Stop Button
- Shown by `SupportAccessibilityService` when agent starts, hidden on any terminal state
- Draggable red pill "Stop Resolve", tap to stop via `CompanionAgentService.stop()`
- Uses `TYPE_ACCESSIBILITY_OVERLAY` — works over all apps without `SYSTEM_ALERT_WINDOW` permission

## Safety — Do Not Bypass

The policy layers are load-bearing. Do not weaken or skip them:

- **PII filter** (`sensitive-filter.ts`) — strips SSN, CC, CVV before LLM sees data
- **Action policy** (`policy.ts`) — blocks sensitive data in outbound messages, requires approval for financial actions
- **SafetyPolicy.kt** — Android-side SSN/CC/password detection, financial keyword gates, 30-iteration hard limit
- **Human-in-the-loop** — cases pause for approval on financial commitments; this is intentional friction
- **Element banning** — elements that led to wrong screens are permanently blocked for the session
- **App-scope restriction** — agent auto-corrects when it strays to non-target apps

## Agent Loop Performance

Current speed optimizations (do not regress):
- `MIN_ACTION_DELAY_MS`: 800ms (was 2000ms)
- Verification delay: 300ms
- Screen stabilization: poll 300ms, initial delay 250ms, max wait 3000ms
- **Pipelining**: Post-verification screen reused as next iteration's input (saves ~500ms per iteration)
- `max_completion_tokens`: 512 (tool calls rarely exceed 300 tokens)
- Tool descriptions compressed ~60% (saves ~300 tokens per request)
- Anthropic token-efficient tool use beta header enabled
- HTTP connect timeout: 15s, read timeout: 45s
- Compact screen format: text-only elements shown as single summary line, only interactive elements get `[N]` IDs
- **Net result**: ~3-4s per iteration (was ~6-8s), roughly 2x speedup

## Testing

Backend tests use vitest with ES modules. Test files live in `tests/`. Pattern: arrange-act-assert with helper factories. Run a single test: `npx vitest run tests/policy.spec.ts`.

Android: No emulator available — user tests on physical Samsung phone. Crashes must be caught via try-catch with graceful fallbacks. Alpha/beta Android libraries are risky on Samsung (Keystore, Biometric, etc.).

## Environment

Copy `.env.example` to `.env`. Required: `AZURE_OPENAI_API_KEY`, `AZURE_OPENAI_ENDPOINT`. The extension connects to `http://127.0.0.1:8787` by default (hardcoded in `service-worker.js`).

## Research References

Agent architecture is informed by these papers/projects (research docs in `android-companion/`):
- **DroidRun** — 91.4% on AndroidWorld. Screen stabilization (poll-until-stable), spatial zone formatting.
- **Minitap** — 100% on AndroidWorld. Ablation: sub-goal decomposition (+21pts), post-action verification (+15pts), action description (+12pts).
- **AppAgent (Tencent)** — Self-learning from exploration, documentation-guided navigation.
- **Mobile-Agent v3** — Multi-agent with reflection agent for self-correction.
- **MobileUse** — Dual-model (planner + actor), semantic state diff.
- **PhoneClaw/OpenClaw** — Open-source phone agent framework, numbered element IDs, tool-based action space.
- **JetBrains Research (Dec 2025)** — Observation masking beats LLM summarization for context management.
- **OpenAI Codex CLI** — Plan tool (no-op for structured reasoning), exponential backoff with jitter, error classification (terminal vs retryable), RespondToModel pattern. Full analysis in `android-companion/RESEARCH_CODEX.md`.

Detailed notes: `AGENT_RESEARCH.md`, `RESEARCH_PROMPTING.md`, `RESEARCH_OPENCLAW.md`, `RESEARCH_CODEX.md` in `android-companion/`.
