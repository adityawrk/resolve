# Resolve

**An AI agent that fights customer support battles for you.**

Resolve is an open-source Android app that automates customer support chats on your behalf. You describe your issue once — "my order arrived damaged, I want a refund" — and the agent takes over. It opens the company's app, navigates to support, explains your issue, uploads evidence, handles the back-and-forth with chatbots and agents, and gets your issue resolved. You never touch the chat.

No backend server. No cloud dependency. Your API key stays on your device.

## The Problem

Getting help from customer support is painful:

- **10 minutes clicking through menus** just to find the "Help" button
- **Repeating yourself** to bots that don't understand
- **Waiting in queue** while the chatbot asks "Can you describe your issue?"
- **Navigating 5 screens** of "Select your order → What went wrong → Tell us more"

You already know what you want. You just need someone to do the clicking and typing for you.

## The Solution

Resolve is an AI agent that sits between you and the support chat. It uses Android's AccessibilityService to see and interact with any app — the same API screen readers use. An LLM (GPT, Claude, Gemini, DeepSeek, or any OpenAI-compatible model) decides what to click, type, and scroll based on what's on screen.

```
You fill out one form:
  App:         "Dominos"
  Order:       "#12345 - Large Pepperoni"
  Issue:       "Arrived cold and 45 minutes late"
  Outcome:     "Full refund"
  Evidence:    [photo of cold pizza]

Resolve does the rest:
  1. Opens Dominos app
  2. Navigates to Order History → finds order #12345
  3. Taps "Help" → "Issue with order"
  4. Selects "Order arrived late" in the chatbot menu
  5. Types: "My order #12345 arrived 45 minutes late and the food was cold.
            I've attached a photo. I'd like a full refund."
  6. Uploads the evidence photo
  7. Handles follow-up questions from the support agent
  8. Gets confirmation: "Refund of ₹499 has been processed"
  9. Notifies you: Done.
```

## How It Works

Resolve runs an **observe-think-act loop** on your device:

```
┌─────────────────────────────────────────────┐
│                 Agent Loop                   │
│                                             │
│  1. OBSERVE                                 │
│     AccessibilityService captures the       │
│     screen: every button, text field,       │
│     label, and menu item gets a number      │
│     [1] Button: "Orders"                    │
│     [2] Button: "Help"                      │
│     [3] Text: "Order #12345 - Pepperoni"    │
│                                             │
│  2. THINK                                   │
│     LLM receives the numbered screen +      │
│     your issue + conversation history.      │
│     Decides: click_element(2)  # tap Help   │
│                                             │
│  3. ACT                                     │
│     AccessibilityService taps the button.   │
│     Verifies the screen actually changed.   │
│                                             │
│  4. REPEAT until resolved or stuck          │
└─────────────────────────────────────────────┘
```

Each iteration takes ~3-4 seconds. A typical support flow completes in 15-25 iterations.

## Architecture

The entire app runs on-device. No data leaves your phone except the LLM API calls to your chosen provider.

```
┌──────────────────────────────────────────────────────────┐
│                        Your Phone                        │
│                                                          │
│  ┌────────────────────┐    ┌──────────────────────────┐  │
│  │  Resolve App       │    │  Target App (e.g. Swiggy)│  │
│  │                    │    │                          │  │
│  │  Issue Form ───────┼───>│  [AccessibilityService   │  │
│  │  Monitor Feed      │    │   reads screen state,    │  │
│  │  Settings/Auth     │    │   performs taps/types]   │  │
│  └────────┬───────────┘    └──────────────────────────┘  │
│           │                                              │
│  ┌────────▼───────────┐                                  │
│  │  Agent Engine       │                                  │
│  │                    │    ┌──────────────────────────┐  │
│  │  AgentLoop         │───>│  LLM API (your key)     │  │
│  │  SafetyPolicy      │<───│  OpenAI / Anthropic /   │  │
│  │  AuthManager       │    │  Gemini / DeepSeek /... │  │
│  └────────────────────┘    └──────────────────────────┘  │
└──────────────────────────────────────────────────────────┘
```

### Key Components

