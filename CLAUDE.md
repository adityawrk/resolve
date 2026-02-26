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

The Android agent implements research-backed patterns from DroidRun (91.4% AndroidWorld), Minitap (100% AndroidWorld), AppAgent (Tencent), Mobile-Agent v3, MobileUse, and PhoneClaw/OpenClaw. These are load-bearing design decisions — do not regress them.

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
- Verification failures are reported to the LLM as explicit feedback

### 3. Sub-Goal Decomposition
Break the task into sub-goals and track progress. This adds +21 points in Minitap's ablation study.
- **File**: `AgentLoop.kt` — 8 hardcoded sub-goals for support flow (find orders → select order → find help → etc.)
- Progress tracked via `updateSubGoalProgress()` and included in every LLM message
- `NavigationPhase` enum: `NAVIGATING_TO_SUPPORT → ON_ORDER_PAGE → ON_SUPPORT_PAGE → IN_CHAT`

### 4. Observation Masking (Context Management)
Full conversation history is maintained, but old screen observations are replaced with one-line summaries while all action traces are preserved. This is better than LLM summarization (JetBrains Research, Dec 2025).
- **File**: `AgentLoop.kt` — `maskOldObservations()` keeps last 4 turns verbatim
- Older `UserObservation` messages get replaced with `"[Turn N: was on {app}/{activity}, took {action}]"`
- All `AssistantToolCall` and `ToolResult` messages are kept intact (action memory)

### 5. Screen Stabilization
Poll the screen until two consecutive captures produce the same fingerprint. Prevents acting on loading/transition screens.
- **File**: `AccessibilityEngine.kt` — `waitForStableScreen(maxWaitMs=3000, pollIntervalMs=500)`
- Called before every observation in the agent loop

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

## Key File Responsibilities (Android)

| File | Role | When to modify |
|------|------|----------------|
| `AccessibilityEngine.kt` | Perception layer — captures UI tree, assigns element IDs, formats for LLM, executes low-level actions | Adding new element types, changing screen format, new action primitives |
| `AgentLoop.kt` | Brain — observe-think-act cycle, verification, sub-goals, conversation management, recovery | Changing agent behavior, adding sub-goals, modifying prompts |
| `LLMClient.kt` | Multi-provider HTTP client — builds messages, parses tool calls, manages conversation format | Adding tools, changing providers, modifying API interaction |
| `CompanionAgentService.kt` | Foreground service — lifecycle, model migration, app launching, notification management | Service lifecycle changes, new intent actions |
| `SafetyPolicy.kt` | PII detection, financial action gates, iteration limits | New action types need validation rules added |
| `AuthManager.kt` | EncryptedSharedPreferences for API keys and OAuth tokens | Auth flow changes |

### Adding a New Agent Action
Update these files in order:
1. `AgentAction` sealed class (add new subclass)
2. `LLMClient.buildToolDefinitions()` (add tool definition)
3. `LLMClient.parseToolCallFromJson()` (parse tool call)
4. `AgentLoop.executeAction()` (execute the action)
5. `AgentLoop.describeAction()` (human-readable description)
6. `AgentLoop.toolNameFromAction()` / `toolArgsFromAction()` (conversation tracking)
7. `SafetyPolicy.validate()` (safety rules for the new action)

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

## Safety — Do Not Bypass

The policy layers are load-bearing. Do not weaken or skip them:

- **PII filter** (`sensitive-filter.ts`) — strips SSN, CC, CVV before LLM sees data
- **Action policy** (`policy.ts`) — blocks sensitive data in outbound messages, requires approval for financial actions
- **SafetyPolicy.kt** — Android-side SSN/CC/password detection, financial keyword gates, 30-iteration hard limit
- **Human-in-the-loop** — cases pause for approval on financial commitments; this is intentional friction

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

Detailed notes: `AGENT_RESEARCH.md`, `RESEARCH_PROMPTING.md`, `RESEARCH_OPENCLAW.md` in `android-companion/`.
