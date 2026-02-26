# Mobile AI Agent Research: OpenClaw, PhoneClaw, DroidRun, and the State of the Art

Research conducted 2026-02-27. Focused on actionable techniques for improving Resolve's Android companion agent.

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [OpenClaw](#openclaw)
3. [PhoneClaw](#phoneclaw)
4. [DroidRun (91.4% AndroidWorld)](#droidrun)
5. [DroidClaw](#droidclaw)
6. [AppAgent (Tencent)](#appagent-tencent)
7. [AutoDroid](#autodroid)
8. [Minitap/mobile-use (100% AndroidWorld)](#minitap--mobile-use)
9. [Surfer 2 (87.1% AndroidWorld)](#surfer-2)
10. [MobileUse (Hierarchical Reflection)](#mobileuse-hierarchical-reflection)
11. [UI Representation Optimization](#ui-representation-optimization)
12. [Stuck Detection and Error Recovery](#stuck-detection-and-error-recovery)
13. [Actionable Takeaways for Resolve](#actionable-takeaways-for-resolve)

---

## Executive Summary

The mobile AI agent space has matured rapidly. The AndroidWorld benchmark -- the standard for evaluating mobile agents -- has been saturated, with multiple systems exceeding 90% and one (Minitap) achieving 100%. Key patterns that separate effective agents from mediocre ones:

1. **Manager-executor separation** beats monolithic agent loops (DroidRun: +27 points over single-agent)
2. **Differential state tracking** (comparing previous vs current screen) outperforms absolute state descriptions
3. **Verified execution** with deterministic post-validation catches silent failures (Minitap: +15 points)
4. **Meta-cognitive reasoning** that detects action loops and forces strategy changes (+9 points)
5. **Compact, indexed UI representation** reduces tokens 49-56% without hurting accuracy
6. **Vision fallback** for when accessibility trees are empty (WebViews, Flutter, games)
7. **Multi-step skills** that compress common patterns into single compound actions

---

## OpenClaw

**Repo**: https://github.com/openclaw/openclaw
**Stars**: 60,000+ (as of early 2026)
**What it is**: A local-first personal AI assistant with multi-channel communication (WhatsApp, Telegram, Slack, etc.) and device automation capabilities.

### Architecture

OpenClaw is NOT primarily a mobile automation agent -- it is a general-purpose AI assistant that happens to have mobile capabilities through plugins. Core architecture:

- **Gateway** (ws://127.0.0.1:18789): WebSocket control plane managing sessions, presence, config, cron, and webhooks
- **Pi agent runtime**: Executes in RPC mode with tool and block streaming
- **Multi-channel inbox**: Routes messages across 10+ platforms
- **Companion apps**: macOS menu bar, iOS/Android nodes for device-local actions

### Prompt Structure

OpenClaw uses workspace-based prompt injection via markdown files:
- `AGENTS.md` -- agent definitions and behavioral rules
- `SOUL.md` -- personality and behavior guidance
- `TOOLS.md` -- tool documentation
- `~/.openclaw/workspace/skills/<skill>/SKILL.md` -- per-skill prompts

This is a clean pattern: each capability gets its own prompt file, loaded contextually.

### Mobile Automation (Mobilerun Skill)

OpenClaw's phone automation comes through the **Mobilerun skill**, which wraps DroidRun's framework:
- Uses cloud-hosted real Android devices via API
- Combines vision models (screenshot analysis) with accessibility tree parsing
- Actions: tapping, typing, screenshots, UI inspection
- No on-device execution -- everything goes through API to cloud phones

### Context Management

- **Session model**: Separate sessions per chat, with group isolation
- **Context compaction**: `/compact` command summarizes session history to manage window size
- **Session pruning**: Automatic lifecycle management prevents unbounded growth
- **Session-to-session coordination**: Tools like `sessions_list`, `sessions_send`, `sessions_history` for multi-agent routing

### Relevance to Resolve

OpenClaw's mobile automation is thin (delegates to DroidRun/Mobilerun). The interesting parts for us are:
- **Workspace-based prompt files** -- we could adopt a similar pattern for our system prompts, making them modular and editable
- **Context compaction** -- summarizing conversation history when the context window fills up, rather than truncating
- **Session lifecycle management** -- automatic cleanup prevents memory leaks in long-running agents

---

## PhoneClaw

**Repo**: https://github.com/rohanarun/phoneclaw (also https://github.com/phoneclaw/phoneclaw)
**Language**: Kotlin (Android native)
**What it is**: On-device Android automation using AccessibilityService + vision models. No root required. Sideloaded APK.

### Architecture

PhoneClaw is the closest architectural match to Resolve's Android companion. It runs entirely on-device as a sideloaded APK.

Core components:
- **ClawAccessibilityService.kt** -- core service for all native actions (taps, types, scrolls)
- **ClawScript engine** -- embedded JavaScript runtime for executing generated scripts
- **Moondream vision API** -- lightweight VLM (0.5B params) for screen understanding
- **React Native bridge** -- connects Kotlin accessibility layer to JS script engine

### Key Innovation: Vision-Assisted Targeting

Instead of coordinate-based or ID-based targeting, PhoneClaw uses two vision functions:

**magicClicker(description)**: Takes a screenshot, sends it to Moondream VLM with a plain-language description of the target element, gets back coordinates, then taps via AccessibilityService. This means the agent describes WHAT it wants to click ("the blue Submit button") rather than WHERE to click.

**magicScraper(question)**: Same vision approach but for reading. Asks a question about the screen ("what is the order status?") and returns a text answer. Used for conditional branching and validation.

### ClawScript Runtime Functions

```
speakText(text)         -- Text-to-speech confirmations
delay(ms)               -- Execution pausing
schedule(task, cron)    -- Recurring task registration
clearSchedule(taskId)   -- Cancel scheduled task
magicClicker(target)    -- Vision-based tap
magicScraper(question)  -- Vision-based screen reading
sendAgentEmail()        -- Notification handoffs
```

### How It Handles Multi-Step Navigation

The LLM generates a complete ClawScript at runtime based on the user's goal. For example, "open twitter and click the blue post button every hour" becomes a script with:
1. magicClicker("Twitter app icon")
2. delay(2000)
3. magicClicker("blue post button")
4. schedule(() => { ... }, "0 * * * *")

The key difference from Resolve: PhoneClaw generates the ENTIRE script upfront, then executes it. Resolve does observe-think-act per step. PhoneClaw's approach is faster for known workflows but brittle for dynamic UIs.

### Relevance to Resolve

**Adopt**:
- **Vision-based element targeting** as a fallback when accessibility tree IDs are unreliable. Our AccessibilityEngine already captures screenshots -- we could add a VLM call for ambiguous elements.
- **magicScraper pattern** for validating actions worked. After clicking "Submit", ask the VLM "was the form submitted successfully?" rather than trying to parse DOM changes.

**Avoid**:
- Generating complete scripts upfront. Our observe-think-act loop is better for customer support where app state is unpredictable.
- Moondream dependency. At 0.5B params it is lightweight but may not be accurate enough. Our existing LLM setup (GPT-4o/Claude) handles vision natively.

---

## DroidRun

**Repo**: https://github.com/droidrun/droidrun
**Score**: 91.4% on AndroidWorld benchmark (116 tasks)
**Language**: Python (controls device via ADB)
**Funding**: EUR 2.1M pre-seed (July 2025)

### The Manager-Executor Architecture (Key Innovation)

DroidRun replaced the traditional "plan then execute" pattern with a **dynamic manager-executor feedback loop**:

```
Manager creates high-level tasks
  -> Executor takes ONE concrete action toward first task
    -> Manager immediately reassesses based on what actually happened
      -> Executor takes next action (possibly toward revised plan)
        -> repeat
```

This is fundamentally different from both:
- Single-agent loops (our current approach): one LLM does everything
- Plan-execute: create full plan, execute blindly, hope it works

The tight feedback loop means the plan constantly adapts to reality. If clicking "Orders" opened an unexpected dialog, the Manager sees that and adjusts.

### Differential State Tracking

This is one of their most impactful techniques:

> "The most recent user message contains the current screenshot and accessibility tree, while the previous message contains the prior accessibility tree, allowing the agent to observe exactly what changed."

Instead of asking the LLM "what do you see on screen?", they ask "what CHANGED since last step?". This is dramatically more efficient for the LLM to reason about.

### UI Element Representation

DroidRun formats accessibility nodes as indexed, hierarchical text:

```
1. Button: "com.app:id/submit", "Submit" - (100,200,300,250)
2. TextView: "label" - (110,210,290,240)
3.   EditText: "com.app:id/email", "Enter email" - (120,300,400,340)
```

Key design choices:
- Numeric indices for actions (click(3) not click("com.app:id/email"))
- Bounds included for spatial reasoning
- Indentation shows parent-child hierarchy
- `ConciseFilter` (when using vision) vs `DetailedFilter` (text-only) to manage token count
- Caches filtered trees to avoid re-processing per step

### Action Space

```
click(index)           -- Tap element by accessibility tree index
long_press(index)      -- Long press element
type(text, index)      -- Type text into focused element
swipe(coord1, coord2)  -- Directional gesture
open_app(text)         -- Launch app by name
copy / paste           -- Clipboard operations (with optional clear)
```

### Specialized Text Manipulation Agent

When the Manager detects a text-heavy task, it routes to a **dedicated text agent** with:
- Python shell access
- A function that clears and replaces text atomically
- Accessibility tree (no screenshots) plus the current focused element's text
- Full context of the goal

This is brilliant -- text manipulation in mobile UIs is notoriously flaky (cursor positioning, autocorrect, etc.). Routing to a specialized agent with programmatic text access eliminates entire categories of failure.

### Prompt Engineering Approach

DroidRun **distributes instructions throughout the system prompt** rather than concentrating them in one section:

> "Memory consistently appearing in both system prompts and user messages."

They also inject:
- Current device date (for temporal reasoning like "schedule for tomorrow")
- Screen stabilization state (0.5s wait, compare states until stable)
- Pointer location disabled (reduces visual noise in screenshots)
- App capabilities extracted automatically

### Error Handling

- Executor outputs three components per action: **thought process**, **chosen action**, and **description**
- All three are injected into the Manager's context
- Failed actions trigger immediate re-planning with `force_planning=True`
- The Manager decides between continuation and goal reformulation

### Per-Role Model Selection

- **Manager**: Uses powerful models (GPT-4o) for complex planning
- **Executor**: Can use faster/cheaper models (GPT-4o-mini) for simple action selection
- This reduces cost by 30-40% without degrading accuracy

### Relevance to Resolve

**High-priority adoptions**:
1. **Differential state tracking** -- send previous + current accessibility tree so the LLM sees what changed. This is a simple change to our AgentLoop's context assembly.
2. **Manager-executor split** -- even a lightweight version where AgentLoop generates a high-level plan and then executes one step at a time with re-evaluation would improve our success rate.
3. **Indexed element references** -- number our accessibility nodes so the LLM says "click(3)" instead of trying to describe elements by text content.
4. **Screen stabilization** -- wait until screen stops changing before capturing state. Our current 2-second MIN_ACTION_INTERVAL is close but not adaptive.

**Medium-priority**:
5. **Specialized text agent** -- route text-heavy tasks to different prompting that avoids screenshot overhead
6. **Per-role model selection** -- use a cheaper model for simple clicks, expensive one for planning

---

## DroidClaw

**Repo**: https://github.com/unitedbyai/droidclaw
**Language**: TypeScript (runs on host, controls phone via ADB)
**License**: MIT

### Architecture: Perception-Reasoning-Action

DroidClaw has the cleanest architecture documentation of any project reviewed:

```
1. PERCEIVE: Dump accessibility tree via ADB, parse XML into interactive
   elements with coordinates and state, detect screen changes via diffing
2. REASON: Send screen state + goal + history to LLM, get back
   think/plan/action structured response
3. ACT: Execute via ADB, feed result back on next step
```

### 28 Actions (Most Comprehensive)

**Basic**: tap, type, enter, longpress, clear, paste, swipe, scroll
**Navigation**: home, back, launch, switch_app, open_url, open_settings, notifications
**Clipboard**: clipboard_get, clipboard_set
**Multi-step skills**: read_screen, submit_message, copy_visible_text, wait_for_content, find_and_tap, compose_email
**System**: screenshot, shell, keyevent, pull_file, push_file, wait, done

The **multi-step skills** are particularly interesting. `read_screen` auto-scrolls and collects all visible text. `compose_email` fills To/Subject/Body using Android intents. `find_and_tap` scrolls to find an element then taps it. These compress 5-10 atomic actions into one call, reducing LLM turns and error surface.

### Stuck Recovery (Six Mechanisms)

1. **Stuck loop detection**: Screen unchanged for 3 steps -> inject context-aware recovery hints into prompt
2. **Repetition tracking**: Same coordinates tapped 3+ times -> nudge agent to try alternatives
3. **Drift detection**: Navigation spam (swipe/back/wait without interaction) -> encourage direct action
4. **Vision fallback**: Empty accessibility tree (WebViews, Flutter) -> screenshot to LLM with coordinate suggestions
5. **Action feedback**: Every result (success/failure + message) fed back on next step
6. **Multi-turn memory**: Conversation history across steps for contextual reasoning

### Structured LLM Output

```json
{
  "think": "The settings page is showing. I need to find the notification toggle.",
  "plan": "Scroll down to find notification settings, then tap the toggle.",
  "action": "scroll",
  "args": { "direction": "down" }
}
```

The three-field output (think/plan/action) gives the LLM structured space to reason before acting. This is better than our current approach of having the LLM output just a tool call.

### Configuration Knobs

```
MAX_STEPS = 30            -- Hard limit before timeout
STEP_DELAY = 2            -- Seconds between actions
STUCK_THRESHOLD = 3       -- Unchanged steps before recovery
VISION_MODE = fallback    -- off / fallback / always
MAX_ELEMENTS = 40         -- UI elements sent per step
MAX_HISTORY_STEPS = 10    -- Past steps retained in context
```

### Three Execution Modes

1. **Interactive**: LLM figures out navigation per step (our current approach)
2. **Workflows** (JSON): Chain goals across apps, LLM handles UI variations, formData injects specific values
3. **Flows** (YAML): Fixed tap/type sequences, no LLM, instant execution for known paths

### Relevance to Resolve

**High-priority adoptions**:
1. **Multi-step skills** -- create compound actions like `findAndTap(description)`, `readEntireScreen()`, `submitMessage(text)` that compress common patterns
2. **Six-mechanism stuck recovery** -- our agent has nothing like this. At minimum, implement stuck loop detection (screen unchanged for N steps) and repetition tracking.
3. **Structured think/plan/action output** -- modify our LLM prompt to require reasoning before action selection
4. **MAX_ELEMENTS cap** -- limit accessibility nodes sent to LLM (DroidClaw uses 40, ranked by relevance)

**Medium-priority**:
5. **Vision fallback mode** -- when accessibility tree is empty, fall back to screenshot analysis
6. **Workflow mode** -- for known customer support flows, allow pre-defined workflow templates

---

## AppAgent (Tencent)

**Repo**: https://github.com/TencentQQGYLab/AppAgent
**Paper**: CHI 2025
**What it is**: Multimodal agent that operates smartphone apps through simplified human-like interactions.

### Exploration-Deployment Pattern

The core innovation is a **two-phase approach**:

**Phase 1 -- Exploration**: The agent (or a human demonstrator) explores an app, documenting what each UI element does. For each element, it records:
- Element name/ID
- Visual appearance
- What happens when you tap/swipe/long-press it
- Which screen transitions it triggers

This creates a **knowledge base** of the app's UI.

**Phase 2 -- Deployment**: When executing a task, the agent uses **RAG** to retrieve relevant UI documentation from the knowledge base. It knows "this button labeled 'Orders' takes you to the order history screen" before it even taps it.

### Action Space (Simple and Effective)

```
Tap(element:int)                              -- Select UI element by number
Long_press(element:int)                       -- Sustained press
Swipe(element:int, direction:str, dist:str)   -- Scroll (up/down/left/right, short/medium/long)
Text(text:str)                                -- Direct text input
Back()                                        -- System back navigation
Exit()                                        -- Signal task completion
```

Only 6 actions. The simplicity is intentional -- fewer actions mean fewer ways for the LLM to go wrong.

### UI Element Labeling

Instead of parsing accessibility trees, AppAgent overlays **numbered labels** on screenshots. The LLM sees a screenshot where button 1 is "Home", button 2 is "Orders", etc. This makes element reference unambiguous.

### AppAgent v2 Improvements

v2 addressed v1's dependency on a working accessibility parser:
- **Flexible action space**: TapButton accepts both numeric IDs AND visual descriptions
- **Multi-modal element identification**: Android IDs + OCR + visual detection model
- **RAG knowledge base**: Stores Android ID, visible labels, text content, visual features per element
- **Safety mechanism**: Detects sensitive operations (passwords, payments) and switches to manual mode
- Eight actions: TapButton, Text, LongPress, Swipe, Back, Home, Wait, Stop

### Relevance to Resolve

**Adopt**:
1. **Exploration phase for known apps** -- we could pre-document common customer support apps (Amazon, airline apps, etc.) so the agent knows the UI before encountering it. Store as structured docs that get injected into the prompt.
2. **Numbered element overlays** -- simpler than accessibility tree indices, less ambiguous for the LLM
3. **Safety mode switching** -- auto-detect when the agent is near sensitive operations and pause for approval. We already have this in SafetyPolicy.kt but could make it more granular.

**Avoid**:
- The two-phase pattern requires pre-exploration per app. We operate across arbitrary apps, so we cannot pre-explore everything. However, we could maintain a growing knowledge base of frequently-encountered apps.

---

## AutoDroid

**Repo**: https://github.com/MobileLLM/AutoDroid
**Paper**: MobiCom 2024 (ACM)
**Score**: 90.9% action accuracy, 71.3% task success rate

### HTML-Style GUI Representation

AutoDroid's key insight: represent the Android UI as **HTML-like text** that LLMs already understand well:

> "AutoDroid executes tasks by prompting the LLMs with an HTML-style text representation of GUI and querying for action guidance."

Instead of custom XML or JSON, they convert the view hierarchy into something resembling HTML. LLMs have seen billions of HTML pages during training, so they reason about HTML-structured UIs more naturally.

### UI Transition Graphs

During an exploration phase, AutoDroid randomly walks through the target app and builds a **UI transition graph** -- a map of "from screen A, action X leads to screen B". This graph gets injected as memory, giving the LLM a map of the app before it starts.

### Memory Injection

The `/memory` directory contains app-specific knowledge that gets injected into prompts. This combines:
- Pre-explored UI transition graphs
- Previously successful action sequences
- Element function documentation

### Multi-Granularity Query Optimization

To reduce LLM costs, AutoDroid optimizes at three levels:
- **Visual**: Only send screenshots when needed
- **Structural**: Compress view hierarchy to relevant elements
- **Semantic**: Only query the LLM when the action is genuinely ambiguous

### Relevance to Resolve

**Adopt**:
1. **HTML-style UI representation** -- format our accessibility tree output as pseudo-HTML rather than custom text. LLMs reason about HTML better because of training data distribution.
2. **UI transition graphs** -- even a simple "if you're on the Help screen, 'Contact Us' leads to the chat screen" map would reduce failed navigation.

---

## Minitap / mobile-use (100% AndroidWorld)

**Repo**: https://github.com/minitap-ai/mobile-use
**Paper**: "Do Multi-Agents Dream of Electric Screens?" (Feb 2026)
**Score**: 100% on AndroidWorld (first system to achieve this)
**Framework**: LangGraph-based multi-agent system

### Why Single-Agent Fails

The paper identifies three failure modes of single-agent architectures:
1. **Context pollution** (38% of failures): Mixing planning traces with execution logs degrades reasoning
2. **Silent text input failures** (41% of failures): Agent thinks it typed text but nothing happened
3. **Repetitive action loops** (21% of failures): Agent tries failing actions repeatedly without escape

### Six-Agent Architecture

| Agent | Role | Model Requirement |
|-------|------|-------------------|
| **Planner** | Decomposes task into ordered subgoals with unique IDs | Budget OK |
| **Orchestrator** | Tracks subgoal lifecycle (pending/in-progress/completed/failed), routes execution | Budget OK |
| **Contextor** | Captures fresh device state (screenshots, accessibility tree, focused app, time) before each cycle | Budget OK |
| **Cortex** | Central decision-maker: analyzes UI hierarchy + screenshots, formulates actions, signals completion | MUST be frontier model |
| **Executor** | Parses Cortex decisions into tool invocations, executes sequentially, aborts on failure | Budget OK |
| **Summarizer** | Manages message history to prevent context overflow | Budget OK |

Key insight: **only the Cortex needs an expensive model**. All other agents tolerate budget models. This enables "Platform Default" configuration at 32% lower cost ($1.07 vs $1.58 per task).

Degrading the Cortex to a budget model collapsed success to 11%. Degrading other agents only dropped to 50-58%.

### Verified Execution (Most Important Technique)

For text input (the #1 failure mode):
1. Focus element with fallback chain: resource ID -> coordinates -> text matching
2. Position cursor to end of existing content
3. Execute input via platform automation
4. Capture fresh device state
5. Extract actual field value
6. Return explicit verification feedback to Cortex

**The principle: LLMs specify intent; deterministic code verifies outcomes.** This eliminates the agent assuming its action worked when it did not.

### Meta-Cognitive Reasoning

Before each decision, the Cortex runs a self-check:
- **Cycle detection**: Has this sequence of actions been seen before?
- **Strategy evaluation**: Has the current approach failed repeatedly?
- **Evidence accumulation**: What do completed actions tell us about task state?

This prevents the agent from retrying the same failing approach indefinitely.

### Parallel Execution Architecture

```
Contextor gathers state
  -> Cortex analyzes and decides
    -> PARALLEL: Orchestrator reviews subgoals | Executor dispatches actions
      -> Summarizer manages context
        -> Convergence synchronizes
          -> Orchestrator routes next step
```

The Cortex can simultaneously signal subgoal completion AND initiate next-step actions, saving a full round-trip.

### Ablation Results

| Technique | Impact on Success Rate |
|-----------|----------------------|
| Multi-agent decomposition | +21 points |
| Verified execution | +15 points |
| Sequential tool execution | +12 points |
| Meta-cognitive reasoning | +9 points |

### Performance

- Average task completion: **31 seconds** (vs 68 seconds for single-agent baselines)
- 100% success rate surpasses human performance (80%) by 20 points

### Relevance to Resolve

**Critical adoptions**:
1. **Verified execution for text input** -- after typing in a chat widget, READ BACK the actual field content and compare. This would catch our biggest failure mode.
2. **Meta-cognitive reasoning** -- before each action, check: "have I tried this before? did it work?" Simple history scanning that prevents loops.
3. **Subgoal decomposition** -- break "get a refund for order #123" into: (a) navigate to orders, (b) find order #123, (c) open help/contact, (d) request refund. Track each subgoal's status.

**Consider**:
4. **Cortex-only frontier model** -- use GPT-4o only for decision-making, cheaper model for planning/orchestration
5. **Sequential tool execution with abort-on-failure** -- if any action fails, stop immediately instead of continuing with stale state

---

## Surfer 2

**Score**: 87.1% on AndroidWorld (surpasses human baseline of 80%)
**Architecture**: Orchestrator, Navigator, Validator, Localizer

### Four-Component Architecture

- **Orchestrator**: High-level task planning and coordination
- **Navigator**: Step-by-step UI interaction decisions
- **Validator**: Self-verification of completed actions/subgoals
- **Localizer**: Precise spatial grounding of UI elements (uses Holo1.5 VLM)

### Key Finding: Spatial Grounding Matters

Ablation showed that swapping Holo1.5 7B for UI-TARS 7B in the Localizer dropped performance from 87.1% to 81.9%. **Accurate element localization is worth 5+ percentage points.** This suggests investing in reliable element targeting (coordinates, bounds, IDs) pays outsized dividends.

### Adaptive Recovery

Surfer 2 integrates "self-verification with adaptive recovery" -- it checks if each action achieved its intended effect and adjusts strategy dynamically. Similar to Minitap's verified execution but more tightly integrated into the navigation loop.

### Relevance to Resolve

- **Invest in element targeting accuracy** -- the 5-point improvement from better spatial grounding suggests our AccessibilityEngine's element identification is a high-leverage area to improve
- **Validator component** -- add a validation step after each action that confirms the screen changed as expected

---

## MobileUse (Hierarchical Reflection)

**Paper**: NeurIPS 2025
**Score**: 62.9% on AndroidWorld
**Architecture**: Three-level hierarchical reflection

### Three Reflection Levels

1. **Action Reflector** (micro): After each action, check if the immediate result matches expectations
2. **Trajectory Reflector** (meso): After N steps, check if the sequence of actions is making progress toward the goal
3. **Global Reflector** (macro): Periodically check if the overall task is on track or has been completed

### Reflection-on-Demand

Key insight: reflecting after EVERY action is wasteful and can even hurt performance. MobileUse uses a **confidence score** to decide when to reflect:
- High confidence: skip reflection, proceed to next action
- Low confidence: trigger appropriate level of reflection

This reduces overhead while catching problems early.

### Proactive Exploration

When facing unfamiliar UIs, the agent proactively explores to build knowledge rather than guessing. Similar to AppAgent's exploration phase but done on-demand during deployment.

### Relevance to Resolve

**Adopt**:
1. **Trajectory reflection** -- every 5-10 steps, prompt the LLM with "look at the last N actions. Are we making progress toward the goal?" This catches drift early.
2. **Confidence-gated reflection** -- only run expensive validation checks when the agent is uncertain. Skip them for obvious actions like clicking a clearly-labeled button.

---

## UI Representation Optimization

Research from "From User Interface to Agent Interface" (2025) and industry deployment at WeChat.

### Key Findings

1. **Token reduction of 49-56%** is achievable without hurting agent performance
2. **Mobile UIs need MORE complete representations** than web UIs (web has verbose DOMs that benefit from aggressive pruning)
3. **Only include interactive elements** -- decorative/invisible nodes waste tokens
4. **Include**: text labels, interaction affordances (tappable, editable, checkable), spatial relationships, accessibility labels
5. **Exclude**: style information, redundant container nodes, invisible elements

### Optimal Format for Mobile

Based on cross-referencing all projects studied:

```
Screen: com.app.support / OrdersActivity
[1] Button "Orders" (tappable) - bounds(0,120,360,180)
[2] TextView "Order #12345" - bounds(20,200,340,230)
[3] Button "Help" (tappable) - bounds(0,240,360,300)
  [3.1] ImageView "help_icon" - bounds(10,250,40,280)
  [3.2] TextView "Get Help" - bounds(50,250,200,280)
[4] EditText "Search orders..." (editable, focused) - bounds(20,60,340,100)
```

Design principles:
- Numeric indices (DroidRun, DroidClaw, AppAgent all converge on this)
- Bounds for spatial reasoning
- Affordance annotations (tappable, editable, checkable)
- Minimal indentation for hierarchy
- Cap at 30-40 elements per screen (DroidClaw default: 40)
- Filter by relevance/interactivity

### Token Impact

Assigning refs only to interactive elements (vs every element) and omitting non-essential attributes can reduce tokens by **51-79%** on real screens. For our use case with GPT-4o pricing, this translates to significant cost savings per case.

---

## Stuck Detection and Error Recovery

Synthesized from DroidClaw, Minitap, MobileUse, and general agent best practices.

### Three Stuck Patterns

1. **The Repeater**: Taking the same action repeatedly (tapping the same button that does not respond)
2. **The Wanderer**: Performing actions but making no progress toward the goal (scrolling randomly)
3. **The Looper**: Alternating between a small set of actions without resolution (back/forward cycle)

### Detection Mechanisms (Ranked by Impact)

| Mechanism | Detection Method | Recovery Action |
|-----------|-----------------|-----------------|
| **Screen unchanged** | Diff current vs previous accessibility tree | Inject "screen hasn't changed" hint, try alternative action |
| **Action repetition** | Track last N actions, detect repeats | Force alternative approach, escalate after 3 repeats |
| **Navigation drift** | Count swipe/back/wait without productive interaction | Prompt agent to take direct action |
| **Cycle detection** | Hash (screen_state, action) tuples, detect revisits | Force strategy change, try different navigation path |
| **Progress stall** | No subgoal completed in N steps | Trigger trajectory reflection, consider task failure |

### Recovery Strategies

1. **Hint injection**: Add to prompt "You've been on this screen for 3 steps without progress. Try a different approach."
2. **Action exclusion**: Temporarily remove the failing action from available options
3. **State reset**: Press Home, re-launch app, start from known state
4. **Strategy override**: Force the agent to describe its current strategy and suggest an alternative
5. **Human escalation**: After N failed recovery attempts, pause for human review

### Implementation Pattern

```kotlin
// Pseudocode for stuck detection in AgentLoop
data class StepRecord(val screenHash: Int, val action: String, val timestamp: Long)

class StuckDetector(
    private val stuckThreshold: Int = 3,
    private val maxRepeats: Int = 3,
    private val historySize: Int = 10
) {
    private val history = mutableListOf<StepRecord>()

    fun recordStep(screenHash: Int, action: String) {
        history.add(StepRecord(screenHash, action, System.currentTimeMillis()))
        if (history.size > historySize) history.removeFirst()
    }

    fun detectStuck(): StuckType? {
        if (history.size < stuckThreshold) return null

        // Screen unchanged
        val recentScreens = history.takeLast(stuckThreshold).map { it.screenHash }
        if (recentScreens.distinct().size == 1) return StuckType.SCREEN_UNCHANGED

        // Action repetition
        val recentActions = history.takeLast(maxRepeats).map { it.action }
        if (recentActions.distinct().size == 1) return StuckType.ACTION_REPEAT

        // Cycle detection (A->B->A->B)
        if (history.size >= 4) {
            val last4 = history.takeLast(4).map { it.screenHash to it.action }
            if (last4[0] == last4[2] && last4[1] == last4[3]) return StuckType.CYCLE
        }

        return null
    }
}

enum class StuckType { SCREEN_UNCHANGED, ACTION_REPEAT, CYCLE, NAVIGATION_DRIFT }
```

---

## Actionable Takeaways for Resolve

### Priority 1 -- Implement Now (Highest Impact, Moderate Effort)

#### 1.1 Differential State Tracking
**What**: Send both previous and current accessibility tree to the LLM so it can see what changed.
**Where**: `AgentLoop.kt` context assembly, `LLMClient.kt` message construction
**Impact**: DroidRun attributes significant accuracy improvement to this technique
**Effort**: Small -- add a `previousState` field, include in prompt

#### 1.2 Verified Text Execution
**What**: After typing text into a field, read back the actual field content and compare.
**Where**: `AccessibilityEngine.kt` typeText method, `AgentLoop.kt` action execution
**Impact**: Minitap found 41% of failures were silent text input failures. +15 points from verification.
**Effort**: Small -- AccessibilityService can read node text after typing

#### 1.3 Stuck Detection
**What**: Track screen hashes and action history, detect when agent is stuck, inject recovery hints.
**Where**: New `StuckDetector` class, integrate into `AgentLoop.kt`
**Impact**: DroidClaw's six mechanisms prevent most infinite loops. We currently rely only on the 30-iteration hard limit.
**Effort**: Medium -- new class, ~100 lines, integration into agent loop

#### 1.4 Indexed Element References
**What**: Number accessibility nodes and have the LLM reference them by index (click(3)) instead of by description.
**Where**: `AccessibilityEngine.kt` screen capture, `LLMClient.kt` tool definitions
**Impact**: Every top-performing agent uses this pattern. Eliminates ambiguity in element selection.
**Effort**: Small -- modify screen state serialization to include indices

### Priority 2 -- Implement Soon (High Impact, Higher Effort)

#### 2.1 Structured Thought Output
**What**: Require the LLM to output think/plan/action instead of just action. Parse the think/plan for logging.
**Where**: `LLMClient.kt` system prompt and response parsing
**Impact**: Better reasoning traces, easier debugging, catches more errors
**Effort**: Medium -- prompt engineering + response parsing changes

#### 2.2 Compound Actions (Multi-Step Skills)
**What**: Create high-level actions like `findAndTap(description)`, `readEntireScreen()`, `navigateToScreen(name)`.
**Where**: New methods in `AccessibilityEngine.kt`, new actions in `AgentAction` sealed class
**Impact**: Reduces LLM turns by 3-5x for common patterns, fewer chances for error
**Effort**: Medium -- new action types, engine methods, LLM tool definitions

#### 2.3 Element Cap and Relevance Filtering
**What**: Limit accessibility nodes sent to LLM to top 30-40, ranked by interactivity and relevance.
**Where**: `AccessibilityEngine.kt` screen capture
**Impact**: 49-56% token reduction, faster responses, lower cost
**Effort**: Medium -- filtering and ranking logic

#### 2.4 Subgoal Decomposition
**What**: Break the customer's request into numbered subgoals, track completion status.
**Where**: `AgentLoop.kt`, `LLMClient.kt` system prompt
**Impact**: Minitap showed +21 points from multi-agent decomposition. Even lightweight subgoal tracking helps.
**Effort**: Medium -- prompt engineering, state tracking in agent loop

### Priority 3 -- Consider Later (Valuable but Complex)

#### 3.1 Manager-Executor Split
**What**: Separate planning LLM call from execution LLM call with different models/prompts.
**Where**: Major refactor of `AgentLoop.kt` and `LLMClient.kt`
**Impact**: DroidRun's core innovation. But requires more LLM calls per step.
**Effort**: Large -- architectural change

#### 3.2 Vision Fallback Mode
**What**: When accessibility tree is empty/sparse, fall back to screenshot + VLM for element identification.
**Where**: `AccessibilityEngine.kt`, `LLMClient.kt`
**Impact**: Handles WebViews, Flutter, custom UI frameworks that lack accessibility data
**Effort**: Large -- need multimodal LLM support, coordinate extraction

#### 3.3 App Knowledge Base
**What**: Pre-document common customer support apps (navigation maps, element functions) and inject via RAG.
**Where**: New knowledge base system, prompt injection
**Impact**: AppAgent showed this dramatically improves task success in known apps
**Effort**: Large -- knowledge base construction, RAG integration, ongoing maintenance

#### 3.4 Trajectory Reflection
**What**: Every 5-10 steps, ask the LLM "are we making progress?" with full action history.
**Where**: `AgentLoop.kt` periodic reflection check
**Impact**: MobileUse showed this catches drift early
**Effort**: Medium -- extra LLM call every N steps, prompt engineering

---

## References

### Projects
- OpenClaw: https://github.com/openclaw/openclaw
- PhoneClaw: https://github.com/rohanarun/phoneclaw
- DroidRun: https://github.com/droidrun/droidrun
- DroidClaw: https://github.com/unitedbyai/droidclaw
- AppAgent: https://github.com/TencentQQGYLab/AppAgent
- AutoDroid: https://github.com/MobileLLM/AutoDroid
- Minitap/mobile-use: https://github.com/minitap-ai/mobile-use
- MobileUse: https://github.com/MadeAgents/mobile-use

### Technical Deep Dives
- DroidRun benchmark method: https://droidrun.ai/benchmark/method/
- DroidRun DroidAgent architecture: https://deepwiki.com/droidrun/droidrun/3-droidagent
- DroidRun LLM integration: https://deepwiki.com/droidrun/droidrun/7-llm-integration
- AppAgent v2 paper: https://arxiv.org/abs/2408.11824
- AppAgent v1 paper: https://arxiv.org/abs/2312.13771
- AutoDroid paper: https://arxiv.org/abs/2308.15272
- Minitap paper: https://arxiv.org/abs/2602.07787
- MobileUse paper: https://arxiv.org/abs/2507.16853
- Surfer 2 paper: https://arxiv.org/abs/2510.19949
- UI representation optimization: https://arxiv.org/abs/2512.13438
- MobileWorld benchmark: https://arxiv.org/abs/2512.19432

### Benchmarks
- AndroidWorld: https://www.emergentmind.com/topics/androidworld
- Mobile AI agents comparison: https://aimultiple.com/mobile-ai-agent
