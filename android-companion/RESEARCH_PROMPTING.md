# Research: Gold-Standard LLM Agent Prompting for UI Automation

**Date**: February 2026
**Scope**: Prompting strategies, context management, action space design, error recovery, and screen state representation for LLM-powered mobile UI agents.
**Goal**: Concrete, actionable improvements to the Resolve Android agent (`AgentLoop.kt`, `LLMClient.kt`, `AccessibilityEngine.kt`).

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Prompting Strategies for Tool-Calling Agents](#1-prompting-strategies)
3. [Context Management for Multi-Turn Agents](#2-context-management)
4. [Action Space Design](#3-action-space-design)
5. [Error Recovery and Planning](#4-error-recovery-and-planning)
6. [Screen State Representation](#5-screen-state-representation)
7. [Recommendations for Resolve](#6-recommendations-for-resolve)
8. [Implementation Priority](#7-implementation-priority)
9. [Sources](#sources)

---

## Executive Summary

The current Resolve agent has a solid foundation (multi-turn conversation, spatial screen zones, phase detection, stuck/oscillation detection) but falls short of state-of-the-art mobile agents on several critical dimensions. After surveying 30+ papers, open-source frameworks, and production systems, the most impactful changes are:

1. **Numbered element references** instead of label-based matching (eliminates ambiguity, the single biggest accuracy win)
2. **Hierarchical reflection** with action verification (catch errors within 1 turn instead of 3)
3. **Goal decomposition** into explicit sub-goals with progress tracking (reduces drift)
4. **Observation masking** for older turns (preserve KV-cache, reduce cost 10x)
5. **Structured reasoning prefix** forcing the LLM to think before acting (measurable accuracy gain)

These five changes collectively address every failure mode described in the problem statement: confusion navigating apps (1, 3), wasting iterations scrolling (1, 4), not reaching the target (3, 2).

---

## 1. Prompting Strategies

### 1.1 ReAct (Reason + Act) with Tool Calling

**What it is**: The ReAct pattern (Yao et al., 2022) interleaves explicit reasoning traces with actions. In a tool-calling context, the model produces a text "thought" before each tool call.

**What the research says**:
- ReAct remains the foundational logic for agentic AI in 2025-2026. It is not obsolete; it is abstracted into frameworks.
- Chain-of-thought (CoT) prompting substantially improves reasoning on navigation tasks. Even zero-shot "let's think step by step" helps, but structured CoT is better.
- For tool-calling specifically, OpenAI-compatible APIs support this via the `content` field on assistant messages alongside `tool_calls`. Anthropic supports it via interleaved `text` and `tool_use` content blocks.

**What actually works in practice**:

The current system prompt says "Think step by step" at the end of the user message, but does not enforce structured reasoning. The LLM may or may not include reasoning in its response. Production systems like Manus and Minitap force structured thinking.

**Recommendation**: Add a structured reasoning prefix to the system prompt that the LLM must follow before every tool call:

```
Before choosing your action, you MUST output your reasoning in this exact format:

OBSERVE: [What I see on screen - key elements, screen type, current state]
THINK: [What phase am I in? What has/hasn't worked? What should I try?]
PLAN: [My next 2-3 steps to reach the goal]
ACT: [The specific tool call I'm making and why]
```

This is not merely cosmetic. MobileUse (NeurIPS 2025) demonstrated that hierarchical reflection achieves 62.9% on AndroidWorld vs ~40% without structured reasoning. The reasoning trace also makes debugging vastly easier.

### 1.2 Few-Shot Examples in System Prompts

**What the research says**:
- LangChain's research found that "a few well-selected examples can be as effective (if not more effective) than many examples" for tool-calling.
- 3 examples perform comparably to 9, suggesting diminishing returns.
- Quality and relevance of examples matter more than quantity.
- OpenAI models see smaller effects from few-shotting than open-source models, but few-shot still helps for novel tasks.

**What actually works**:

Few-shot examples are expensive in tokens but highly effective for establishing the correct pattern of interaction. The key insight is to show the *decision pattern*, not just the action.

**Recommendation**: Add 2-3 canonical few-shot examples in the system prompt that demonstrate the reasoning format. These should cover:
1. A navigation decision (seeing a home screen, choosing to go to Account)
2. A chat interaction (seeing a support chat, composing a message)
3. An error recovery (action failed, choosing a different approach)

Keep examples short (100-150 tokens each) to limit token overhead. Use only when the model is a smaller model like GPT-5 Nano; larger models like GPT-5 may not need them.

### 1.3 Spatial Information and Position Awareness

**What the research says**:
- Set-of-Marks (SoM) prompting (Microsoft Research) overlays numbered labels on interactive elements, dramatically improving grounding accuracy in visual language models.
- Even for text-only UI representations, numbered references eliminate ambiguity. AppAgent v2 and DroidRun both use numeric element IDs.
- Position hints (left/right/center) help but are insufficient when multiple elements share the same label.

**Current gap in Resolve**: The `click_button` tool takes a `buttonLabel` string, which requires fuzzy text matching in `clickByText()`. This fails when:
- Multiple elements have the same or similar text
- Elements only have content descriptions
- Text is truncated differently than the LLM expects
- Dynamic content changes between observation and action

**Recommendation**: Switch to numbered element references. Assign each interactive element a numeric ID in the screen representation:

```
[CONTENT]
  [1] btn: "Help" (left)
  [2] btn: "Orders" (center)
  [3] btn: "Account" (right)
  [4] text: "Welcome back, John"
  [5] INPUT: "Search..."
```

The `click_button` tool should accept either a number or a label, with the number being preferred. This is the single highest-impact change for action accuracy.

---

## 2. Context Management

### 2.1 Full History vs Sliding Window vs Summarization

**What the research says**:

Three dominant strategies exist:

| Strategy | Pros | Cons |
|----------|------|------|
| Full history | Perfect recall, model sees everything | Hits context limit fast, attention dilution, cost |
| Sliding window | Fixed memory cost, recent context preserved | Loses early context, may repeat mistakes |
| Hybrid (summary + recent) | Best of both, used by production systems | Summary quality matters, compression is lossy |

**What production systems do**:
- **Manus** (production agent): Uses append-only context with observation masking. Older tool outputs are replaced with `[masked]` placeholders, preserving the action trace but dropping verbose screen dumps. This preserves KV-cache stability.
- **Claude Code**: Runs "auto-compact" at 95% context utilization, recursively summarizing the trajectory.
- **MobileUse**: Maintains a hierarchical three-tier reflection (action-level, trajectory-level, global-level) that effectively compresses context into actionable summaries.
- **JetBrains research** (December 2025): Found that "observation masking can serve as a first line of defense against context bloat, hiding old, noisy tool outputs with placeholders while keeping only the most relevant parts."

**Current Resolve approach**: `maybeCompressHistory()` compresses old turns into a summary when exceeding 8 turns or 6000 tokens. The summary is a flat list of `T1: tool_name(args) -> result`.

**Problems with current approach**:
1. Compression is all-or-nothing (entire old block summarized at once)
2. Summary format loses important navigation context (which screens were visited)
3. 6000 token limit is very conservative for GPT-5 class models
4. Compression invalidates KV-cache entirely (the system prompt + compressed history differs from the previous call)

**Recommendation**: Adopt Manus-style observation masking:

```
Strategy: Append-only context with progressive masking

Turn 1: [full observation]  -> tool_call -> result
Turn 2: [full observation]  -> tool_call -> result
Turn 3: [full observation]  -> tool_call -> result  <-- current turn
         ^                     ^            ^
         MASK old obs to:      keep         keep
         "[Screen: Amazon OrdersPage, 12 elements]"
```

Rules:
- Never modify or remove messages from history (append-only preserves KV-cache)
- Mask the `UserObservation` content of turns older than N (keep only a 1-line summary like `"[Screen: {package}/{activity}, {elementCount} elements, phase: {phase}]"`)
- Always keep the assistant's tool call and reasoning intact (the LLM needs to see what it did)
- Always keep tool results intact (the LLM needs to know what worked/failed)
- Keep the last 3-4 turns in full detail

This approach:
- Preserves the action trajectory (what was tried)
- Preserves results (what worked/failed)
- Drops verbose screen dumps that are no longer relevant
- Maintains append-only invariant for KV-cache stability

### 2.2 Token Budgeting

**What the research says**:
- Manus reports KV-cache hit rate as "the single most important metric" for production agents. Cached input tokens cost 10x less than uncached on Claude (0.30 vs 3.00 USD/MTok).
- Context window utilization should be monitored. For GPT-5 Nano with a 128K context window, staying under 8K tokens total is extremely conservative.

**Current Resolve budget**:
- System prompt: ~800 tokens
- Per-turn observation: ~300-500 tokens
- Per-turn tool call + result: ~100 tokens
- 8 turns x ~600 = ~4800 tokens of history
- Total: ~6000 tokens per LLM call

**Recommendation**: Increase the budget. With GPT-5 Nano's context window and the observation masking strategy, the agent can comfortably maintain 15-20 turns of history within 12K tokens. The system prompt is the most important context and should be expanded with few-shot examples and better guidance.

### 2.3 Manus's Dynamic todo.md Pattern

**What Manus discovered**: Over long agent runs (50+ tool calls), models suffer from "goal drift" --- they lose track of the original objective and get absorbed in local details. Manus combats this by maintaining a dynamic `todo.md` that is "recited" at the end of context, keeping objectives in the model's recent attention window.

**Application to Resolve**: The current user message includes the phase and goal at the end, which is good. But the progress tracking is implicit. Make it explicit:

```
## Progress Tracker
- [x] Opened target app (Swiggy)
- [x] Found Account/Profile section
- [ ] Navigate to Orders
- [ ] Find order #SW-12345
- [ ] Open Help for this order
- [ ] Reach support chat
- [ ] Describe issue and negotiate resolution
```

This progress tracker should be maintained by the agent loop (not the LLM) based on phase detection and key milestones, and injected at the end of every user message.

---

## 3. Action Space Design

### 3.1 Optimal Action Set for Mobile UI Agents

**What the research says**:

Multiple frameworks converge on a similar core action set:

| Action | DroidRun | AppAgent | UI-TARS | MobileUse | Resolve (current) |
|--------|----------|----------|---------|-----------|-------------------|
| Click/Tap | tap(x,y) | tap(element) | click(bbox) | click(element) | click_button(label) |
| Type | type(text) | type(text) | type(text) | type(text) | type_message(text) |
| Scroll | swipe(dir) | swipe(dir) | scroll(dir) | scroll(dir) | scroll_down/up |
| Wait | -- | -- | -- | wait | wait_for_response |
| Back | -- | press_back | press_back | press_back | press_back |
| Long press | long_press | long_press | long_press | long_press | -- |
| Open app | open_app | -- | -- | open_app | -- (handled externally) |
| Done/Finish | done | finish | done | -- | mark_resolved |
| Human handoff | -- | -- | -- | -- | request_human_review |

**Key observations**:
1. Most frameworks use coordinate-based or element-reference-based clicking, not label-based.
2. `long_press` is useful but rarely needed for customer support flows.
3. `open_app` can be useful for restarting from scratch when deeply lost.
4. `swipe` with directional parameters (up/down/left/right) is more flexible than separate scroll_up/scroll_down.

**Recommendation for Resolve's action space**:

Keep the current actions but make these changes:
- **`click_button`**: Rename to `click_element`. Accept `elementId` (number from screen representation) as primary parameter, `label` as fallback. Add `description` parameter for the LLM to explain what it expects to happen (aids error detection).
- **`type_message`**: Keep as-is, but add `elementId` parameter to specify which input field (when multiple exist).
- **Add `open_app`**: For recovery when the agent gets lost in the wrong app or needs to restart.
- **Consider `swipe`**: A single `swipe(direction)` tool combining scroll_up/scroll_down is cleaner but not critical.

### 3.2 Tool Description Quality

**What the research says**:
- TOOLVERIFIER (ACL 2025 findings) showed that optimized tool descriptions improve accuracy by 22% over baselines on ToolBench.
- Constraint information in tool descriptions significantly reduces misuse. Telling the model "do NOT use for X" is highly effective.
- Manus uses consistent prefixes for tool groups (e.g., `browser_*`, `shell_*`) to enable prefix-based filtering.

**Current Resolve tool descriptions**: Already quite good. The `type_message` description includes "ONLY use this when you are in a chat/support interface" and `scroll_down` includes "Do NOT scroll to browse." These constraint annotations are exactly right.

**Recommendation**: Add an `expectedOutcome` or `description` field to the action tools. This forces the LLM to articulate what it expects to happen, which:
1. Makes the reasoning explicit and debuggable
2. Enables action verification (did what I expected actually happen?)
3. Reduces speculative/random actions

### 3.3 Handling Ambiguous Element Matching

**Current problem**: `clickByText()` uses `findAccessibilityNodeInfosByText()` which is a fuzzy text search. When multiple nodes match:
- It prefers clickable nodes (good)
- But returns the first match, not necessarily the right one

**What production systems do**:
- AppAgent v2: Uses a structured knowledge base mapping elements to their functions
- DroidRun: Uses numeric element IDs with exact matching
- CORE (NeurIPS 2025): Groups elements into layout-aware blocks, selecting the most relevant block first

**Recommendation**: The numbered element reference system (Section 1.3) solves this entirely. Each element gets a stable numeric ID for the current turn. The LLM selects by number. No ambiguity.

---

## 4. Error Recovery and Planning

### 4.1 Error Detection

**What the research says**:
- **BacktrackAgent** (EMNLP 2025): Introduces verifier, judger, and reflector components. The key insight: "Existing agents primarily focus on enhancing the accuracy of individual actions and often lack effective mechanisms for detecting and recovering from errors." BacktrackAgent improved task success rate by 7.59% on Mobile3M simply by adding error detection + backtracking.
- **MobileUse**: Uses a three-tier hierarchical reflection:
  - **Action Reflector**: After each action, checks if the screen changed as expected
  - **Trajectory Reflector**: After several actions, checks if overall progress is being made
  - **Global Reflector**: Checks if the task is complete or impossible
- **Minitap** (100% on AndroidWorld): Uses "deterministic post-validation of text input against device state." After typing, it reads the field content back to verify the text was actually entered correctly.

**Current Resolve approach**: Detects stagnation (same screen 3+ turns) and oscillation (A-B-A-B pattern). These catch some errors but only after 3-4 wasted iterations.

**Recommendation**: Add action verification to every turn:

```kotlin
// After executing an action, before waiting for content change:
val postActionScreen = engine.captureScreenState()
val actionVerification = verifyAction(decision.action, preActionScreen, postActionScreen)

// Include verification result in the tool result message:
val resultDescription = when {
    actionResult is ActionResult.Failed -> "FAILED: ${actionResult.reason}"
    !actionVerification.succeeded -> "ACTION EXECUTED but verification failed: ${actionVerification.reason}"
    else -> "Success. ${actionVerification.observation}"
}
```

Specific verifications:
- `click_button`: Did the screen change? (If not, the click probably missed)
- `type_message`: Is the typed text now present in an input field? (Minitap's insight)
- `scroll_down`: Did the content shift? (If not, we're at the bottom)
- `press_back`: Did the package/activity change? (If not, back may have been consumed by a non-visible component)

### 4.2 Backtracking Strategies

**What the research says**:
- BacktrackAgent generates training data specifically for backtracking decisions
- Agents that leave failed actions in context (rather than removing them) perform better, because the model learns to avoid those paths (Manus's "error preservation" principle)
- The key is detecting *when* to backtrack, not just *how*

**Current Resolve approach**: The `press_back` tool exists and the system prompt mentions "If stuck after 3 attempts, try press_back." But this guidance is vague and passive.

**Recommendation**: Make backtracking an active strategy with a "frustration counter":

```
If the last 3 actions all failed or the screen hasn't changed in 3 turns:
  -> Inject a system message: "BACKTRACK: Your recent actions have not made progress.
     Previous approaches that failed: [list]. You must try a fundamentally
     different navigation path. Consider: press_back to go to a previous screen,
     then try a different menu/tab."
```

The key improvement over the current stagnation hint is *listing what was already tried*, so the LLM does not repeat the same failed approach.

### 4.3 Goal Decomposition

**What the research says**:
- **Minitap** (100% AndroidWorld): Decomposes tasks into two phases (Plan, Act) with six specialized agents. The Planner decomposes goals into ordered subgoals, and the Orchestrator tracks completion status. Multi-agent decomposition alone contributed +21 points over single-agent baselines.
- **Mobile-Agent-v3** (Alibaba Qwen team): Uses a Manager Agent for subgoal planning, Worker Agent for execution, Reflector Agent for evaluation, and Notetaker Agent for recording important observations.
- **ReCAP** (2025): Recursive Context-Aware Planning proposes a full ordered subtask list upfront, with selective re-insertion on backtracking.

**Current Resolve approach**: Navigation phases (NAVIGATING_TO_SUPPORT, ON_ORDER_PAGE, ON_SUPPORT_PAGE, IN_CHAT) provide implicit goal structure, but there is no explicit sub-goal plan.

**Recommendation**: While a full multi-agent architecture is overkill for Resolve's scope, the *planning concept* is not. Implement a lightweight goal decomposition:

1. At the start of the loop, generate a plan (either hardcoded based on app type, or LLM-generated in a separate call):
```
Sub-goals for "Get refund on Swiggy order #12345":
1. Open Swiggy app
2. Navigate to Account/Profile
3. Find Orders section
4. Locate order #12345
5. Open Help/Support for this order
6. Select issue category (if applicable)
7. Reach live chat or support bot
8. Describe issue and request refund
9. Wait for confirmation
```

2. Track which sub-goals have been completed (based on phase detection and key events)
3. Include the plan + progress in every user message (the "dynamic todo.md" pattern from Manus)
4. If the agent deviates from the plan for 3+ turns, inject a "REPLAN" prompt

This gives the LLM a roadmap while allowing flexibility. The +21 points from Minitap's decomposition suggests this is one of the highest-value changes.

### 4.4 Meta-Cognitive Cycle Detection

**What Minitap discovered**: "Meta-cognitive reasoning that detects cycles and triggers strategy changes" adds +9 points. The agent explicitly monitors its own behavior for patterns like:
- Clicking the same button repeatedly
- Alternating between two screens
- Scrolling without finding the target
- Making progress then undoing it

**Current Resolve approach**: Oscillation detection and consecutive duplicate detection exist but only surface as warnings in the user message.

**Recommendation**: Escalate cycle detection from passive warnings to active intervention:

```kotlin
enum class CycleType {
    OSCILLATION,           // A->B->A->B
    REPETITION,            // Same action 3+ times
    SCROLL_EXHAUSTION,     // 5+ scrolls without finding target
    PROGRESS_REVERSAL,     // Went from phase N to phase N-1
}

// When a cycle is detected, don't just warn -- force a strategy change:
when (cycleType) {
    OSCILLATION -> {
        // Remove the oscillating action from the next turn's available tools
        // (via logit masking or explicit prohibition in the prompt)
    }
    SCROLL_EXHAUSTION -> {
        // Temporarily disable scroll tools, force navigation action
    }
    PROGRESS_REVERSAL -> {
        // Log what worked to get to the higher phase, suggest repeating that path
    }
}
```

---

## 5. Screen State Representation

### 5.1 Accessibility Tree vs Screenshot vs Hybrid

**What the research says**:
- Pure accessibility tree (XML/text): Compact, semantic, works with text-only models. Used by DroidRun, DroidBot-GPT, AndroidArena.
- Pure screenshot: Richer visual context, captures non-standard UIs. Used by CogAgent, AppAgent v1.
- Hybrid (tree + screenshot): Best accuracy but highest cost. Used by AppAgent v2, Mobile-Agent-v3.

**For Resolve's case**: Text-only accessibility tree is the right choice because:
1. The agent uses text-only LLM APIs (no vision capability for most providers)
2. Accessibility tree provides semantic labels and interaction states
3. Token cost is manageable (300-500 tokens per screen)
4. The target apps (food delivery, e-commerce) have well-structured accessibility trees

### 5.2 Element Filtering Strategy

**What the research says**:
- CORE (NeurIPS 2025): Uses "layout-aware block partitioning" to group semantically related elements, reducing UI exposure by up to 55.6%.
- WebClaw: Only labels interactive elements (buttons, links, inputs), reducing token count by 51-79% vs labeling everything.
- AndroidArena: Compresses view hierarchy XML by assigning unique node IDs and removing redundant container nodes.

**Current Resolve approach** (`formatForLLM()`): Already good. Filters out invisible, zero-size, and decorative elements. Groups by spatial zone (top bar, content, bottom bar). Deduplicates by position + label. Truncates long text. Limits text items to 25.

**Gaps**:
1. No numeric element IDs (critical for unambiguous action grounding)
2. No indication of which elements are new since last screen (differential highlighting)
3. Scrollable content extent is unknown (is there more content below?)
4. Tab/selected state is not shown (which tab am I on?)

**Recommendation**: Enhance `formatForLLM()`:

```
Package: com.swiggy.android
Screen: OrderDetailActivity
Layout: Order detail page with bottom navigation

[TOP BAR]
  [1] nav-btn: "Navigate up" (left)
  [2] text: "Order Details"

[CONTENT]
  [3] text: "Order #SW-12345"
  [4] text: "Delivered on Feb 25, 2026"
  [5] text: "Butter Chicken x1 - Rs 350"
  [6] text: "Dal Makhani x1 - Rs 250"
  [7] text: "Total: Rs 645"
  [8] btn: "Reorder" (left)
  [9] btn: "Help" (right)              <-- NEW (was not on previous screen)
  (1 scrollable area, can scroll down)

[BOTTOM BAR]
  [10] tab: "Home" (left)
  [11] tab: "Search" (center-left)
  [12] tab: "Cart" (center-right)
  [13] tab: "Account" (right) [SELECTED]

Focused: none
```

Key additions:
- **Numeric IDs** on every interactive + informational element
- **`<-- NEW`** marker for elements not seen on previous screen
- **`[SELECTED]`** state for tabs, checkboxes, toggles
- **Scroll extent** hint ("can scroll down")
- **Enabled/disabled** state for buttons that may be grayed out

### 5.3 Token Optimization

**What the research says**:
- Format choices have dramatic impact on token count. Markdown tables cost more than compact key-value pairs.
- For browser MCPs, formatting can vary token cost by 51-79% for the same information.
- The key insight: "compress but keep it restorable." Preserve element IDs/references even when dropping verbose content.

**Current Resolve token usage per screen**: ~300-500 tokens. This is already reasonably compact.

**Recommendation**: The numbered element system adds ~2-3 tokens per element (the `[N]` prefix) but saves tokens in tool calls (number vs full label string). Net impact is roughly neutral. The real savings come from observation masking (Section 2.1), which can reduce per-call context by 40-60% for later turns.

---

## 6. Recommendations for Resolve

### 6.1 Changes to `AccessibilityEngine.kt` / `ScreenState`

**Add numbered element references**:
- Maintain a `Map<Int, UIElement>` for the current screen
- Each element gets a stable ID for the current turn (IDs reset each turn)
- Expose a `getElementById(id: Int)` method for action execution
- Include the ID in `formatForLLM()` output

**Add selected/checked state display**:
- Already captured in `UIElement.isChecked` but not shown in `formatForLLM()`
- Add tab selection state detection

**Add scroll extent hints**:
- After `scrollScreenForward()`, check if content actually changed (indicates more content exists)
- Track scroll position heuristically (did we reach the bottom?)

### 6.2 Changes to `LLMClient.kt`

**Update `click_button` tool**:
```json
{
  "name": "click_element",
  "description": "Click an interactive element on screen. Prefer using elementId (the number shown in brackets). Fall back to label only if no number is shown.",
  "parameters": {
    "elementId": { "type": "integer", "description": "The [N] number of the element to click" },
    "label": { "type": "string", "description": "Fallback: the text label if elementId is not available" },
    "expectedOutcome": { "type": "string", "description": "What you expect to happen after clicking (e.g., 'Opens order detail page')" }
  },
  "required": ["expectedOutcome"]
}
```

**Update `type_message` tool**:
```json
{
  "name": "type_message",
  "parameters": {
    "text": { "type": "string", "description": "The message to type and send" },
    "elementId": { "type": "integer", "description": "Optional: which input field to type into (if multiple exist)" }
  }
}
```

### 6.3 Changes to `AgentLoop.kt`

**Add structured reasoning enforcement** in `buildSystemPrompt()`:
- Require OBSERVE/THINK/PLAN/ACT format in every response
- Parse the reasoning to extract the plan for progress tracking

**Replace `maybeCompressHistory()` with observation masking**:
- Never remove messages from history
- Replace old `UserObservation.content` with a one-line summary in-place (or shadow the content with a shorter version)
- Keep assistant tool calls and tool results intact
- This preserves KV-cache prefixes

**Add action verification** after every `executeAction()`:
- Capture screen state before and after action
- Compare to detect if the action had the expected effect
- Include verification result in tool result message

**Add explicit sub-goal tracking**:
- Define sub-goals based on the case type and navigation phase
- Track completion based on phase transitions and key events
- Include progress tracker in every user message

**Enhance cycle detection to active intervention**:
- When cycles detected, inject specific remediation instructions
- Temporarily disable problematic tools (e.g., disable scroll after scroll exhaustion)

### 6.4 System Prompt Restructuring

The system prompt should be restructured for maximum effectiveness. Key principles from the research:

1. **Stable prefix**: The system prompt content should not change between turns (KV-cache). Move all dynamic content (phase, progress, warnings) to the user message.

2. **Role and constraints first**: Identity, hard rules, and safety constraints at the top.

3. **Reasoning format requirement**: Enforce structured thinking.

4. **Few-shot examples**: 2-3 short examples demonstrating correct reasoning and action selection.

5. **Navigation knowledge**: App-specific patterns as reference material (not instructions).

Proposed structure:
```
[1. Identity and role]
[2. Hard rules and constraints]
[3. Reasoning format requirement (OBSERVE/THINK/PLAN/ACT)]
[4. Navigation knowledge base (app patterns)]
[5. Action usage guidelines]
[6. Few-shot examples]
```

The user message structure:
```
[1. Case details (issue, desired outcome, order ID)]
[2. Progress tracker (sub-goals with checkmarks)]
[3. Turn counter and budget]
[4. Screen change feedback]
[5. Warnings (oscillation, stagnation, scroll budget)]
[6. Current screen state (with numbered elements)]
[7. Phase-specific goal reminder]
```

**Critical**: Move case details (issue, desired outcome) to the user message, not the system prompt. This way, the system prompt is identical across all cases, maximizing KV-cache reuse if running multiple cases with the same model.

---

## 7. Implementation Priority

Ordered by expected impact / implementation effort ratio:

### Tier 1: High Impact, Moderate Effort (do first)

1. **Numbered element references** in `formatForLLM()` + updated `click_element` tool
   - Expected impact: Large (eliminates element matching ambiguity)
   - Effort: ~2-3 hours (modify formatForLLM, update tool def, add element map, update executeAction)
   - Evidence: Every production mobile agent uses numbered/coordinate-based references

2. **Structured reasoning enforcement** (OBSERVE/THINK/PLAN/ACT)
   - Expected impact: Medium-Large (forces deliberate decision-making)
   - Effort: ~1 hour (system prompt change + reasoning parser for logs)
   - Evidence: MobileUse +20% from structured reflection; zero-cost improvement

3. **Explicit sub-goal tracking with progress display**
   - Expected impact: Large (prevents goal drift, provides roadmap)
   - Effort: ~2-3 hours (sub-goal generator, progress tracker, user message update)
   - Evidence: Minitap +21 points from decomposition alone

### Tier 2: High Impact, Higher Effort (do second)

4. **Action verification** (post-action screen comparison)
   - Expected impact: Medium (catches failures within 1 turn instead of 3)
   - Effort: ~3-4 hours (verification logic per action type, screen diff, integration)
   - Evidence: Minitap +7 points from verified execution

5. **Observation masking** (replace old screen dumps with summaries)
   - Expected impact: Medium (reduces cost, prevents attention dilution)
   - Effort: ~2 hours (modify conversation management, implement masking)
   - Evidence: Manus reports 10x cost reduction; JetBrains confirms effectiveness

6. **Enhanced cycle detection with active intervention**
   - Expected impact: Medium (prevents wasted iterations)
   - Effort: ~2 hours (cycle classifier, remediation strategies, tool disabling)
   - Evidence: Minitap +9 points from meta-cognition

### Tier 3: Valuable but Lower Priority

7. **Few-shot examples** in system prompt
   - Expected impact: Small-Medium (depends on model; GPT-5 Nano may benefit more)
   - Effort: ~1 hour (craft 2-3 examples, test token impact)
   - Evidence: Mixed; helps smaller models more

8. **Move case details to user message** (KV-cache optimization)
   - Expected impact: Small (cost reduction for multi-case scenarios)
   - Effort: ~30 min (refactor prompt construction)
   - Evidence: Manus principle of stable system prompt prefix

9. **Scroll extent detection**
   - Expected impact: Small (prevents scrolling past the end)
   - Effort: ~1 hour
   - Evidence: Quality-of-life improvement

10. **`open_app` tool for recovery**
    - Expected impact: Small (escape hatch for deeply lost agent)
    - Effort: ~1 hour
    - Evidence: Several frameworks include this for robustness

---

## Sources

### Papers and Academic Research

- Yao et al. "ReAct: Synergizing Reasoning and Acting in Language Models" (2022). The foundational ReAct paper.
  https://www.promptingguide.ai/techniques/react

- "BacktrackAgent: Enhancing GUI Agent with Error Detection and Backtracking Mechanism" (EMNLP 2025). Error detection and recovery for GUI agents.
  https://arxiv.org/abs/2505.20660

- "MobileUse: A Hierarchical Reflection-Driven GUI Agent for Autonomous Mobile Operation" (NeurIPS 2025). Hierarchical reflection architecture achieving SOTA on AndroidWorld.
  https://arxiv.org/abs/2507.16853

- "Mobile-Agent-v3: Fundamental Agents for GUI Automation" (2025). Alibaba Qwen team's multi-agent framework with Manager/Worker/Reflector/Notetaker.
  https://arxiv.org/abs/2508.15144

- "Do Multi-Agents Dream of Electric Screens? Achieving Perfect Accuracy on AndroidWorld Through Task Decomposition" (Minitap, 2026). First system to achieve 100% on AndroidWorld.
  https://arxiv.org/abs/2602.07787

- "CORE: Reducing UI Exposure in Mobile Agents via Collaboration Between Cloud and Local LLMs" (NeurIPS 2025). Layout-aware element partitioning.
  https://arxiv.org/abs/2510.15455

- "MemTool: Optimizing Short-Term Memory Management for Dynamic Tool Calling in LLM Agent Multi-Turn Conversations" (2025).
  https://arxiv.org/abs/2507.21428

- "Set-of-Mark Prompting Unleashes Extraordinary Visual Grounding in GPT-4V" (Microsoft Research, 2023). Numbered element labels for grounding.
  https://arxiv.org/abs/2310.11441

- "AppAgent v2: Advanced Agent for Flexible Mobile Interactions" (2024). Structured knowledge base for app elements.
  https://arxiv.org/abs/2408.11824

- "DroidBot-GPT: GPT-powered UI Automation for Android" (2023). Early accessibility tree to LLM pipeline.
  https://arxiv.org/abs/2304.07061

- "Zero-shot Tool Instruction Optimization for LLM Agents" (ACL 2025 Findings). TOOLVERIFIER achieving 22% improvement via optimized tool descriptions.
  https://aclanthology.org/2025.findings-acl.1347.pdf

### Blog Posts and Production Systems

- "Context Engineering for AI Agents: Lessons from Building Manus" (2025). KV-cache optimization, observation masking, append-only context, tool naming, error preservation.
  https://manus.im/blog/Context-Engineering-for-AI-Agents-Lessons-from-Building-Manus

- LangChain. "Few-shot prompting to improve tool-calling performance" (2024). Quality over quantity for few-shot examples.
  https://blog.langchain.com/few-shot-prompting-to-improve-tool-calling-performance/

- LangChain. "Context Engineering for Agents" (2025).
  https://blog.langchain.com/context-engineering-for-agents/

- JetBrains Research. "Cutting Through the Noise: Smarter Context Management for LLM-Powered Agents" (December 2025). Observation masking as first defense against context bloat.
  https://blog.jetbrains.com/research/2025/12/efficient-context-management/

- Scrapybara. "Unifying the Computer Use Action Space" (2025). Standardized action space across platforms.
  https://scrapybara.com/blog/unified-action-space

- GetMaxim. "Context Window Management: Strategies for Long-Context AI Agents and Chatbots" (2025).
  https://www.getmaxim.ai/articles/context-window-management-strategies-for-long-context-ai-agents-and-chatbots/

### Open-Source Frameworks

- DroidRun: LLM-agnostic mobile agent framework. Action space, accessibility service perception layer.
  https://github.com/droidrun/droidrun

- AppAgent / AppAgentX: Tencent's multimodal smartphone agent framework.
  https://github.com/TencentQQGYLab/AppAgent

- Mobile-Agent-v3 / GUI-Owl: Alibaba's multi-agent GUI automation.
  https://github.com/X-PLUG/MobileAgent

- Minitap mobile-use: Six-agent decomposition framework achieving 100% on AndroidWorld.
  https://github.com/minitap-ai/mobile-use

- MobileUse: Hierarchical reflection GUI agent.
  https://github.com/MadeAgents/mobile-use

- UI-TARS: ByteDance's native GUI agent.
  https://github.com/bytedance/UI-TARS

### Benchmarks

- AndroidWorld: Dynamic benchmarking environment for autonomous agents.
  https://google-research.github.io/android_world/

- MobileAgentBench: Efficient benchmark for mobile LLM agents.
  https://arxiv.org/abs/2406.08184
