/**
 * Resolve - Widget Detector
 *
 * Detects known customer support chat widgets on the page.
 * Uses a profile-based approach: each provider has known DOM signatures.
 * Falls back to heuristic detection for unknown widgets.
 */

// eslint-disable-next-line no-var
var ResolveDetector = (() => {
  /**
   * Each profile defines how to detect and identify a specific chat widget provider.
   * Selectors are CSS selectors. hostIndicators are patterns matched against script src attributes.
   */
  const profiles = [
    {
      id: 'intercom',
      name: 'Intercom',
      detect: {
        iframeSelectors: [
          'iframe[name="intercom-messenger-frame"]',
          'iframe[name="intercom-borderless-frame"]',
          'iframe[name="intercom-launcher-frame"]',
        ],
        domMarkers: [
          '#intercom-container',
          '#intercom-frame',
          '.intercom-messenger',
          '[class*="intercom"]',
        ],
        hostIndicators: ['widget.intercom.io', 'js.intercomcdn.com'],
      },
      selectors: {
        chatContainer: '[class*="conversation-container"], [class*="MessengerConversation"]',
        messageList: '[class*="conversation-parts"], [class*="MessageList"]',
        messageItem: '[class*="conversation-part"], [class*="Message__"]',
        messageText: '[class*="part-body"], [class*="MessageBody"]',
        inputField: '[contenteditable="true"], [class*="composer"] [contenteditable]',
        sendButton: '[class*="composer"] button[type="submit"], [aria-label="Send"]',
        quickReplyButtons: '[class*="quick-reply"] button, [class*="QuickReply"] button',
        typingIndicator: '[class*="typing-indicator"], [class*="TypingIndicator"]',
      },
      inputMethod: 'contenteditable',
      sendMethod: 'enter',
    },
    {
      id: 'zendesk',
      name: 'Zendesk',
      detect: {
        iframeSelectors: [
          'iframe#webWidget',
          'iframe[data-product="web_widget"]',
          'iframe[title="Messaging window"]',
          'iframe[title*="Number of unread"]',
        ],
        domMarkers: [
          '#webWidget',
          '[data-garden-container-id="containers.messenger"]',
          '[class*="zEWidget"]',
        ],
        hostIndicators: ['static.zdassets.com', 'ekr.zdassets.com'],
      },
      selectors: {
        chatContainer: '[data-garden-container-id="containers.messenger"], [role="log"]',
        messageList: '[role="log"], [class*="MessageLog"]',
        messageItem: '[class*="MessageBubble"], [data-testid*="message"]',
        messageText: '[class*="MessageBubble"] [dir], [class*="message-text"]',
        inputField: 'textarea[aria-label*="message"], textarea[placeholder*="message"], [contenteditable="true"]',
        sendButton: 'button[aria-label="Send message"], button[aria-label="Send"]',
        quickReplyButtons: '[class*="quick-replies"] button, button[class*="QuickReply"]',
        typingIndicator: '[class*="typing"], [aria-label*="typing"]',
      },
      inputMethod: 'textarea',
      sendMethod: 'enter',
    },
    {
      id: 'freshchat',
      name: 'Freshchat',
      detect: {
        iframeSelectors: [
          'iframe#fc_frame',
          'iframe[id*="freshworks"]',
        ],
        domMarkers: [
          '#freshworks-container',
          '#fc_frame',
          '[id*="freshchat"]',
          '.freshworks_widget',
        ],
        hostIndicators: ['wchat.freshchat.com', 'fw-cdn.com'],
      },
      selectors: {
        chatContainer: '[class*="chat-messages"], [class*="conversation-wrap"]',
        messageList: '[class*="chat-messages"], [class*="message-list"]',
        messageItem: '[class*="chat-message"], [class*="message-item"]',
        messageText: '[class*="message-text"], [class*="msg-content"]',
        inputField: 'textarea[class*="input"], input[class*="chat-input"]',
        sendButton: 'button[class*="send"], [class*="send-btn"]',
        quickReplyButtons: '[class*="quick-action"] button',
        typingIndicator: '[class*="typing"]',
      },
      inputMethod: 'textarea',
      sendMethod: 'click',
    },
    {
      id: 'drift',
      name: 'Drift',
      detect: {
        iframeSelectors: [
          'iframe#drift-frame',
          'iframe[title*="Drift"]',
          'iframe[src*="drift.com"]',
        ],
        domMarkers: [
          '#drift-widget',
          '#drift-frame-controller',
          '#drift-frame',
        ],
        hostIndicators: ['js.driftt.com', 'drift.com'],
      },
      selectors: {
        chatContainer: '[class*="conversation-container"]',
        messageList: '[class*="message-list"]',
        messageItem: '[class*="message-item"]',
        messageText: '[class*="message-body"]',
        inputField: 'textarea, [contenteditable="true"]',
        sendButton: 'button[class*="send"]',
        quickReplyButtons: '[class*="quick-reply"] button',
        typingIndicator: '[class*="typing"]',
      },
      inputMethod: 'textarea',
      sendMethod: 'enter',
    },
    {
      id: 'tawk',
      name: 'Tawk.to',
      detect: {
        iframeSelectors: [
          'iframe[title*="chat widget"]',
          'iframe[src*="tawk.to"]',
          'iframe[title="chat widget"]',
        ],
        domMarkers: [
          '#tawk-bubble-container',
          '[class*="tawk-"]',
          '#tawkchat-container',
        ],
        hostIndicators: ['embed.tawk.to'],
      },
      selectors: {
        chatContainer: '[class*="tawk-chat-panel"]',
        messageList: '[class*="message-list"], [class*="tawk-messages"]',
        messageItem: '[class*="tawk-message"]',
        messageText: '[class*="tawk-message-text"]',
        inputField: 'textarea[class*="tawk"], textarea',
        sendButton: 'button[class*="tawk-send"], button[class*="send"]',
        quickReplyButtons: '[class*="quick-reply"] button',
        typingIndicator: '[class*="typing"]',
      },
      inputMethod: 'textarea',
      sendMethod: 'click',
    },
    {
      id: 'crisp',
      name: 'Crisp',
      detect: {
        iframeSelectors: [
          'iframe[data-from-crisp]',
          'iframe[title*="Crisp"]',
        ],
        domMarkers: [
          '#crisp-chatbox',
          '.crisp-client',
          '[class*="crisp-"]',
        ],
        hostIndicators: ['client.crisp.chat'],
      },
      selectors: {
        chatContainer: '[class*="cc-window"]',
        messageList: '[class*="cc-messages"]',
        messageItem: '[class*="cc-message"]',
        messageText: '[class*="cc-message-text"]',
        inputField: '[contenteditable="true"], textarea[class*="cc-"]',
        sendButton: 'button[class*="cc-send"], [data-action="send"]',
        quickReplyButtons: '[class*="cc-picker-action"] button',
        typingIndicator: '[class*="cc-typing"]',
      },
      inputMethod: 'contenteditable',
      sendMethod: 'enter',
    },
    {
      id: 'hubspot',
      name: 'HubSpot',
      detect: {
        iframeSelectors: [
          '#hubspot-messages-iframe-container iframe',
          'iframe[src*="hubspot.com"][src*="messages"]',
        ],
        domMarkers: [
          '#hubspot-messages-iframe-container',
          '#hs-chat-open',
        ],
        hostIndicators: ['js.hs-scripts.com', 'js.usemessages.com'],
      },
      selectors: {
        chatContainer: '[class*="conversation-container"]',
        messageList: '[class*="messages-list"]',
        messageItem: '[class*="message-row"]',
        messageText: '[class*="message-body"]',
        inputField: '[contenteditable="true"], textarea',
        sendButton: 'button[class*="send"]',
        quickReplyButtons: '[class*="quick-reply"] button',
        typingIndicator: '[class*="typing"]',
      },
      inputMethod: 'contenteditable',
      sendMethod: 'enter',
    },
    {
      id: 'livechat',
      name: 'LiveChat',
      detect: {
        iframeSelectors: [
          'iframe[src*="livechat"]',
          'iframe[title*="LiveChat"]',
          '#chat-widget iframe',
        ],
        domMarkers: [
          '#chat-widget-container',
          '#chat-widget',
          '[class*="livechat"]',
        ],
        hostIndicators: ['cdn.livechatinc.com', 'connect.livechatinc.com'],
      },
      selectors: {
        chatContainer: '[class*="chat-container"]',
        messageList: '[class*="message-list"]',
        messageItem: '[class*="message-item"]',
        messageText: '[class*="message-text"]',
        inputField: 'textarea, [contenteditable="true"]',
        sendButton: 'button[class*="send"]',
        quickReplyButtons: '[class*="quick-reply"] button',
        typingIndicator: '[class*="typing"]',
      },
      inputMethod: 'textarea',
      sendMethod: 'enter',
    },
  ];

  /**
   * Detect a known widget in the current document context.
   * Since content scripts run with all_frames:true, this checks
   * whether the current frame IS a widget iframe or contains one.
   */
  function detect() {
    // First check: are we inside a widget iframe?
    const insideResult = detectFromInside();
    if (insideResult) return insideResult;

    // Second check: is there a widget on this page (top frame or same-origin iframe)?
    const onPageResult = detectOnPage();
    if (onPageResult) return onPageResult;

    // Third: heuristic fallback
    return detectHeuristic();
  }

  /** Check if the current frame's URL or DOM matches a known widget (we're inside the iframe). */
  function detectFromInside() {
    if (window === window.top) return null; // Not in an iframe

    const currentUrl = window.location.href;
    const currentOrigin = window.location.origin;

    for (const profile of profiles) {
      // Check URL against host indicators
      for (const host of profile.detect.hostIndicators) {
        if (currentUrl.includes(host) || currentOrigin.includes(host)) {
          return { profile, context: 'inside_iframe' };
        }
      }

      // Check DOM markers inside this frame
      for (const selector of profile.detect.domMarkers) {
        if (document.querySelector(selector)) {
          return { profile, context: 'inside_iframe' };
        }
      }
    }

    return null;
  }

  /** Check if a known widget exists on the current page (inspecting the top-level DOM). */
  function detectOnPage() {
    for (const profile of profiles) {
      // Check for widget iframes
      for (const selector of profile.detect.iframeSelectors) {
        if (document.querySelector(selector)) {
          return { profile, context: 'has_iframe', iframeSelector: selector };
        }
      }

      // Check for DOM markers (for widgets that don't use iframes)
      for (const selector of profile.detect.domMarkers) {
        if (document.querySelector(selector)) {
          return { profile, context: 'same_origin' };
        }
      }

      // Check for script tags
      const scripts = document.querySelectorAll('script[src]');
      for (const script of scripts) {
        for (const host of profile.detect.hostIndicators) {
          if (script.src.includes(host)) {
            return { profile, context: 'script_detected' };
          }
        }
      }
    }

    return null;
  }

  /** Heuristic fallback: detect chat-like elements when no known profile matches. */
  function detectHeuristic() {
    // Look for floating elements in bottom-right that look like chat widgets
    const candidates = document.querySelectorAll(
      'iframe[src*="chat"], iframe[src*="widget"], iframe[src*="messenger"], ' +
      'iframe[src*="support"], div[class*="chat-widget"], div[class*="live-chat"]'
    );

    if (candidates.length > 0) {
      return {
        profile: {
          id: 'generic',
          name: 'Chat Widget',
          detect: {},
          selectors: {
            chatContainer: '[class*="chat"], [role="log"], [class*="message"]',
            messageList: '[class*="message-list"], [role="log"]',
            messageItem: '[class*="message"]',
            messageText: '[class*="message-text"], [class*="message-body"], [class*="content"]',
            inputField: 'textarea, [contenteditable="true"], input[type="text"]',
            sendButton: 'button[class*="send"], button[type="submit"]',
            quickReplyButtons: 'button[class*="quick"], button[class*="reply"], button[class*="option"]',
            typingIndicator: '[class*="typing"]',
          },
          inputMethod: 'auto',
          sendMethod: 'auto',
        },
        context: 'heuristic',
      };
    }

    return null;
  }

  return { detect, profiles };
})();
