# OpenAI Codex CLI Research — Patterns for Resolve

**Date**: February 2026
**Source**: github.com/openai/codex (Rust implementation in `codex-rs/`)
**Method**: 5 parallel research agents analyzing Agent Loop Architecture, Safety & Guardrails, UX & User Interaction, Prompt Engineering, and Error Recovery & Resilience

---

## 1. Agent Loop Architecture

### Codex's SQ/EQ Pattern

Codex uses a **Submission Queue / Event Queue** pattern with clean separation:

- **Session Layer** (`codex.rs`): Long-lived state, conversation history, `ActiveTurn` mutex
- **Turn Layer** (`state/turn.rs`): Per-turn execution state, pending approvals, in-flight tools
- **Task Layer** (`tasks/`): Five task types implementing `SessionTask` trait:
  - `RegularTask` — main LLM-call-and-tool-execution cycle
  - `CompactTask` — context window compaction
  - `ReviewTask` — content review
  - `UndoTask` — undo via ghost snapshots
  - `GhostSnapshotTask` — pre-action state snapshots

The core turn cycle:
1. Create `TurnContext` with model, tools, policies, sandbox config
2. Send conversation history to LLM
3. Stream response events through `handle_output_item_done()`
4. Tool calls queued as `InFlightFuture` via `tool_runtime.handle_tool_call()`
5. Tool results fed back to model for next iteration
6. Turn ends on: final text without tool calls, max iterations, or user interrupt

Key insight: The loop is **event-driven, not polling-based**. The `needs_follow_up` flag on `OutputItemResult` determines whether another LLM call is needed.

### Tool Dispatch Chain

1. **Router** (`tools/router.rs`): Converts LLM response items into `ToolCall` objects
2. **Registry** (`tools/registry.rs`): `HashMap<String, Arc<dyn ToolHandler>>` with `kind()`, `is_mutating()`, `handle()`
3. **Orchestrator** (`tools/orchestrator.rs`): Three-phase: approval → sandbox → execution → conditional retry
4. **Parallel Runtime** (`tools/parallel.rs`): `Arc<RwLock<()>>` — parallel tools get read lock, sequential get write lock

### Error Hierarchy

```
FunctionCallError:
  - RespondToModel(String)  — recoverable, tell the model what went wrong
  - MissingLocalShellCallId — structural error
  - Fatal(String)           — non-recoverable, abort turn
```

`RespondToModel` is the critical pattern: when a tool fails, the error message is sent back to the LLM as the tool result, giving the model a chance to self-correct. Only `Fatal` errors abort the turn.

### The Plan Tool (No-Op)

Codex implements planning as a **structured tool call** that does nothing:

```
update_plan(explanation: String?, plan: [{step: String, status: Pending|InProgress|Completed}])
```

From source code comment: *"This function doesn't do anything useful. However, it gives the model a structured way to record its plan that clients can read and render."*

The value is in:
1. Making the model's reasoning visible to users
2. Creating a checklist the model can update as it progresses
3. Providing structured data for progress rendering in the UI

---

## 2. Prompt Engineering

### System Prompt Structure

Codex uses a layered, composable system prompt (~700 lines) from `codex-rs/core/prompt.md`:

```
# Identity and capabilities paragraph
# How you work
  ## Personality
  ## Responsiveness (with preamble examples)
  ## Planning (with good/bad plan examples)
  ## Task execution (behavioral rules)
  ## Validating your work
  ## Ambition vs. precision
  ## Sharing progress updates
  ## Presenting your work and final message
# Tool Guidelines
  ## Shell commands
  ## update_plan
```

### Tool Definitions

Each tool has rich descriptions with:
- Usage examples directly in the description string
- When NOT to use the tool
- Approval-related parameters dynamically injected via `create_approval_parameters()`
- The `apply_patch` tool has its own grammar definition file + instructions markdown

### Context Injection

Dynamic context injected through multiple mechanisms:

- **Environment context as XML**: `<environment_context><cwd>...</cwd></environment_context>`
- **AGENTS.md as tagged user messages**: `<INSTRUCTIONS>...</INSTRUCTIONS>`
- **Skills wrapped in XML**: `<skill><name>demo-skill</name>...</skill>`
- **Differential settings updates**: Only emits what changed between turns

### Instruction Hierarchy

Explicit priority order documented in the prompt:
1. Direct system/developer/user prompt instructions (highest)
2. More-deeply-nested AGENTS.md files
3. Root-level AGENTS.md files (lowest)

### Few-Shot Examples

- Inline examples in system prompt (high/low quality plan examples)
- Preamble message examples (8 concise progress updates)
- Tool usage examples embedded in tool descriptions
- NO traditional few-shot user/assistant message pairs

### Token Efficiency

