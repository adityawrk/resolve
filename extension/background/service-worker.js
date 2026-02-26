/**
 * Resolve - Background Service Worker
 *
 * Central hub: routes messages between popup, content scripts, and the backend.
 * Maintains one WebSocket per active session.
 */

const BACKEND_URL = 'http://127.0.0.1:8787';
const WS_URL = 'ws://127.0.0.1:8787/ws/agent';

// ─── State ──────────────────────────────────────────────────────────────────

/** @type {WebSocket | null} */
let ws = null;
let wsConnected = false;
let reconnectTimer = null;
let activeCaseId = null;

/**
 * Registry of detected widgets per tab.
 * Map<tabId, { frameId, provider, profile }>
 */
const widgetRegistry = new Map();

// ─── WebSocket management ───────────────────────────────────────────────────

function connectWs() {
  if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
    return;
  }

  try {
    ws = new WebSocket(WS_URL);

    ws.onopen = () => {
      wsConnected = true;
      clearTimeout(reconnectTimer);
      broadcastToPopup({ type: 'connection_status', connected: true });
    };

    ws.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data);
        handleBackendMessage(msg);
      } catch (e) {
        console.error('[Resolve] Failed to parse WS message:', e);
      }
    };

    ws.onclose = () => {
      wsConnected = false;
      ws = null;
      broadcastToPopup({ type: 'connection_status', connected: false });
      scheduleReconnect();
    };

    ws.onerror = () => {
      wsConnected = false;
    };
  } catch {
    scheduleReconnect();
  }
}

function scheduleReconnect() {
  clearTimeout(reconnectTimer);
  reconnectTimer = setTimeout(connectWs, 3000);
}

function sendToBackend(msg) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(msg));
    return true;
  }
  return false;
}

// ─── Handle messages from the backend ───────────────────────────────────────

function handleBackendMessage(msg) {
  switch (msg.type) {
    case 'execute_action':
      executeActionOnWidget(msg);
      break;

    case 'agent_event':
      broadcastToPopup({
        type: 'agent_event',
        caseId: msg.caseId,
        event: msg.event,
      });
      break;

    case 'request_widget_state':
      sendWidgetStateToBackend(msg.caseId, msg.tabId);
      break;

    case 'case_paused':
      broadcastToPopup({
        type: 'agent_event',
        caseId: msg.caseId,
        event: {
          type: 'paused',
          reason: msg.reason,
          needsInput: msg.needsInput,
          inputPrompt: msg.inputPrompt,
        },
      });
      break;

    case 'case_completed':
      broadcastToPopup({
        type: 'agent_event',
        caseId: msg.caseId,
        event: { type: 'completed', summary: msg.summary },
      });
      activeCaseId = null;
      break;

    case 'case_error':
      broadcastToPopup({
        type: 'agent_event',
        caseId: msg.caseId,
        event: { type: 'error', message: msg.message },
      });
      activeCaseId = null;
      break;

    default:
      console.log('[Resolve] Unknown backend message:', msg.type);
  }
}

// ─── Execute actions on the chat widget via content script ──────────────────

async function executeActionOnWidget(msg) {
  const { caseId, actionId, action } = msg;

  // Find the tab with the active widget
  let targetTab = null;
  let targetFrameId = 0;

  for (const [tabId, info] of widgetRegistry) {
    targetTab = tabId;
    targetFrameId = info.frameId;
    break;
  }

  if (targetTab === null) {
    sendToBackend({
      type: 'action_result',
      caseId,
      actionId,
      success: false,
      error: 'No widget found',
    });
    return;
  }

  try {
    const response = await chrome.tabs.sendMessage(
      targetTab,
      { type: 'execute_action', action, actionId },
      { frameId: targetFrameId }
    );

    sendToBackend({
      type: 'action_result',
      caseId,
      actionId,
      success: response?.success ?? false,
      error: response?.error,
    });

    // After action execution, wait a moment then send fresh widget state
    setTimeout(() => sendWidgetStateToBackend(caseId, targetTab), 1500);
  } catch (err) {
    sendToBackend({
      type: 'action_result',
      caseId,
      actionId,
      success: false,
      error: err.message || 'Failed to send action to content script',
    });
  }
}

async function sendWidgetStateToBackend(caseId, tabId) {
  const info = widgetRegistry.get(tabId ?? [...widgetRegistry.keys()][0]);
  if (!info) return;

  const targetTab = tabId ?? [...widgetRegistry.keys()][0];
  try {
    const state = await chrome.tabs.sendMessage(
      targetTab,
      { type: 'get_widget_state' },
      { frameId: info.frameId }
    );

    if (state) {
      sendToBackend({
        type: 'widget_state',
        caseId,
        state,
      });
    }
  } catch (err) {
    console.error('[Resolve] Failed to get widget state:', err);
  }
}

