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
```

## Architecture

Three execution surfaces share the same observe-think-act loop:

1. **Backend** (`src/`) — Express server, Azure OpenAI GPT-5 Nano, WebSocket for extension, REST polling for mobile. All stores are in-memory.
2. **Chrome Extension** (`extension/`) — Manifest V3, detects 8 chat widget providers via DOM, routes state to backend over WebSocket.
3. **Android Companion** (`android-companion/`) — On-device LLM client, AccessibilityService for cross-app automation, foreground service. Runs standalone without backend.

Case flow: `queued → running → [paused_for_approval → running] → completed | failed`

## Key Conventions

- Backend uses ES modules (`"type": "module"`) — all imports need `.js` extension
- Zod validates all API route inputs; never trust raw `req.body`
- Extension JS uses IIFEs for module isolation (no bundler): `ResolveDetector`, `ResolveExtractor`, `ResolveExecutor`, `ResolveBus`
- Android minSdk 28 (required for EncryptedSharedPreferences)
- Android credentials stored via `AuthManager` using EncryptedSharedPreferences — never write to SharedPreferences directly for secrets
- `AgentLogStore` drives the MonitorActivity feed via `StateFlow` — always call `AgentLogStore.log()` for user-visible events

## Safety — Do Not Bypass

The policy layers are load-bearing. Do not weaken or skip them:

- **PII filter** (`sensitive-filter.ts`) — strips SSN, CC, CVV before LLM sees data
- **Action policy** (`policy.ts`) — blocks sensitive data in outbound messages, requires approval for financial actions
- **SafetyPolicy.kt** — Android-side SSN/CC/password detection, financial keyword gates, 30-iteration hard limit
- **Human-in-the-loop** — cases pause for approval on financial commitments; this is intentional friction

## Testing

Backend tests use vitest with ES modules. Test files live in `tests/`. Pattern: arrange-act-assert with helper factories. Run a single test: `npx vitest run tests/policy.spec.ts`.

## Environment

Copy `.env.example` to `.env`. Required: `AZURE_OPENAI_API_KEY`, `AZURE_OPENAI_ENDPOINT`. The extension connects to `http://127.0.0.1:8787` by default (hardcoded in `service-worker.js`).