- **Truncation** (`truncate.rs`): Head+tail preservation with `"…{count} tokens truncated…"` marker
- **Differential context**: Only emits settings changes, not full re-injection
- **Compaction**: Separate LLM call to create "handoff summary" replacing full history
- **Image estimation**: Fixed 7373 bytes instead of full data URL size
- **4-bytes-per-token heuristic** for fast estimation

---

## 3. Safety & Guardrails

### Two-Axis Permission Model

**SandboxMode** × **AskForApproval**:

```
SandboxMode: "read-only" | "workspace-write" | "danger-full-access"

AskForApproval: "untrusted" | "on-failure" | "on-request" | { "reject": config } | "never"
```

### Approval Decisions (6 outcomes)

- `accept` — one-time approval
- `acceptForSession` — approve for current session
- `acceptWithExecpolicyAmendment` — approve + create rule for similar future commands
- `applyNetworkPolicyAmendment` — per-host allow/deny rules
- `decline` — reject command
- `cancel` — cancel workflow

**Banned amendment prefixes**: `python`, `bash`, `perl`, `ruby`, `node`, `sudo`, `git` — prevents overly broad auto-approve rules.

### Sandboxing

- **macOS**: Seatbelt with deny-all base policy + dynamic extensions
- **Linux**: Bubblewrap + seccomp + Landlock LSM (three-layer)
- **Windows**: Restricted tokens + ACLs + firewall rules
- **Fail-closed**: Invalid sandbox config blocks all access, never falls back to unrestricted

### Orchestrator Escalation Pattern

1. Execute in sandbox
2. If sandbox denies → detect via `is_likely_sandbox_denied()` (scans for "operation not permitted", checks exit codes)
3. Request user approval for escalated execution
4. Retry without sandbox only with explicit consent

### PII Handling

Codex does **environment variable filtering**, not content-level PII detection:
- Default exclusions: `*KEY*`, `*SECRET*`, `*TOKEN*` patterns
- Custom exclusion patterns
- Platform-aware (case-insensitive on Windows)
- No SSN/CC regex (our SafetyPolicy.kt is more advanced here)

### Rate Limiting

- Account-level: `RateLimitWindow { used_percent, window_minutes, resets_at }`
- Agent-level: Thread spawn limits via `Guards` with `AtomicUsize` counters
- Execution: Default 10s timeout, 1 MiB output cap, 10,000 event deltas per call

---

## 4. UX & User Interaction

### Real-Time Feedback

- **StatusIndicatorWidget**: Shimmer animation + elapsed timer + "esc to interrupt" hint
- **Shimmer**: Cosine-weighted color wave, falls back to BOLD/DIM on limited terminals
- **Exec Cells**: Color-coded bullet, syntax-highlighted command, truncated output (5 lines for tools, 50 for user), exit icon + duration
- **ASCII Animations**: 10 variants × 36 frames at 80ms ticks

### Progress Tracking

- NO step counter or estimated time remaining
- **Plan mode**: TodoList with checkbox states (checked/unchecked)
- **FinalMessageSeparator**: Total elapsed time and runtime metrics
- **Token usage card**: Input/output breakdown, context window %, rate limit bars

### Streaming Display

**Newline-gated streaming with adaptive chunking**:

- **Smooth Mode** (default): One line per animation tick
- **Catch-Up Mode**: Activates at queue depth ≥8 or oldest item >120ms; drains all queued lines
- **Hysteresis**: 250ms sustained low pressure before returning to smooth mode
- **Severe backlog** (64+ lines or 300ms+): Bypass cooldown entirely

### User Interruption

- `Esc` interrupts current turn, preserves queued messages
- `Ctrl+C` quits (with "press again to quit" confirmation)
- Approval overlay pauses execution, blocks until user responds
- `InterruptManager` uses `VecDeque<QueuedInterrupt>` for sequential processing

### Transparency Tiers

- **Agent Reasoning Events**: Configurable detail (Auto, Concise, Detailed, None)
- **Exec Cells**: Full commands with syntax highlighting, truncated output
- **Transcript Overlay** (Ctrl+T): Full conversation history, auto-follow + manual scroll
- **Session Header**: Model, cwd, approval policy, sandbox, token usage

### History / Logging

- 17+ history cell types (user messages, AI responses, reasoning, commands, patches, tool calls, etc.)
- Persistent history as `~/.codex/history.jsonl`
- SQLite state DB for session metadata
- Resume/fork sessions from any point

### Configuration

- CLI args: `-m model`, `-a approval-policy`, `-s sandbox-mode`, `--full-auto`
- Config file: `~/.codex/config.toml` (model defaults, MCP servers, notification hooks)
- Runtime changeable: model, reasoning effort, approval policy, collaboration mode
- Progressive delegation: start strict, loosen per user comfort

