# Resolve - AI Customer Support Agent

Open-source AI agent that handles customer support chats **on behalf of the customer**.

You describe your issue once with evidence, and Resolve takes over: it navigates the support chat, clicks buttons, types messages, uploads files, and resolves your issue end-to-end. No more waiting in chat queues, repeating yourself to bots, or clicking through endless menus.

## How it works

```
You: "My order #12345 arrived damaged. I want a full refund."
     [attaches photo of damaged package]

Resolve: Opens the company's support chat widget
         -> Describes the issue
         -> Uploads evidence photo
         -> Navigates the support flow
         -> Negotiates resolution
         -> Gets refund confirmation
         -> Notifies you: "Done. Refund of $49.99 approved, 3-5 business days."
```

## Architecture

```
┌──────────────────────┐     WebSocket      ┌──────────────────────┐
│   Chrome Extension   │ <================> │   Backend Server     │
│                      │                    │                      │
│  popup/              │                    │  Agent Loop          │
│    Issue form UI     │  widget state -->  │    Observe state     │
│    Live activity     │  <-- actions       │    Ask Claude        │
│    Approve/pause     │                    │    Validate policy   │
│                      │                    │    Return action     │
│  content/            │                    │                      │
│    Widget detector   │                    │  Claude API          │
│    DOM extractor     │                    │    Tool-use agent    │
│    DOM executor      │                    │    System prompt     │
│                      │                    │    6 tools defined   │
│  background/         │                    │                      │
│    Message routing    │                    │  Safety              │
│    WS management     │                    │    PII filter        │
│                      │                    │    Action policy     │
│  8 widget profiles   │                    │    Rate limiting     │
└──────────────────────┘                    └──────────────────────┘
```

### Three execution channels

| Channel | Use case |
|---------|----------|
| **Browser Extension** | Chrome extension detects live chat widgets and automates them via DOM manipulation. Claude decides each action. |
| **Mock Browser** | Playwright drives a mock support portal for testing and demos. |
| **Mobile Companion** | Android companion app executes support flows on mobile apps via accessibility APIs. |

## Supported chat widgets

The extension detects and interacts with these providers out of the box:

- **Intercom** - contenteditable input, iframe-based
- **Zendesk** - Web Widget / Messaging
- **Freshchat** - Freshworks chat widget
- **Drift** - Drift messenger
- **Tawk.to** - Tawk chat widget
- **Crisp** - Crisp chatbox
- **HubSpot** - HubSpot Messages
- **LiveChat** - LiveChat widget
- **Generic** - Heuristic fallback for unknown widgets

Widget profiles are defined in `extension/lib/widget-detector.js`. Contributing new profiles is one of the easiest ways to contribute.

## Quick start

### 1. Backend server

```bash
git clone <repo-url>
cd cs-support
npm install
npx playwright install chromium   # for mock browser channel

# Set your Anthropic API key
cp .env.example .env
# Edit .env and set ANTHROPIC_API_KEY

npm run dev
```

Server runs at `http://127.0.0.1:8787` with WebSocket at `ws://127.0.0.1:8787/ws/agent`.

### 2. Chrome Extension

1. Open Chrome and navigate to `chrome://extensions`
2. Enable **Developer mode** (top right toggle)
3. Click **Load unpacked**
4. Select the `extension/` folder from this repository
5. Pin the Resolve extension to your toolbar

### 3. Use it

1. Navigate to any website with a customer support chat widget
2. Click the Resolve extension icon
3. Describe your issue and attach evidence
4. Select your desired outcome
5. Click **Resolve It**
6. Watch the agent work in the activity feed
7. Approve or provide input when the agent asks

### 4. Android companion (single-submit UX)

1. Open `android-companion/` in Android Studio and run the app on an emulator/device.
2. Keep backend running locally (`npm run dev`).
3. In the app, fill:
   - API key
   - App name
   - Issue title
   - Short description
   - Optional media attachments
4. Tap **Submit Issue**.

The app automatically bootstraps device session + permissions, submits the case, and starts the mobile agent loop.

## Agent tools

The Claude-powered agent has 6 tools:

| Tool | Description |
|------|-------------|
| `type_message` | Type and send a message in the chat |
| `click_button` | Click a button or quick-reply option |
| `upload_file` | Upload evidence (screenshot, receipt) |
| `wait_for_response` | Wait for the support agent to respond |
| `request_human_review` | Pause and ask the customer for input |
| `mark_resolved` | Mark the issue as resolved |

## Safety model

Multiple layers of protection:

- **PII filtering**: SSN, credit card, password patterns are redacted before reaching Claude.
- **Action-level policy gates**: Financial actions and commitments require explicit user approval.
- **Sensitive data blocking**: Agent cannot send messages containing sensitive terms.
- **Rate limiting**: Minimum 2s between actions, maximum 30 iterations per case.
- **Human-in-the-loop**: Agent pauses for unknown information or uncertain decisions.
- **Full audit trail**: Every action logged to case timeline and archived to disk.

## API endpoints

