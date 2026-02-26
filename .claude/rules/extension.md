---
globs: ["extension/**/*.js", "extension/**/*.html", "extension/**/*.css", "extension/manifest.json"]
---

# Chrome Extension Rules

## Architecture

Manifest V3. No bundler — plain JS files loaded in order via manifest.json content_scripts:
`message-bus.js → widget-detector.js → dom-extractor.js → dom-executor.js → main.js`

Each library is an IIFE that exposes a single global: `ResolveBus`, `ResolveDetector`, `ResolveExtractor`, `ResolveExecutor`.

## Widget Detection

`ResolveDetector` has 8 named profiles (Intercom, Zendesk, Freshchat, Drift, Tawk, Crisp, HubSpot, LiveChat) plus a generic heuristic fallback. Each profile defines:
- `detect.iframeSelectors` / `detect.domMarkers` / `detect.hostIndicators`
- `selectors` for messageList, inputField, sendButton, etc.
- `inputMethod` and `sendMethod`

Detection runs inside iframes first (origin check), then tries the top frame, then heuristic.

When adding a new widget profile:
1. Add the profile object in `widget-detector.js` under `profiles`
2. Include `detect`, `selectors`, `inputMethod`, `sendMethod`
3. The generic heuristic handles unknown widgets — only add a profile if the widget needs custom selectors

## Communication

- **Content ↔ Background**: `chrome.runtime.sendMessage` via `ResolveBus`
- **Background ↔ Backend**: WebSocket to `ws://127.0.0.1:8787/ws/agent`
- **Background ↔ Popup**: `chrome.runtime.sendMessage` (popup polls on interval)

Message types from content: `widget_found`, `widget_state_update`, `widget_lost`
Message types from popup: `get_widget_status`, `get_connection_status`, `start_case`, `pause_case`, `stop_case`, `approve_case`

## DOM Execution

`ResolveExecutor` handles `type_message`, `click_button`, `upload_file`, `wait`. It auto-detects contenteditable vs textarea for typing. Send method is profile-aware (enter key vs click button).

## Key Constraints

- Content scripts run in an isolated world — no access to page JS globals
- The extension has no permissions beyond `activeTab` and `scripting` — do not add broad host permissions
- The service worker has no DOM access — all DOM operations go through content scripts
- Double-injection guard in `main.js` (`window.__resolveContentLoaded`) — do not remove