| Component | What it does |
|-----------|-------------|
| **AccessibilityEngine** | Captures the UI tree from any app. Assigns numbered IDs to every interactive element. Executes taps, types, scrolls. |
| **AgentLoop** | The brain. Runs the observe-think-act cycle with sub-goal tracking, post-action verification, oscillation detection, and recovery mechanisms. |
| **LLMClient** | Multi-provider HTTP client. Sends numbered screen state + conversation history, receives tool calls. Works with OpenAI, Anthropic, Google Gemini, DeepSeek, or any OpenAI-compatible endpoint. |
| **SafetyPolicy** | Blocks PII (SSN, credit cards, passwords) from reaching the LLM. Gates financial actions behind user approval. Hard 30-iteration limit. |
| **AuthManager** | Stores API keys in EncryptedSharedPreferences with a plain-text backup layer for Samsung Keystore reliability. |
| **AppNavigationKnowledge** | Human-curated navigation profiles for popular apps (Dominos, Swiggy, Amazon, etc.). Tells the agent exactly how to reach the support section and what pitfalls to avoid. |

## Research-Backed Design

The agent architecture implements patterns from 7 published mobile agent papers and 1 production agent CLI, validated through ablation studies:

| Pattern | Source | Impact | Implementation |
|---------|--------|--------|----------------|
| **Numbered element IDs** | Every top agent (DroidRun, Minitap, OpenClaw) | Foundation | `[N]` IDs assigned spatially in `AccessibilityEngine` |
| **Sub-goal decomposition** | Minitap (100% AndroidWorld) | +21 points | 8 hardcoded sub-goals tracking navigation → chat phases |
| **Post-action verification** | Minitap ablation study | +15 points | Screen fingerprint comparison after every action |
| **Observation masking** | JetBrains Research (Dec 2025) | Better than LLM summarization | Old screens replaced with one-line summaries; action traces preserved |
| **Screen stabilization** | DroidRun (91.4% AndroidWorld) | Prevents acting on loading screens | Poll-until-stable with 3s timeout |
| **Plan tool** | OpenAI Codex CLI | Structured reasoning | No-op tool that forces LLM to externalize its plan |
| **Exponential backoff + jitter** | OpenAI Codex CLI | Reliable retries | `base * 2^attempt * random(0.9, 1.1)` with error classification |
| **Navigation-first display** | Novel | Prevents product-page distraction | Content elements lose IDs during navigation phases |

Full research notes: [`AGENT_RESEARCH.md`](android-companion/AGENT_RESEARCH.md), [`RESEARCH_CODEX.md`](android-companion/RESEARCH_CODEX.md)

## Getting Started

### Prerequisites