### Cases
- `POST /api/cases` - Create a new support case
- `POST /api/cases/:id/start` - Start via mock browser
- `POST /api/cases/:id/start-mobile` - Start via mobile companion
- `POST /api/cases/:id/start-extension` - Start via browser extension
- `POST /api/cases/:id/approve` - Resume a paused case
- `GET /api/cases` - List all cases
- `GET /api/cases/:id` - Get case details

### Devices (mobile companion)
- `POST /api/devices/register`
- `GET /api/devices`
- `GET /api/devices/:id`
- `POST /api/devices/:id/permissions`
- `GET /api/devices/:id/commands`

### Client/mobile (API key flow)
- `POST /api/client/mobile/submit` - One call to bootstrap session, create case, and dispatch mobile automation

### Device agent protocol
- `POST /api/device-agent/poll`
- `POST /api/device-agent/commands/:id/events`
- `POST /api/device-agent/commands/:id/complete`
- `POST /api/device-agent/commands/:id/fail`

### WebSocket
- `ws://127.0.0.1:8787/ws/agent` - Real-time extension communication

## Demo: browser extension flow

```bash
# 1. Start the server
npm run dev

# 2. Load the extension in Chrome (see Quick Start above)

# 3. Navigate to any site with a chat widget (or use the mock portal)
open http://127.0.0.1:8787/mock-support

# 4. Click the Resolve extension icon and submit your issue
```

## Demo: mock browser flow

```bash
curl -sS -X POST http://127.0.0.1:8787/api/cases \
  -H 'content-type: application/json' \
  -d '{
    "customerName": "Asha Patel",
    "issue": "My package arrived damaged and I need a refund",
    "orderId": "ORD-2026-01",
    "attachmentPaths": ["fixtures/damaged-package.txt"]
  }'

# Then start and check:
curl -sS -X POST http://127.0.0.1:8787/api/cases/<CASE_ID>/start
curl -sS http://127.0.0.1:8787/api/cases/<CASE_ID>
```

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | 8787 | Server port |
| `BASE_URL` | `http://127.0.0.1:8787` | Server base URL |
| `ANTHROPIC_API_KEY` | (required) | Claude API key for the agent |
| `CLAUDE_MODEL` | `claude-sonnet-4-20250514` | Which Claude model to use |
| `MOBILE_COMMAND_TIMEOUT_MS` | 120000 | Mobile command timeout |
| `CONVERSATION_ARCHIVE_DIR` | `./conversations` | Where to save case transcripts |

## Project structure

```
cs-support/
├── extension/                    # Chrome extension (vanilla JS)
│   ├── manifest.json             # Manifest V3
│   ├── popup/                    # Extension popup UI
│   │   ├── popup.html            # Issue form + activity feed
│   │   ├── popup.css             # Dark theme UI
│   │   └── popup.js              # Popup logic
│   ├── background/
│   │   └── service-worker.js     # Message routing + WebSocket
│   ├── content/
│   │   └── main.js               # Widget detection + action execution
│   └── lib/
│       ├── widget-detector.js    # 8 provider profiles + heuristic fallback
│       ├── dom-extractor.js      # Extract normalized chat state
│       ├── dom-executor.js       # Type, click, upload with framework compat
│       └── message-bus.js        # Chrome messaging wrapper
├── src/                          # Backend (TypeScript + Express)
│   ├── index.ts                  # Server + WebSocket + routes
│   ├── domain/
│   │   ├── types.ts              # Core types
│   │   ├── case-store.ts         # Case state machine
│   │   ├── device-store.ts       # Mobile device registry
│   │   └── device-command-store.ts
│   ├── services/
│   │   ├── support-agent.ts      # Orchestration (3 channels)
│   │   ├── agent-loop.ts         # Observe-think-act cycle
│   │   ├── claude-client.ts      # Claude API + tool definitions
│   │   ├── policy.ts             # Case + action policy gates
│   │   ├── sensitive-filter.ts   # PII redaction
│   │   ├── intent.ts             # Keyword intent classifier
│   │   ├── conversation-archive.ts
│   │   └── mobile-permissions.ts
│   ├── automation/
│   │   └── mock-support-runner.ts
│   └── web/
│       └── mock-support-page.ts
├── android-companion/            # Android companion app
├── tests/                        # Vitest test suite
├── conversations/                # Archived case transcripts
└── fixtures/                     # Test fixtures
```

## Tests

```bash
npm test
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). The easiest ways to contribute:

1. **Add widget profiles** - Test with a chat widget, document its selectors, add to `widget-detector.js`
2. **Improve DOM extraction** - Better message parsing for specific providers
3. **Add tests** - More coverage for the agent loop and policy gates
4. **Mobile companion** - Implement real accessibility-based automation for Android apps

## Current limitations

- Runtime state is in-memory (no DB), but case transcripts are archived to disk.
- Extension requires the backend to be running locally (no hosted mode yet).
- Widget profiles may need updating as providers change their DOM.
- No auth/tenant model yet (device token is prototype-grade).
- iOS cross-app autonomy constraints still apply for mobile channel.
- File upload works for providers that use standard `<input type="file">` elements.

## License

MIT