---

## 5. Error Recovery & Resilience

### Retry with Exponential Backoff + Jitter

From `codex-rs/codex-client/src/retry.rs`:

```
delay = base_delay × 2^attempt × random(0.9, 1.1)
```

**RetryOn** flags: `retry_429`, `retry_5xx`, `retry_transport`

Error classification:
- `Http(status, body)` — retried based on status code
- `RetryLimit` — terminal
- `Timeout` — retryable
- `Network` — retryable
- `Build` — NOT retryable (malformed request)

Higher-level classification:
- `ContextWindowExceeded` — NOT retryable, needs compaction
- `QuotaExceeded` — NOT retryable
- `Retryable { message, delay }` — explicit retry hint
- `RateLimit` — retryable, parses `"try again in Xs"` from error
- `InvalidRequest` — NOT retryable

### Malformed Response Handling

- Invalid JSON in SSE: logged at debug, event **skipped**, processing continues
- Missing required fields: silently skipped
- Unknown event types: traced but ignored (forward compatibility)
- Stream closes without completion: specific error returned

### Graceful Degradation

- WebSocket → HTTP fallback (permanent for session on failure)
- Context compaction on overflow (trim oldest, retry)
- Cached tools fallback when MCP refresh fails
- Noop file watcher when initialization fails
- One-time 401 recovery (refresh token, retry once)
- Poison mutex recovery (`unwrap_or_else(PoisonError::into_inner)`)

### Timeout Layers

- Per-command: 10s default (`DEFAULT_EXEC_COMMAND_TIMEOUT_MS`)
- IO drain: 2s guard against hung pipes
- SSE stream: configurable idle timeout
- Graceful task abort: 100ms grace before forced termination
- MCP connectors: 30s ready timeout
- Output cap: 1 MiB per stream

### State Persistence for Crash Recovery

- **Rollout recording**: JSONL files at `~/.codex/sessions/YYYY/MM/DD/`
  - Deferred materialization, async channel-based persistence
  - Output truncated to 10KB before persistence
- **SQLite state DB**: Thread metadata, dynamic tools
  - Read-repair reconciles DB against filesystem
  - Background backfill without blocking startup
- **Session resumption**: `resume_thread_from_rollout()` reconstructs full state
- **Ghost snapshots**: Git commits capture filesystem state at checkpoints

### Diagnostic Logging

- Structured tracing (`tracing::warn!`, `tracing::debug!`)
- OpenTelemetry integration with per-request metrics
- `feedback_tags!` macro for structured post-mortem data
- Per-attempt telemetry (start time, duration, HTTP status)
- Error context: request IDs, Cloudflare ray IDs, rate limit snapshots
- Error messages capped at 2 KiB

---

## 6. Notable Gaps in Codex (Where Resolve Is Better)

### Loop/Stuck Detection
Codex has **no explicit stuck detection**. Relies on context window pressure, user interruption, and timeouts. Our oscillation detection, element banning, stagnation counter, and 30-iteration hard limit are more robust for UI automation.

### PII Detection
Codex only filters environment variables. Our SSN regex + credit card Luhn + password pattern matching is more advanced for customer support where PII appears in screen content.

### Post-Action Verification
Codex has no explicit "did this work?" checks. Our screen fingerprint comparison, text readback for TypeMessage, and verification failures reported to LLM are stronger.

### Sub-Goal Decomposition
Codex has the plan tool but no enforced sub-goal tracking. Our 8 hardcoded sub-goals with `NavigationPhase` enum and phase-specific directives are more targeted.

---

## 7. Implementation Plan

### Phase 1: Critical Fixes (from emulator testing)
1. Loading race condition fix (wait for home screen before first iteration)
2. Menu page far-right icon disambiguation in Dominos nav profile
3. App-scope restriction (prevent agent from running in other apps)

### Phase 2: High-Value, Low-Effort Patterns
4. Plan tool (no-op tool for structured LLM reasoning + UI visibility)
5. LLM retry with exponential backoff + jitter + error classification
6. Session-scoped approvals (approve once, auto-approve similar for session)
7. Descriptive error feedback to LLM (RespondToModel pattern)

### Phase 3: Medium-Effort Architecture
8. Context compaction (token estimation + summarization at 70% context)
9. Screen content PII pre-filtering before LLM
10. Richer MonitorActivity feed (cards with status bullets, elapsed time, expandable detail)
11. Structured system prompt with explicit hierarchy and few-shot examples

### Phase 4: Strategic Improvements
12. Conversation history normalization (ensure every tool call has a result)
13. Progressive trust (per-app approval whitelists that build over time)
14. Agent state checkpoints for crash recovery
15. Action journaling with timestamps and screen snapshots
