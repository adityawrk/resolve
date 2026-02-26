---
globs: ["src/**/*.ts", "tests/**/*.ts"]
---

# Backend Rules

## Module System

This project uses Node ESM (`"type": "module"` in package.json). All internal imports MUST use `.js` extensions:

```typescript
// Correct
import { CaseStore } from '../domain/case-store.js';
// Wrong — will fail at runtime
import { CaseStore } from '../domain/case-store';
```

## API Routes

All route handlers validate input with Zod before use. Follow the existing pattern in `src/index.ts`:

```typescript
const schema = z.object({ field: z.string().min(1) });
const parsed = schema.safeParse(req.body);
if (!parsed.success) return res.status(400).json({ error: parsed.error.message });
```

## Stores

All stores (`CaseStore`, `DeviceStore`, `DeviceCommandStore`, `ApiKeySessionStore`) are in-memory Maps. They are singletons instantiated in `src/index.ts` and passed to services via constructor injection. Do not import stores directly from service files.

## Agent Loop

`AgentLoopManager` in `src/services/agent-loop.ts` manages per-case sessions. Constants:
- `MAX_ITERATIONS = 30`
- `MIN_ACTION_INTERVAL_MS = 2000`
- `WAIT_TIMEOUT_MS = 45000`

The loop waits for widget state from the extension via WebSocket before deciding the next action. Never call the LLM without fresh widget state.

## LLM Client

`src/services/claude-client.ts` uses Azure OpenAI (GPT-5 Nano) via the openai SDK. Tool definitions live in this file. When adding a new tool:
1. Add to `tools` array in `callWithTools()`
2. Add parsing in `parseToolCall()`
3. Handle in agent-loop's `executeAction()`

## Testing

- Test runner: vitest (configured for ES modules)
- Run single file: `npx vitest run tests/<file>.spec.ts`
- Tests do not require a running server; they test domain logic and services directly
- Use `describe`/`it`/`expect` (vitest globals)