// ─── Handle messages from popup and content scripts ─────────────────────────

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  switch (msg.type) {
    // ── From content script ──
    case 'widget_found': {
      const tabId = sender.tab?.id;
      const frameId = sender.frameId ?? 0;
      if (tabId != null) {
        widgetRegistry.set(tabId, {
          frameId,
          provider: msg.provider,
          profile: msg.profile,
        });
        broadcastToPopup({
          type: 'widget_detected',
          provider: msg.provider,
          tabId,
        });
      }
      sendResponse({ ok: true });
      return false;
    }

    case 'widget_lost': {
      const tabId = sender.tab?.id;
      if (tabId != null) {
        widgetRegistry.delete(tabId);
      }
      sendResponse({ ok: true });
      return false;
    }

    case 'widget_state_update': {
      if (activeCaseId) {
        sendToBackend({
          type: 'widget_state',
          caseId: activeCaseId,
          state: msg.state,
        });
      }
      sendResponse({ ok: true });
      return false;
    }

    // ── From popup ──
    case 'get_widget_status': {
      const info = widgetRegistry.get(msg.tabId);
      sendResponse({
        widgetFound: !!info,
        provider: info?.provider ?? null,
      });
      return false;
    }

    case 'get_connection_status': {
      sendResponse({ connected: wsConnected });
      return false;
    }

    case 'start_case': {
      handleStartCase(msg.payload).then(sendResponse);
      return true; // async
    }

    case 'pause_case': {
      sendToBackend({ type: 'pause_case', caseId: msg.caseId });
      sendResponse({ ok: true });
      return false;
    }

    case 'stop_case': {
      sendToBackend({ type: 'stop_case', caseId: msg.caseId });
      activeCaseId = null;
      sendResponse({ ok: true });
      return false;
    }

    case 'approve_case': {
      sendToBackend({
        type: 'approve_case',
        caseId: msg.caseId,
        userInput: msg.userInput,
      });
      sendResponse({ ok: true });
      return false;
    }
  }

  return false;
});

// ─── Start case via REST + switch to WS ─────────────────────────────────────

async function handleStartCase(payload) {
  try {
    // Ensure WS is connected
    connectWs();

    // Find the active widget's tab info
    let widgetTabId = null;
    let widgetInfo = null;
    for (const [tabId, info] of widgetRegistry) {
      widgetTabId = tabId;
      widgetInfo = info;
      break;
    }

    // Create case via REST
    const createRes = await fetch(`${BACKEND_URL}/api/cases`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        customerName: 'User', // Extension user
        issue: payload.issue,
        desiredOutcome: payload.desiredOutcome,
        files: payload.files,
      }),
    });

    if (!createRes.ok) {
      const err = await createRes.json().catch(() => ({}));
      return { error: err.error || `Server error: ${createRes.status}` };
    }

    const caseData = await createRes.json();
    activeCaseId = caseData.id;

    // Start the browser_extension channel via REST
    const startRes = await fetch(`${BACKEND_URL}/api/cases/${caseData.id}/start-extension`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        widgetProvider: widgetInfo?.provider ?? 'unknown',
        tabId: widgetTabId,
      }),
    });

    if (!startRes.ok) {
      const err = await startRes.json().catch(() => ({}));
      return { error: err.error || 'Failed to start agent' };
    }

    // Send initial widget state over WS
    if (widgetTabId != null) {
      setTimeout(() => sendWidgetStateToBackend(caseData.id, widgetTabId), 500);
    }

    return { caseId: caseData.id };
  } catch (err) {
    return { error: err.message || 'Connection failed' };
  }
}

// ─── Tab lifecycle ──────────────────────────────────────────────────────────

chrome.tabs.onRemoved.addListener((tabId) => {
  widgetRegistry.delete(tabId);
});

chrome.tabs.onUpdated.addListener((tabId, changeInfo) => {
  if (changeInfo.status === 'loading') {
    widgetRegistry.delete(tabId);
  }
});

// ─── Broadcast helpers ──────────────────────────────────────────────────────

function broadcastToPopup(msg) {
  chrome.runtime.sendMessage(msg).catch(() => {
    // Popup might not be open
  });
}

// ─── Init ───────────────────────────────────────────────────────────────────

connectWs();