- Android phone (minSdk 28 / Android 9+)
- An LLM API key from any supported provider:
  - [OpenAI](https://platform.openai.com/api-keys) (or sign in with ChatGPT OAuth — no API key needed)
  - [Anthropic](https://console.anthropic.com) (Claude)
  - [Google AI Studio](https://aistudio.google.com/apikey) (Gemini)
  - [DeepSeek](https://platform.deepseek.com)
  - Any OpenAI-compatible endpoint (Groq, Together, Ollama, etc.)

### Install

**Option A — Download APK:**
Download the latest build from [Releases](https://github.com/adityaeternal/resolve/releases).

**Option B — Build from source:**

```bash
git clone https://github.com/adityaeternal/resolve.git
cd resolve/android-companion
./gradlew assembleDebug
# Install: adb install app/build/outputs/apk/debug/app-debug.apk
```

### Setup

1. **Open Resolve** and choose your auth method:
   - **Sign in with ChatGPT** — OAuth flow, no API key needed
   - **Use your own API key** — enter provider, key, model, and optional endpoint
2. **Enable accessibility service** — the app walks you through it
   - On Android 13+ with sideloaded APKs: allow restricted settings first (the app guides you)
3. **Fill in your issue** — app name, order details, description, desired outcome, optional photos
4. **Tap Resolve It** and switch to the target app

The agent runs as a foreground service with a floating stop button. Tap it anytime to cancel.

## Supported Apps

The agent works with **any Android app** using generic navigation heuristics. These apps have optimized navigation profiles with known support paths and pitfall avoidance:

| App | Category | Profile |
|-----|----------|---------|
| Amazon | E-commerce | Full path |
| Uber | Ride-hailing | Full path |
| Flipkart | E-commerce (India) | Full path |
| Swiggy | Food delivery (India) | Full path |
| Zomato | Food delivery (India) | Full path |
| Dominos India | Food ordering (India) | Full path + pitfall avoidance |
| Ola | Ride-hailing (India) | Full path |

Adding a new app profile is one of the easiest ways to contribute — see [Contributing](#contributing).

## Safety

Resolve takes safety seriously. These layers are load-bearing and cannot be bypassed:

- **PII detection** — SSN, credit card numbers, and passwords are detected and blocked before reaching the LLM
- **Financial action gates** — refund/payment-related actions pause for explicit user approval
- **30-iteration hard limit** — the agent stops after 30 actions, period
- **App-scope restriction** — if the agent navigates to the wrong app, it auto-corrects
- **Element banning** — UI elements that led to wrong screens (wallet pages, product listings) are permanently blocked for the session
- **Floating stop button** — always visible overlay, one tap to stop

## LLM Providers

Resolve works with any LLM that supports tool calling via an OpenAI-compatible API:

| Provider | Auth Method | Models |
|----------|-------------|--------|
| **OpenAI** | API key or ChatGPT OAuth | GPT-4o, GPT-4o-mini, o1, o3-mini |
| **Anthropic** | API key | Claude Sonnet, Claude Haiku |
| **Google** | API key (via OpenAI-compatible endpoint) | Gemini 2.0 Flash, Gemini Pro |
| **DeepSeek** | API key | DeepSeek-V3, DeepSeek-R1 |
| **Custom** | API key + endpoint | Groq, Together, Ollama, any OpenAI-compatible API |

## Contributing

Contributions welcome. The easiest ways to help:

### Add app navigation profiles
Test Resolve with a new app, document the support flow path and common wrong turns, and add an entry to `AppNavigationKnowledge.kt`. This directly improves success rates for all users of that app.

### Improve chat phase handling
The agent navigates well but chat interactions can be improved — better chatbot menu detection, multi-turn conversation strategy, file upload handling.

### Add tests
Unit tests for `SafetyPolicy`, `AgentLoop` logic, and `LLMClient` parsing are valuable and easy to write.

### Development setup

```bash
git clone https://github.com/adityaeternal/resolve.git
cd resolve/android-companion
./gradlew assembleDebug
```

Requirements: Android SDK, Java 17, Kotlin. Test on a real device (Samsung recommended — they have unique Keystore behavior that affects many Android apps).

## Project Structure

```
resolve/
├── android-companion/              # The Android app
│   ├── app/src/main/
│   │   ├── java/com/cssupport/companion/
│   │   │   ├── AccessibilityEngine.kt     # Screen capture + action execution
│   │   │   ├── AgentLoop.kt               # Observe-think-act brain
│   │   │   ├── LLMClient.kt               # Multi-provider LLM + tool calling
│   │   │   ├── CompanionAgentService.kt    # Foreground service
│   │   │   ├── SafetyPolicy.kt            # PII filter + action gates
│   │   │   ├── AuthManager.kt             # Encrypted credential storage
│   │   │   ├── AppNavigationKnowledge.kt   # App support path profiles
│   │   │   ├── SupportAccessibilityService.kt  # AccessibilityService
│   │   │   ├── MainActivity.kt            # Issue form
│   │   │   ├── WelcomeActivity.kt         # Auth entry point
│   │   │   ├── OnboardingActivity.kt      # Permission setup
│   │   │   ├── MonitorActivity.kt         # Live activity feed
│   │   │   └── CompleteActivity.kt        # Resolution summary
│   │   └── res/                           # Layouts, strings, themes
│   ├── AGENT_RESEARCH.md                  # Research papers analysis
│   └── RESEARCH_CODEX.md                  # Codex CLI patterns analysis
├── CLAUDE.md                              # Development guide
└── LICENSE                                # MIT
```

## FAQ

**Does this work with any app?**
Yes. The AccessibilityService can interact with any Android app. Apps with navigation profiles work better because the agent knows the exact path to support. For unknown apps, it uses generic heuristics (look for "Help", "Support", "Contact Us", etc.).

**Is my API key safe?**
Your API key is stored in Android's EncryptedSharedPreferences (AES-256-GCM backed by the hardware Keystore) with a plain-text backup for Samsung Keystore reliability. It never leaves your device except in direct API calls to your chosen LLM provider. No telemetry, no analytics, no backend.

**How much does it cost per use?**
Each support flow uses roughly 15-25 LLM calls with ~500-1000 tokens each. At current OpenAI pricing, that's typically $0.01-0.05 per resolution. Your mileage varies based on model and provider.

**Why AccessibilityService instead of screen coordinates?**
AccessibilityService provides the semantic UI tree — it knows that element [3] is a "Submit" button, not just pixels at (450, 800). This makes the agent resolution-independent, language-aware, and far more reliable than coordinate-based approaches.

**Can I use this without an API key?**
Yes — use the "Sign in with ChatGPT" option, which uses OAuth with your existing OpenAI account. No API key needed.

## License

MIT — see [LICENSE](LICENSE).
