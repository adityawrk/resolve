# Mobile AI Agent Research: Gold-Standard Practices for Resolve

Deep research into the state-of-the-art in mobile AI agent automation, conducted February 2026.
Covers open-source projects, academic papers, benchmarks, and specific gaps in our implementation.

---

## Table of Contents

1. [Landscape of Top Mobile AI Agent Projects](#1-landscape-of-top-mobile-ai-agent-projects)
2. [What the Best Agents Do Differently (Technical Deep Dive)](#2-what-the-best-agents-do-differently)
3. [Specific Gaps in Our Implementation](#3-specific-gaps-in-our-implementation)
4. [Ranked Improvements by Impact](#4-ranked-improvements-by-impact)
5. [Code-Level Recommendations](#5-code-level-recommendations)
6. [Architecture Patterns to Adopt](#6-architecture-patterns-to-adopt)

---

## 1. Landscape of Top Mobile AI Agent Projects

### 1.1 DroidRun -- 91.4% on AndroidWorld (State-of-the-Art)

**Source:** https://droidrun.ai/benchmark/method/ | https://github.com/droidrun/droidrun

DroidRun is the leading open-source native mobile agent framework. It achieved 91.4% on
AndroidWorld (116 tasks across 20 apps) using Gemini 2.5 Pro.

**Key architectural innovation: Manager-Executor feedback loop.**

Unlike traditional plan-then-execute approaches, DroidRun replaces sequential planning with a
tight feedback loop:

1. **Manager** creates high-level subgoals (not a full plan).
2. **Executor** takes ONE concrete action toward the first subgoal.
3. **Manager** immediately reassesses based on the actual outcome (not the expected outcome).
4. Repeat -- the plan evolves dynamically with each step.

This is fundamentally different from our approach, where a single LLM both plans and executes
in the same call. DroidRun's Manager sees the full picture (history, current state, goals) while
the Executor focuses narrowly on "what one action do I take right now?"

**Other critical innovations:**
- **Screen stabilization:** After every action, wait 0.5s and compare screen states, repeating
  until stable or timeout. This ensures the UI has fully rendered before the next decision.
- **Differential state:** The most recent message contains current screenshot + accessibility tree;
  the previous message contains the prior accessibility tree. The LLM sees exactly what changed.
- **Text Task routing:** When the Manager identifies a text-intensive task, it routes to a
  specialized text agent that operates atomically (clear-then-replace) rather than character-by-character.
- **App knowledge system:** An agentic system auto-generates descriptions of what each app does,
  giving the Manager concrete knowledge of app capabilities without hardcoding.
- **Element indexing:** Actions use `click(index)` where each UI element is assigned a stable numeric
  index from the accessibility tree, eliminating text-matching ambiguity.
- **Executor output triple:** The Executor outputs (1) thought process, (2) chosen action, and (3)
  description of what it did -- so the Manager understands not just what happened but why.

### 1.2 Minitap/AGI-0 -- 97.4% to 100% on AndroidWorld

**Source:** https://arxiv.org/html/2602.07787v1

Minitap is the first system to achieve 100% accuracy on AndroidWorld. It uses SEVEN specialized
agents built on LangGraph:

| Agent | Role |
|-------|------|
| **Planner** | Decomposes tasks into ordered subgoals with unique IDs |
| **Orchestrator** | Tracks subgoal lifecycle (pending/in-progress/completed/failed), decides routing |
| **Contextor** | Retrieves current device state (screenshot + UI hierarchy + package + timestamp) |
| **Cortex** | Central multimodal analysis -- formulates actions or signals completion |
| **Executor** | Parses structured decisions, translates to tool invocations |
| **ExecutorToolNode** | Custom sequential tool execution (prevents cascading errors) |
| **Summarizer** | Manages message history to prevent context overflow |

**Ablation study results (contribution of each innovation):**

| Innovation | Impact |
|------------|--------|
| Multi-agent architecture | **+21 percentage points** |
| Post-validation | **+15 percentage points** |
| Sequential execution | **+12 percentage points** |
| Hybrid perception (a11y tree + screenshot) | **+11 percentage points** |
| Meta-cognitive reasoning (self-reflection) | **+9 percentage points** |

**Critical detail: Post-validation.** After text input, the system queries the device for an updated
UI hierarchy, extracts the actual value, and compares it against the intended value. This alone is
worth +15 points. Text input with keyboard management uses multiple fallback selectors:
resource ID then coordinates then text matching.

**Critical detail: Meta-cognitive reasoning.** The Orchestrator analyzes prior agent outputs for
"cycle detection" and "strategy evaluation" -- it can detect when the agent is going in circles
and trigger replanning. This maps directly to our oscillation detection but is much more sophisticated.

**Key failure mode breakdown:**
- Context management failures: 38% (solved by multi-agent decomposition with focused contexts)
- Execution reliability failures: 41% (solved by sequential execution + post-validation)
- Error recovery failures: 21% (solved by meta-cognitive reasoning)

### 1.3 AppAgent / AppAgent v2 / AppAgentX (Tencent)

**Source:** https://github.com/TencentQQGYLab/AppAgent | https://arxiv.org/abs/2408.11824

AppAgent pioneered the **exploration-deployment two-phase approach:**

**Phase 1 -- Exploration:** The agent explores an unfamiliar app, documenting what each UI element
does. This creates a structured knowledge base of element functionalities.

**Phase 2 -- Deployment:** When given a task, the agent uses RAG (Retrieval-Augmented Generation)
to retrieve relevant knowledge from the exploration phase, enabling efficient execution without
re-exploring.

**AppAgent v2 innovations:**
- Flexible action space with parser, text, and vision descriptions for each element
- RAG-powered retrieval from the knowledge base during deployment
- Adaptive to different apps without app-specific code

**AppAgentX (2025) evolution:**
- Identifies repetitive action sequences and creates high-level shortcuts ("macros")
- Memory mechanism records task execution history
- Significantly reduces steps and reasoning for common tasks

**Relevance to Resolve:** Our agent has zero prior knowledge about any app. It starts cold every
time. AppAgent's approach of building a knowledge base through exploration is directly applicable --
we could pre-explore target apps (Dominos, Amazon, etc.) and store navigation paths.

### 1.4 Mobile-Agent v2/v3 (Alibaba / Qwen Team)

**Source:** https://github.com/X-PLUG/MobileAgent | https://arxiv.org/abs/2508.15144

Mobile-Agent v3 uses six specialized modules:

1. **RAG Module** -- retrieves external world knowledge
2. **Manager Agent** -- subgoal planning and guidance
3. **Worker Agent** -- GUI operation execution
4. **Reflector Agent** -- evaluation and feedback on actions
5. **Notetaker Agent** -- records important observations for later reference
6. **GUI Device Interface** -- platform abstraction (phone + PC)

**Core model: GUI-Owl** -- a native multimodal agent model built on Qwen2.5-VL, post-trained on
large-scale GUI interaction data. Unifies perception, grounding, reasoning, planning, and action
execution in a single network.

Achieves 73.3% on AndroidWorld (open-source SOTA before DroidRun).

**Key insight: The Reflector and Notetaker roles.** Our agent has no explicit reflection step and no
note-taking. The Reflector evaluates whether each action achieved its intended effect. The Notetaker
records facts discovered during navigation (e.g., "the Help button is at the bottom of the order
detail page") that are reused in later turns.

### 1.5 OS-Copilot / FRIDAY / UFO (Microsoft)

**Source:** https://os-copilot.github.io/ | https://github.com/microsoft/UFO

OS-Copilot/FRIDAY architecture for desktop agents:
1. **Planner** -- decomposes user request into subtasks
2. **Configurator** -- maintains working memory, retrieves tools and knowledge
3. **Actor with Self-Reflection** -- iteratively executes and self-criticizes until subtask is done

UFO uses a **dual-agent framework:**
- **AppAgent** -- understands and controls individual applications
- **HostAgent** -- orchestrates across multiple applications

UFO3 (latest) introduces Galaxy, a multi-device orchestration framework using DAG-based task
decomposition with continuous result-driven graph evolution.

### 1.6 PhoneClaw / OpenClaw

**Source:** https://github.com/rohanarun/phoneclaw

PhoneClaw is an Android automation app inspired by Claude Code, using:
- **ClawScript** -- JavaScript-based scripting language for runtime automation
- **magicClicker(description)** -- vision-assisted element clicking by natural language description
- **magicScraper(description)** -- vision-assisted information extraction
- Built-in scheduling (cron expressions) for recurring tasks

Uses DroidRun under the hood for the actual mobile agent framework (it is a skill/integration on
the OpenClaw platform).

### 1.7 OpenAdapt

**Source:** https://github.com/OpenAdaptAI/OpenAdapt

Generative RPA framework focused on learning from human demonstrations. Architecture:
- Record GUI demonstrations
- Train ML models on the recorded interactions
- Evaluate and deploy agents
- Privacy module for PII/PHI scrubbing

Less relevant to our use case (we need autonomous navigation, not demo replay), but the
demonstration-recording concept could inform our knowledge base.

---

## 2. What the Best Agents Do Differently

### 2.1 Screen State Representation: The Single Biggest Differentiator

**Finding: The top agents ALL use hybrid perception (accessibility tree + screenshots).**

| Agent | A11y Tree | Screenshots | Notes |
|-------|-----------|-------------|-------|
| DroidRun (91.4%) | Yes | Yes | Both sent each turn; differential a11y tree |
| Minitap (100%) | Yes | Yes | Hybrid perception worth +11 points |
| Mobile-Agent v3 (73.3%) | Yes | Yes | GUI-Owl vision model for grounding |
| AppAgent | No | Yes | Vision-only with GPT-4V/Qwen-VL |
| **Resolve (ours)** | **Yes** | **No** | **Text-only a11y tree** |

**Our agent operates blind.** It sees a text representation of UI elements but never sees the
actual screen. This is a massive disadvantage because:

1. **Icon-only buttons** -- Many buttons have no text or content description. A screenshot would
   show an obvious camera icon, shopping cart, or profile avatar. Our agent sees nothing.
2. **Layout understanding** -- Our zone-based approach (top/content/bottom) is crude. A screenshot
   lets the LLM understand spatial relationships naturally.
3. **Dynamic content** -- Loading spinners, progress bars, animations, and toast messages are
   invisible to our accessibility tree parser.
4. **Visual context** -- The LLM can't distinguish a product page from a help page if the text
   elements are similar. A screenshot makes it instantly obvious.
5. **Error states** -- Red error borders, disabled-looking buttons, selected states -- all visual
   information our agent misses.

**Recommendation: Add screenshot capture using the AccessibilityService takeScreenshot API
(available since API 30/Android 11, we require API 28+). Send both the a11y tree text AND the
screenshot to the LLM each turn. Use a multimodal model (GPT-4o, Gemini 2.5 Pro, Claude Sonnet)
that can process both.**

### 2.2 Element Grounding: Index-Based vs. Text-Based

**Finding: Top agents use numeric element indexing, not text matching.**

DroidRun's action space: `click(index)`, `type(text, index)`, `long_press(index)`

Each element in the accessibility tree gets a unique numeric index. The LLM sees:
```
[3] Button: "Account" (right)
[7] TextView: "Orders"
[12] Button: "Help with this order"
```

And responds with `click(12)` instead of `click_button("Help with this order")`.

**Why this is better:**
- **No ambiguity.** If there are two buttons labeled "Help", index disambiguation is trivial.
- **No text matching bugs.** Our `clickByText` does a substring search which can match the wrong
  element (e.g., clicking "Account Settings" when the agent meant "Account").
- **Content-description-less elements work.** An icon button without text gets an index and can
  still be clicked.
- **Faster, more reliable.** No need for fuzzy matching or fallback strategies.

**Our current approach:** The LLM says `click_button("Help")` and we search the tree for text
matches. This fails when:
- Multiple elements contain "Help"
- The element has slightly different text (e.g., "HELP" vs "Help")
- The element is an icon with no text
- The element is inside a container and the text is on a child node

### 2.3 Manager-Executor Split: Planning vs. Execution

**Finding: The top 3 agents (DroidRun, Minitap, Mobile-Agent v3) ALL separate planning from execution.**

Our agent uses a single LLM call that must simultaneously:
1. Understand the screen
2. Remember what it has done
3. Plan what to do
4. Choose one specific action

This is asking the LLM to do too much in a single inference. The research shows that splitting
these responsibilities dramatically improves performance:

- **+21 percentage points** from multi-agent architecture (Minitap ablation)
- DroidRun's tight Manager-Executor loop is the core innovation behind 91.4%

The Manager/Planner handles strategic reasoning: "I need to get to customer support. The fastest
path is Account > Orders > [order] > Help." The Executor handles tactical reasoning: "I see a
button labeled Account in the top-right. I should click it."

### 2.4 Post-Action Verification

**Finding: The best agents verify that actions succeeded before moving on.**

Minitap's post-validation is worth **+15 percentage points.** After typing text:
1. Query the device for an updated UI hierarchy
2. Extract the actual value in the text field
3. Compare against intended value
4. Retry with fallback selectors if mismatch

Our agent does NONE of this. After executing an action, we:
1. Wait for content change (but with a basic boolean flag)
2. Capture the next screen state
3. Hope the LLM notices if something went wrong

This means if a click fails silently (common -- the element may have been obscured, the tree may
have been stale), the agent proceeds as if it succeeded and gets increasingly confused.

**VeriSafe Agent (2025 research)** takes this further with pre-action verification: before executing
an irreversible action, verify the anticipated state transition against logical rules. This is
especially important for our use case where typing in a support chat is essentially irreversible.

### 2.5 Screen Stabilization

**Finding: DroidRun waits 0.5s after every action, then polls screen state until stable or timeout.**

Our implementation: We call `engine.waitForContentChange(timeoutMs = 3000)` which uses a simple
`contentChanged` boolean flag. This has several problems:

1. The flag is set by ANY content change event, including animation frames, loading spinners,
   and keyboard appearance/disappearance.
2. We don't verify the screen is actually stable -- it might still be transitioning.
3. We don't differentiate between "screen changed because of our action" and "screen changed
   because of unrelated background activity."

DroidRun's approach: Wait 0.5s, take a snapshot, wait 0.5s, take another snapshot, compare.
If they differ, keep waiting. If they match, the screen is stable. Timeout after ~5 seconds.

### 2.6 Context Management for Long-Horizon Tasks

**Finding: Observation masking outperforms LLM summarization for context management.**

JetBrains Research (December 2025, https://blog.jetbrains.com/research/2025/12/efficient-context-management/):
- Keeping a window of the **last 10 turns** with full fidelity is optimal
- Observation masking (replacing old observations with placeholders) beats LLM-based summarization
- Summarization causes 13-15% trajectory elongation (agent runs longer but doesn't do better)
- Both approaches cut costs >50% vs. unmanaged context

Our implementation compresses history to keep the last 8 turns, but we summarize old turns
(including LLM reasoning snippets) into a compact text block. Research suggests we should instead
simply drop the full screen state from old turns while keeping action/result pairs intact.

**Minitap's approach:** A dedicated Summarizer agent manages message history. It decides what to
keep and what to compress, rather than using fixed heuristics.

**AgentProg (2025 research):** Program-guided context management -- the agent writes a "program"
(pseudocode script) of its plan, then uses the program structure to decide which context is
relevant at each step.

### 2.7 Knowledge Bases and Prior Experience

**Finding: Cold-start navigation is the fundamental bottleneck. Top agents pre-build knowledge.**

AppAgent's exploration-deployment pattern is directly relevant to Resolve:

1. **Pre-exploration phase:** Before deployment, run the agent through each target app (Dominos,
   Amazon, etc.) and document the navigation path to customer support.
2. **Knowledge base:** Store these paths as structured documents:
   ```
   App: Dominos
   Path to support: Home > Account (top-right avatar) > Orders > [order] > Help > Chat
   Key screens: OrderListActivity, OrderDetailActivity, HelpActivity
   Common obstacles: promotional popup on launch (dismiss with back), location permission dialog
   ```
3. **RAG retrieval:** When the agent starts, retrieve the relevant app knowledge and include it
   in the system prompt.

Our agent starts blind every single time. Even though we hardcode some app-specific patterns in
the system prompt ("Food delivery: Profile/Account > Orders > Help"), this is crude and doesn't
capture the actual UI structure of each app.

### 2.8 Failure Recovery and Stuck Detection

**Finding: Top agents use explicit reflection, not just duplicate detection.**

Our stuck detection:
- Consecutive duplicate actions (3 max)
- Screen oscillation (A-B-A-B pattern)
- Stagnation (3+ unchanged screens)
- Scroll budget (8 max)

This is heuristic-based and reactive. The research shows better approaches:

**Mobile-Agent v3's Reflector Agent:** After every action, a dedicated agent evaluates:
1. Did the action achieve its intended effect?
2. Is the agent making progress toward the subgoal?
3. Should the plan be revised?

**Minitap's meta-cognitive reasoning (+9 points):** Analyzes prior agent outputs for cycle detection
and strategy evaluation. Accumulates evidence to prevent redundant work.

**Agent-R (2025 research):** Trains agents on "revision trajectories" -- they learn from examples
where an error was detected, a critique was generated, and a correction was applied. This teaches
the model to recognize its own failure patterns.

### 2.9 Action Space Design

**Finding: Top agents have 10-15 actions with clear semantic boundaries.**

| Agent | Actions | Notable extras |
|-------|---------|---------------|
| DroidRun | click, long_press, type, swipe, system_button, wait, open_app, get_state, take_screenshot, remember, complete | `remember(info)` for note-taking; `open_app` for app switching |
| Minitap | tap, scroll, swipe, text input, package launch, video record, save_note, read_note, list_notes | Scratchpad operations |
| **Resolve** | type_message, click_button, scroll_down, scroll_up, wait, upload_file, press_back, request_human_review, mark_resolved | **Missing: long_press, swipe, open_app, coordinate_tap, note-taking** |

**Missing actions in Resolve:**
1. **long_press** -- Some apps require long-press for context menus
2. **swipe with direction/coordinates** -- Our scroll is limited to scrollable containers; many
   apps need swipe gestures on specific elements (e.g., swipe to delete, pull-to-refresh)
3. **open_app** -- If the target app crashes or the agent gets stuck in the wrong app, it cannot
   recover by launching the correct app
4. **tap_at_coordinates** -- Fallback for elements that can't be found by text/index
5. **remember/note-taking** -- Record observations for later use (e.g., "the order ID is visible
   on this screen" or "I need to scroll down to find the Help button")
6. **get_current_state** -- Explicitly request a fresh screen capture without taking an action

---

## 3. Specific Gaps in Our Implementation

### 3.1 AccessibilityEngine.kt Gaps

**File:** `/Users/aditya/projects/cs-support/android-companion/app/src/main/java/com/cssupport/companion/AccessibilityEngine.kt`

| Gap | Severity | Description |
|-----|----------|-------------|
| No screenshot capture | CRITICAL | The engine only captures the a11y tree, never takes screenshots. The LLM operates blind. |
| No element indexing | HIGH | Elements are matched by text substring, which is fragile and ambiguous. |
| No screen stability check | HIGH | `waitForContentChange` uses a single boolean flag; doesn't verify screen is actually stable. |
| No hierarchy preservation | MEDIUM | `parseNodeTree` flattens the tree. Parent-child relationships are lost. The LLM can't tell which button belongs to which list item. |
| `findNodeByText` is fragile | HIGH | Uses `findAccessibilityNodeInfosByText()` which is a platform substring search. "Account" matches "Account Settings", "My Account", and "Account Balance". |
| No coordinate-based tap exposure | MEDIUM | `tapAt` exists but is only used as a fallback inside `clickNode`. It's not exposed as a top-level action for the LLM. |
| No long-press action | MEDIUM | No `longPress(node)` method exists. Some apps require long-press for menus. |
| Stale root window handling is incomplete | MEDIUM | The fallback to `service.windows` works but doesn't account for system dialogs, overlays, or multi-window mode. |
| `scrollScreenForward` always uses the first scrollable | LOW | If there are multiple scrollable containers (e.g., a horizontal banner + vertical list), we always scroll the first one. |
| No text extraction from non-text nodes | LOW | Image nodes with embedded text (e.g., promotional banners) are invisible. |

### 3.2 AgentLoop.kt Gaps

**File:** `/Users/aditya/projects/cs-support/android-companion/app/src/main/java/com/cssupport/companion/AgentLoop.kt`

| Gap | Severity | Description |
|-----|----------|-------------|
| Single-agent architecture | CRITICAL | One LLM call handles planning, execution, and reflection simultaneously. Top agents split these into 2-7 specialized roles. |
| No post-action verification | CRITICAL | After an action executes, we don't verify it succeeded. The LLM may proceed on false assumptions. |
| `detectChanges` is trivially simple | HIGH | Returns only "NO_CHANGE", "FIRST_SCREEN", or "SCREEN_CHANGED" with zero detail about WHAT changed. |
| No app knowledge base | HIGH | Agent starts cold every time. No prior knowledge of app layouts or navigation paths. |
| Context compression uses summarization | MEDIUM | Research shows observation masking (dropping old screens but keeping action pairs) is better than summarization. |
| No note-taking / memory tool | MEDIUM | Agent can't record observations for later use. Every screen observation is ephemeral. |
| Navigation phase detection is too coarse | MEDIUM | Only 4 phases with keyword-based detection. Can't distinguish between "order list" and "order detail", or between "FAQ page" and "live chat launcher". |
| MAX_CONVERSATION_TURNS = 8 is too low | MEDIUM | Research suggests 10 turns is optimal. At 8, we may be throwing away context too aggressively. |
| MAX_ESTIMATED_TOKENS = 6000 is very low | MEDIUM | With multimodal input (screenshots), this would need to be much higher. Even text-only, 6000 tokens limits the agent's memory significantly. |
| System prompt is enormous but lacks specificity | MEDIUM | Our prompt has good general advice but no app-specific navigation paths. The hardcoded patterns are too vague ("Profile/Account -> Orders -> Help"). |
| No explicit reflection step | MEDIUM | The agent never asks "did my last action work?" -- it just observes the new screen and makes a new decision. |
| `trySendMessage` brute-forces button search | LOW | Searches a hardcoded list of labels. Should use the a11y tree's knowledge of which element is adjacent to the input field. |
| No recovery from wrong-app situations | LOW | If the agent ends up in the wrong app (e.g., via a deep link or intent), there's no mechanism to launch the correct app. |

### 3.3 LLMClient.kt Gaps

**File:** `/Users/aditya/projects/cs-support/android-companion/app/src/main/java/com/cssupport/companion/LLMClient.kt`

| Gap | Severity | Description |
|-----|----------|-------------|
| No multimodal support | CRITICAL | Cannot send screenshots to the LLM. Only text-based messages. |
| `tool_choice: "required"` forces an action every turn | HIGH | The LLM MUST call a tool even when the best action is to reflect or observe. This leads to unnecessary actions when the agent should be thinking. |
| No streaming support | LOW | Full response must complete before action executes. Streaming would reduce perceived latency. |
| `max_completion_tokens = 1024` may be too low | LOW | For complex reasoning (chain-of-thought), the LLM may need more tokens to think properly. |
| No retry on malformed tool calls | MEDIUM | If the LLM returns invalid JSON arguments, we fall back to `Wait`. We should retry the LLM call. |
| No model-specific optimizations | LOW | Different models have different strengths. We use the same prompt regardless of whether it's GPT-4o, Claude, or Gemini. |
| HttpURLConnection is barebones | LOW | No connection pooling, no automatic retry on transient errors, no request/response logging for debugging. |

---

## 4. Ranked Improvements by Impact

Based on the research, here are the improvements ranked by expected impact on task success rate:

### Tier 1: Transformative (Expected: +20-40 percentage points)

**1. Add screenshot capture and multimodal LLM input**
- Impact: +11 points (Minitap ablation) to +20+ points (estimated for our text-only baseline)
- Effort: Medium (API 30+ takeScreenshot, base64 encode, add to LLM messages)
- The single highest-impact change. Our agent operates blind. Every top agent uses vision.

**2. Implement Manager-Executor split**
- Impact: +21 points (Minitap ablation)
- Effort: High (architectural rewrite of AgentLoop)
- A Manager LLM plans the high-level approach; an Executor LLM takes individual actions. The
  Manager reassesses after every action.

**3. Add post-action verification**
- Impact: +15 points (Minitap ablation)
- Effort: Medium (capture screen state after action, compare against expectations)
- After every action: did the screen change? Did the expected element appear/disappear? For text
  input: is the text actually in the field?

### Tier 2: Major (Expected: +5-15 percentage points)

**4. Switch to index-based element grounding**
- Impact: +5-10 points estimated
- Effort: Medium (number elements in formatForLLM, parse index from LLM response)
- Eliminates text-matching ambiguity. Every element gets a unique number.

**5. Implement proper screen stabilization**
- Impact: +5-8 points estimated
- Effort: Low (poll-and-compare loop after each action)
- Wait, capture, wait, capture, compare. Repeat until stable or timeout.

**6. Build app-specific knowledge base**
- Impact: +5-10 points estimated
- Effort: Medium-High (pre-explore apps, store paths, RAG retrieval)
- Pre-document the navigation path to support for each target app. Include in system prompt.

**7. Add reflection/verification step**
- Impact: +9 points (Minitap ablation for meta-cognitive reasoning)
- Effort: Medium (add reflection prompt between action and next observation)
- After each action, ask: "Did this action achieve its intended effect? Am I making progress?"

### Tier 3: Significant (Expected: +2-5 percentage points)

**8. Improve context management (observation masking)**
- Impact: +2-4 points estimated
- Effort: Low (replace summarization with masked observations)
- Keep last 10 turns in full. For older turns, keep action+result but drop screen state.

**9. Add note-taking/remember tool**
- Impact: +2-3 points estimated
- Effort: Low (add `remember` tool, inject notes into each user message)
- Lets the agent record observations like "Help button is at bottom of order detail."

**10. Expand action space**
- Impact: +2-3 points estimated
- Effort: Low-Medium (add long_press, coordinate_tap, open_app, remember)
- Missing actions force the agent into workarounds or failures.

**11. Allow tool_choice: "auto" with a "think" tool**
- Impact: +1-3 points estimated
- Effort: Low (change tool_choice, add think tool definition)
- Let the LLM decide when to act vs. when to observe/reflect.

### Tier 4: Polish (Expected: +1-2 percentage points)

**12. Preserve hierarchy in element representation**
- Impact: +1-2 points estimated
- Effort: Medium (restructure parseNodeTree output)
- Show parent-child relationships so the LLM knows which button belongs to which card.

**13. Improve detectChanges to describe WHAT changed**
- Impact: +1-2 points estimated
- Effort: Low (diff old vs. new element lists)
- Instead of just "SCREEN_CHANGED", describe "new elements appeared: Chat input, Send button"

**14. Raise MAX_CONVERSATION_TURNS to 10-12**
- Impact: +1 point estimated
- Effort: Trivial (change constant)
- Research supports 10 turns as optimal window.

---

## 5. Code-Level Recommendations

### 5.1 Screenshot Capture (Tier 1)

Add to AccessibilityEngine.kt:

```kotlin
// Requires API 30+ (Android 11). Our minSdk is 28, so guard with version check.
// For API 28-29, fall back to MediaProjection or skip screenshots.
suspend fun takeScreenshot(): Bitmap? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

    val result = CompletableDeferred<Bitmap?>()
    service.takeScreenshot(
        Display.DEFAULT_DISPLAY,
        service.mainExecutor,
        object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                val bitmap = Bitmap.wrapHardwareBuffer(
                    screenshot.hardwareBuffer, screenshot.colorSpace
                )
                screenshot.hardwareBuffer.close()
                result.complete(bitmap)
            }
            override fun onFailure(errorCode: Int) {
                result.complete(null)
            }
        }
    )
    return withTimeoutOrNull(3000) { result.await() }
}

fun bitmapToBase64(bitmap: Bitmap, maxWidth: Int = 720): String {
    // Resize to save tokens (720p is sufficient for UI understanding)
    val scale = maxWidth.toFloat() / bitmap.width
    val resized = Bitmap.createScaledBitmap(
        bitmap,
        maxWidth,
        (bitmap.height * scale).toInt(),
        true
    )
    val stream = ByteArrayOutputStream()
    resized.compress(Bitmap.CompressFormat.JPEG, 60, stream) // JPEG at 60% quality
    return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
}
```

Add multimodal message support in LLMClient.kt:

```kotlin
// In callOpenAICompatible, change the user message construction:
if (screenshotBase64 != null) {
    put(JSONObject().apply {
        put("role", "user")
        put("content", JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", userMessage))
            put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", "data:image/jpeg;base64,$screenshotBase64")
                    put("detail", "low") // "low" = 85 tokens; "high" = many more
                })
            })
        })
    })
} else {
    put(JSONObject().put("role", "user").put("content", userMessage))
}
```

### 5.2 Element Indexing (Tier 2)

Modify the ScreenState.formatForLLM() method to assign numeric indices:

```kotlin
fun formatForLLM(): String {
    // ... existing filtering and deduplication ...

    // Assign stable indices to all interactive + meaningful elements
    var nextIndex = 0
    val indexedElements = deduped.map { el ->
        IndexedElement(index = nextIndex++, element = el)
    }

    // Format with indices:
    // [0] btn: "Account" (right)
    // [1] text: "Welcome back, John"
    // [2] btn: "Orders"
    // [3] scrollable area
    for (indexed in indexedElements) {
        val el = indexed.element
        val typeHint = when {
            el.isEditable -> "INPUT"
            el.isClickable && el.isEnabled -> "btn"
            el.isScrollable -> "scroll"
            else -> "text"
        }
        val label = el.text ?: el.contentDescription ?: "unlabeled"
        sb.appendLine("  [${indexed.index}] $typeHint: \"$label\"$posHint")
    }
}
```

Modify click_button tool to accept either a label or an index:

```kotlin
put(toolDef(
    name = "click_element",  // renamed from click_button
    description = "Click an element by its index number (shown in [brackets] in the screen state). " +
        "Always prefer using the index. Example: click_element(index=5)",
    properties = JSONObject()
        .put("index", JSONObject()
            .put("type", "integer")
            .put("description", "The element index number shown in [brackets]"))
        .put("label", JSONObject()
            .put("type", "string")
            .put("description", "Fallback: element label text if index is not available")),
    required = listOf("index"),
))
```

### 5.3 Screen Stabilization (Tier 2)

Replace the simple contentChanged flag with a proper stability check:

```kotlin
/**
 * Wait for the screen to stabilize after an action.
 * Takes snapshots every 500ms and compares fingerprints.
 * Returns when two consecutive snapshots match, or on timeout.
 */
suspend fun waitForScreenStability(timeoutMs: Long = 5000): ScreenState {
    delay(500) // Initial wait for action to take effect

    var previousFingerprint = ""
    var stableState: ScreenState? = null
    val deadline = System.currentTimeMillis() + timeoutMs

    while (System.currentTimeMillis() < deadline) {
        val state = captureScreenState()
        val fingerprint = state.fingerprint()

        if (fingerprint == previousFingerprint && previousFingerprint.isNotEmpty()) {
            // Two consecutive identical snapshots = screen is stable
            return state
        }

        previousFingerprint = fingerprint
        stableState = state
        delay(500)
    }

    // Timeout -- return last captured state
    return stableState ?: captureScreenState()
}
```

### 5.4 Post-Action Verification (Tier 1)

Add a verification step after each action in AgentLoop.executeAction():

```kotlin
private suspend fun executeAndVerify(action: AgentAction): ActionResult {
    val preState = engine.captureScreenState()
    val result = executeAction(action)

    if (result !is ActionResult.Success) return result

    // Wait for screen to stabilize
    val postState = engine.waitForScreenStability()

    // Verify the action had an effect
    val preFingerprint = preState.fingerprint()
    val postFingerprint = postState.fingerprint()

    if (preFingerprint == postFingerprint) {
        // Screen didn't change -- action may have failed silently
        return ActionResult.Failed(
            "Action appeared to succeed but screen did not change. " +
            "The element may not have been interactive, or a popup may be blocking."
        )
    }

    // For text input: verify the text was actually entered
    if (action is AgentAction.TypeMessage) {
        val inputFields = postState.elements.filter { it.isEditable }
        val textFound = inputFields.any {
            it.text?.contains(action.text.take(20)) == true
        }
        if (!textFound) {
            return ActionResult.Failed(
                "Text was not found in any input field after typing. " +
                "The input field may not have been focused."
            )
        }
    }

    return ActionResult.Success
}
```

### 5.5 Manager-Executor Split (Tier 1)

This is the most impactful architectural change. Pseudocode for the new loop:

```kotlin
class AgentLoop(...) {
    // Manager: high-level planning (uses a frontier model)
    private val managerClient: LLMClient = ...
    // Executor: single-action execution (can use a smaller/faster model)
    private val executorClient: LLMClient = ...

    private var currentPlan: List<Subgoal> = emptyList()

    suspend fun executeLoop(): AgentResult {
        // Initial planning
        currentPlan = planWithManager(initialContext)

        while (hasSubgoals() && iterationCount < MAX_ITERATIONS) {
            val subgoal = currentPlan.first { it.status == SubgoalStatus.PENDING }
            subgoal.status = SubgoalStatus.IN_PROGRESS

            // Executor: take ONE action toward the current subgoal
            val screenState = engine.waitForScreenStability()
            val screenshot = engine.takeScreenshot()
            val executorDecision = executorClient.decide(
                subgoal = subgoal.description,
                screenState = screenState.formatForLLM(),
                screenshot = screenshot,
            )

            // Execute and verify
            val result = executeAndVerify(executorDecision.action)

            // Manager: reassess after every action
            val managerVerdict = managerClient.assess(
                plan = currentPlan,
                lastAction = executorDecision,
                actionResult = result,
                screenState = screenState,
            )

            when (managerVerdict) {
                is ManagerVerdict.Continue -> { /* proceed to next executor step */ }
                is ManagerVerdict.SubgoalComplete -> {
                    subgoal.status = SubgoalStatus.COMPLETED
                }
                is ManagerVerdict.Replan -> {
                    currentPlan = managerVerdict.newPlan
                }
                is ManagerVerdict.Stuck -> {
                    // Try recovery strategy
                }
                is ManagerVerdict.Done -> {
                    return AgentResult.Resolved(managerVerdict.summary)
                }
            }
        }
    }
}
```

Note on cost: The Manager runs on every turn which doubles LLM calls. However, the Executor can
use a smaller/faster/cheaper model since it only needs to take one action. DroidRun uses this
pattern successfully -- the Manager cost is compensated by using a lightweight Executor model.

---

## 6. Architecture Patterns to Adopt

### 6.1 Recommended Target Architecture

Based on the research, the architecture that best fits Resolve's constraints (on-device, single
LLM provider, Android accessibility service) is a simplified DroidRun-inspired approach:

```
                        +-------------------+
                        |   Case Context    |
                        | (app, issue, etc) |
                        +--------+----------+
                                 |
                    +------------v-----------+
                    |      Manager LLM       |
                    |  (plans subgoals,      |
                    |   reassesses after     |
                    |   each action)         |
                    +------------+-----------+
                                 |
                    +------------v-----------+
                    |     Executor LLM       |
                    |  (takes ONE action     |
                    |   given subgoal +      |
                    |   current screen)      |
                    +------------+-----------+
                                 |
              +---------+--------+---------+---------+
              |         |        |         |         |
           Execute   Verify   Stabilize  Capture  Reflect
              |         |        |         |         |
              v         v        v         v         v
        +-----------+ +-----+ +------+ +------+ +------+
        | A11y      | |Post-| |Screen| |Screen| |Self- |
        | Engine    | |Valid | |Stab  | |Shot  | |Check |
        | (actions) | |Check | |Loop  | |API   | |Step  |
        +-----------+ +-----+ +------+ +------+ +------+
```

### 6.2 Simplified Version (Lower Cost)

If running two LLM calls per turn is too expensive, a single-LLM approach with explicit
reflection can capture most of the benefit:

```
Turn N:
1. Capture stable screen state (a11y tree + screenshot)
2. Build user message with:
   - Current screen (indexed elements + screenshot)
   - Previous screen (for differential context)
   - What changed since last turn
   - Current subgoal from plan
   - Action verification result from last turn
   - App knowledge (from RAG)
   - Notes from remember() calls
3. LLM decides: either take an action OR use think() to reflect
4. If action: execute, verify, stabilize
5. If think: record reflection, proceed to next observation
```

This is still a single-agent architecture but adds verification and reflection steps that are
missing from our current implementation.

### 6.3 App Knowledge Base Format

```json
{
  "app_id": "com.dominos.android",
  "app_name": "Dominos",
  "support_path": [
    {"screen": "Home", "action": "click", "target": "Account icon (top-right, person avatar)"},
    {"screen": "Account", "action": "click", "target": "Order History / Past Orders"},
    {"screen": "Order List", "action": "click", "target": "Most recent order"},
    {"screen": "Order Detail", "action": "click", "target": "Help / Report Issue"},
    {"screen": "Help", "action": "click", "target": "Chat with us / Contact Support"}
  ],
  "common_obstacles": [
    {"type": "popup", "trigger": "app_launch", "description": "Promotional popup", "action": "press_back or click X"},
    {"type": "dialog", "trigger": "app_launch", "description": "Location permission", "action": "click Allow or Deny"},
    {"type": "login_required", "trigger": "account_access", "description": "Must be logged in", "action": "request_human_review"}
  ],
  "support_type": "in-app_chat",
  "known_issues": [
    "Help button sometimes requires scrolling down on order detail page",
    "Chat widget takes 3-5 seconds to load after clicking Contact Support"
  ]
}
```

### 6.4 Implementation Priority Order

Given the constraints (single developer, Samsung test device, no emulator, cost-sensitive users
with their own API keys), here is the recommended implementation order:

**Phase 1 (Maximum impact, moderate effort):**
1. Screenshot capture + multimodal LLM (requires model that supports vision: GPT-4o, Claude, Gemini)
2. Element indexing in formatForLLM + index-based click action
3. Screen stabilization (poll-and-compare)
4. Post-action verification (did screen change? is text entered?)

**Phase 2 (High impact, higher effort):**
5. Manager-Executor split OR simplified single-agent with explicit reflection tool
6. App knowledge base (start with top 5 target apps)
7. Context management improvement (observation masking)

**Phase 3 (Polish):**
8. Note-taking/remember tool
9. Expanded action space (long_press, coordinate_tap, open_app)
10. Improved differential state description

---

## Research Sources

### Open-Source Projects
- DroidRun: https://droidrun.ai | https://github.com/droidrun/droidrun
- AppAgent: https://github.com/TencentQQGYLab/AppAgent
- Mobile-Agent: https://github.com/X-PLUG/MobileAgent
- PhoneClaw: https://github.com/rohanarun/phoneclaw
- OpenAdapt: https://github.com/OpenAdaptAI/OpenAdapt
- UFO: https://github.com/microsoft/UFO
- OS-Copilot: https://os-copilot.github.io/
- AndroidWorld: https://github.com/google-research/android_world
- CogAgent: https://github.com/zai-org/CogAgent
- Set-of-Mark: https://github.com/microsoft/SoM

### Key Papers and Benchmarks
- Minitap (100% on AndroidWorld): https://arxiv.org/abs/2602.07787
- DroidRun benchmark results: https://droidrun.ai/benchmark/method/
- AppAgent v2: https://arxiv.org/abs/2408.11824
- Mobile-Agent v3: https://arxiv.org/abs/2508.15144
- CogAgent (CVPR 2024): https://openaccess.thecvf.com/content/CVPR2024/papers/Hong_CogAgent_A_Visual_Language_Model_for_GUI_Agents_CVPR_2024_paper.pdf
- VeriSafe Agent: https://arxiv.org/abs/2503.18492
- JetBrains context management research: https://blog.jetbrains.com/research/2025/12/efficient-context-management/
- AndroidWorld benchmark (ICLR 2025): https://openreview.net/forum?id=il5yUQsrjC

### Benchmark Leaderboard (AndroidWorld, as of February 2026)
| Rank | Agent | Success Rate | Type |
|------|-------|-------------|------|
| 1 | Minitap | 100% | Multi-agent (7 agents) |
| 2 | AGI-0 | 97.4% | Multi-agent |
| 3 | DroidRun | 91.4% | Manager-Executor |
| 4 | AutoGLM-Mobile | 80.2% | End-to-end VLM |
| 5 | Human baseline | 80% | Reference |
| 6 | Mobile-Agent v3 | 73.3% | Multi-agent (open-source) |
| 7 | Claude 3.7 Sonnet (zero-shot) | ~65% | Single VLM |
