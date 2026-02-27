---
globs: ["android-companion/**/*.kt", "android-companion/**/*.xml", "android-companion/**/*.gradle.kts"]
---

# Android Rules

## Build

```bash
cd android-companion && ./gradlew assembleDebug
```

minSdk 28, targetSdk 35, Kotlin, Java 17. No Jetpack Compose — uses View system with Material 3 components.

## Package & Key Classes

Package: `com.cssupport.companion`

| Class | Role |
|-------|------|
| `AccessibilityEngine` | Wraps AccessibilityService; captures screen state, executes clicks/types/scrolls |
| `AgentLoop` | On-device observe-think-act cycle (max 30 iterations) |
| `LLMClient` | Multi-provider HTTP client (Azure/OpenAI/Anthropic/Custom) with tool calling |
| `CompanionAgentService` | Foreground service; owns the coroutine scope; two modes: backend-polling and local |
| `SafetyPolicy` | PII detection, financial action gates, iteration limits |
| `AuthManager` | EncryptedSharedPreferences for API keys and tokens |
| `SupportAccessibilityService` | Android AccessibilityService; singleton pattern via `instance` companion property |

## Activity Flow

`WelcomeActivity → OnboardingActivity → MainActivity → MonitorActivity → CompleteActivity`

Settings is reachable from MainActivity's gear icon. WelcomeActivity auto-skips to Main if credentials + accessibility are both configured.

## Sealed Classes

Actions and results use Kotlin sealed classes extensively:
- `AgentAction` — TypeMessage, ClickButton, ScrollDown, ScrollUp, Wait, UploadFile, PressBack, RequestHumanReview, MarkResolved
- `AgentResult` — Resolved, Failed, NeedsHumanReview, Cancelled
- `ActionResult` — Success, Failed, Resolved, HumanReviewNeeded
- `PolicyResult` — Allowed, NeedsApproval, Blocked

When adding a new action: update `AgentAction`, `LLMClient.buildToolDefinitions()`, `LLMClient.parseToolCallFromJson()`, `AgentLoop.executeAction()`, and `AgentLoop.describeAction()`.

## Threading

- `CompanionAgentService` uses `SupervisorJob() + Dispatchers.IO` for its coroutine scope
- `LLMClient.chatCompletion()` runs on `Dispatchers.IO` via `withContext`
- `AccessibilityEngine` methods run on the accessibility service's main thread; use `@Volatile` for cross-thread state
- `AgentLogStore.entries` is a `StateFlow` — collect it in `lifecycleScope` with `repeatOnLifecycle(STARTED)`

## XML Layouts

- Page padding: 24dp horizontal (`@dimen/page_horizontal`)
- All text fields use `@style/Resolve.TextInput` (outlined, 16dp corners)
- Primary CTA: `@style/Resolve.Button.Primary` (56dp height, full width)
- Chip selection: `@style/Resolve.Chip` with `singleSelection="true"`
- Theme: `Theme.Resolve.Dark` (OLED true black, purple seed `#6C5CE7`)

## NEVER

- Never store API keys in plain SharedPreferences — always use `AuthManager`
- Never call `AccessibilityNodeInfo` methods after recycling the node
- Never skip `SafetyPolicy.validate()` before executing an action
- Never use `android:exported="true"` on activities other than WelcomeActivity in release builds
