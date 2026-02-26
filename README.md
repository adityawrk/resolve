# Resolve - AI Customer Support Agent

Open-source Android agent that handles customer support chats **on behalf of the customer**.

You describe your issue once with evidence, and Resolve takes over: it navigates the app's support flow, clicks buttons, types messages, uploads files, and resolves your issue end-to-end. No more waiting in chat queues, repeating yourself to bots, or clicking through endless menus.

## How it works

```
You: "My order #12345 arrived damaged. I want a full refund."
     [attaches photo of damaged package]

Resolve: Opens the company's app
         -> Navigates to order history
         -> Finds the support/help section
         -> Describes the issue to support chat
         -> Uploads evidence photo
         -> Navigates the support flow
         -> Negotiates resolution
         -> Gets refund confirmation
         -> Notifies you: "Done. Refund of ₹499 approved."
```

## Architecture

Fully standalone Android app — no backend server, no cloud dependency. The app runs an on-device LLM client (bring your own API key or use ChatGPT OAuth) that drives an AccessibilityService to automate support chats across any app.

```
┌─────────────────────────────────────────────────┐
│              Android Companion App              │
│                                                 │
│  MainActivity                                   │
│    Issue form (app, order, description, photos)  │
│    → Launches CompanionAgentService              │
│                                                 │
│  CompanionAgentService (Foreground Service)      │
│    → AgentLoop (observe-think-act cycle)         │
│       → AccessibilityEngine (screen capture)     │
│       → LLMClient (Azure/OpenAI/Anthropic)       │
│       → SafetyPolicy (PII filter, action gates)  │
│                                                 │
│  SupportAccessibilityService                     │
│    → Captures UI tree from any app               │
│    → Executes clicks, types, scrolls             │
│    → Floating stop button overlay                │
│                                                 │
│  AuthManager                                     │
│    → EncryptedSharedPreferences + backup          │
│    → ChatGPT OAuth or bring-your-own-key          │
└─────────────────────────────────────────────────┘
```

## Agent tools

The LLM-powered agent has these tools:

| Tool | Description |
|------|-------------|
| `type_message` | Type text into the focused input field |
| `click_element` | Click a numbered UI element by ID |
| `scroll_down` / `scroll_up` | Scroll the current view |
| `press_back` | Press the Android back button |
| `wait` | Wait for content to load |
| `request_human_review` | Pause and ask the user for input |
| `mark_resolved` | Mark the issue as resolved |
| `update_plan` | Structured reasoning (no-op, forces the LLM to plan) |

## Quick start

### 1. Install the APK

Download the latest APK from the **"Resolve App"** folder in Google Drive, or build from source:

```bash
cd android-companion
./gradlew assembleDebug
# APK at: app/build/outputs/apk/debug/app-debug.apk
```

### 2. Configure

1. Open the app and choose your auth method:
   - **ChatGPT OAuth** — Sign in with your OpenAI account
   - **Bring your own key** — Enter API key for Azure OpenAI, OpenAI, Anthropic, or any compatible endpoint
2. Enable the accessibility service (the app guides you through this)
3. On Android 13+ with sideloaded APKs: allow restricted settings first (the app guides you)

### 3. Use it

1. Enter the app name (e.g., "Dominos", "Swiggy", "Amazon")
2. Describe your issue and desired outcome
3. Optionally attach photos as evidence
4. Tap **Resolve It**
5. Switch to the target app — the agent takes over
6. Watch progress in the notification or tap the floating stop button to cancel

## Supported apps

The agent works with any Android app, but has optimized navigation profiles for:

- **Dominos India** — pizza order support
- **Swiggy** — food delivery support
- **Zomato** — food delivery support
- **Amazon** — order support
- **Flipkart** — order support
- **Uber** — ride support
- **Ola** — ride support

For unlisted apps, the agent uses generic navigation heuristics.

## Safety model

- **PII filtering**: SSN, credit card, password patterns detected and blocked before reaching the LLM.
- **Financial action gates**: Actions involving refunds or payments require explicit user approval.
- **30-iteration hard limit**: Agent stops after 30 actions to prevent runaway loops.
- **Element banning**: UI elements that led to wrong screens are permanently blocked.
- **App-scope restriction**: Agent auto-corrects when it navigates to non-target apps.
- **Floating stop button**: Always visible — tap to immediately stop the agent.

## Project structure

```
cs-support/
├── android-companion/           # Android app (Kotlin, View system, Material 3)
│   ├── app/src/main/java/com/cssupport/companion/
│   │   ├── AccessibilityEngine.kt    # UI tree capture, element IDs, action execution
│   │   ├── AgentLoop.kt              # Observe-think-act cycle, recovery, prompts
│   │   ├── LLMClient.kt              # Multi-provider LLM client with tool calling
│   │   ├── CompanionAgentService.kt   # Foreground service lifecycle
│   │   ├── SafetyPolicy.kt           # PII detection, action gates
│   │   ├── AuthManager.kt            # Encrypted credential storage
│   │   ├── AppNavigationKnowledge.kt  # App-specific navigation profiles
│   │   ├── SupportAccessibilityService.kt # AccessibilityService + stop button
│   │   ├── MainActivity.kt           # Issue form
│   │   ├── WelcomeActivity.kt        # Auth flow entry
│   │   ├── OnboardingActivity.kt     # Accessibility setup guide
│   │   ├── MonitorActivity.kt        # Live agent activity feed
│   │   └── CompleteActivity.kt       # Resolution summary
│   ├── app/src/main/res/             # Layouts, strings, themes
│   ├── AGENT_RESEARCH.md             # Research papers analysis
│   ├── RESEARCH_CODEX.md             # Codex CLI patterns analysis
│   └── build.gradle.kts              # Build config
├── CLAUDE.md                         # Development instructions
├── README.md                         # This file
└── LICENSE                           # MIT
```

## Contributing

1. Fork the repo and create a feature branch
2. Build: `cd android-companion && ./gradlew assembleDebug`
3. Test on a real Android device (Samsung recommended)
4. Keep PRs focused and small
5. Do not commit API keys or credentials

### Good first contributions

- **Add app navigation profiles** — Test with a new app, document the support flow path and pitfalls, add to `AppNavigationKnowledge.kt`
- **Improve chat phase** — Better handling of chatbot menus, file uploads, multi-turn conversations
- **Add tests** — Unit tests for SafetyPolicy, AgentLoop logic, LLMClient parsing

## License

MIT
