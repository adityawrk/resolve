/**
 * Resolve - DOM Extractor
 *
 * Extracts normalized chat widget state from the DOM.
 * Given a widget profile, reads messages, buttons, input state, etc.
 */

// eslint-disable-next-line no-var
var ResolveExtractor = (() => {
  /**
   * Extract the full widget state as a normalized object.
   * @param {object} profile - The widget profile with selectors
   * @returns {object} Normalized widget state
   */
  function extractState(profile) {
    const selectors = profile.selectors;

    return {
      provider: profile.id,
      timestamp: Date.now(),
      messages: extractMessages(selectors),
      buttons: extractButtons(selectors),
      inputField: extractInputState(selectors),
      typingIndicator: isTyping(selectors),
      url: window.location.href,
    };
  }

  /**
   * Extract chat messages from the widget.
   */
  function extractMessages(selectors) {
    const messages = [];

    // Try messageItem selector first
    const items = querySelectorAllSafe(selectors.messageItem);
    for (const item of items) {
      const text = extractTextContent(item, selectors.messageText);
      if (!text) continue;

      const sender = classifySender(item);
      messages.push({
        sender,
        text: text.trim(),
        timestamp: extractTimestamp(item),
      });
    }

    // If no messages found via items, try extracting from the message list directly
    if (messages.length === 0) {
      const list = document.querySelector(selectors.messageList);
      if (list) {
        const childMessages = extractMessagesFromContainer(list);
        messages.push(...childMessages);
      }
    }

    return messages;
  }

  /**
   * Extract text from a message element, preferring the messageText sub-selector.
   */
  function extractTextContent(element, messageTextSelector) {
    if (messageTextSelector) {
      const textEl = element.querySelector(messageTextSelector);
      if (textEl) return textEl.textContent;
    }
    // Fallback: get direct text, excluding nested interactive elements
    return getCleanText(element);
  }

  /**
   * Get clean text content, stripping buttons and interactive elements.
   */
  function getCleanText(element) {
    const clone = element.cloneNode(true);
    clone.querySelectorAll('button, input, select, [role="button"]').forEach((el) => el.remove());
    return clone.textContent?.trim() || '';
  }

  /**
   * Classify whether a message is from the user (customer), the support agent, or system.
   */
  function classifySender(element) {
    const html = element.outerHTML.toLowerCase();
    const classes = (element.className || '').toLowerCase();
    const dataAttrs = Array.from(element.attributes)
      .filter((a) => a.name.startsWith('data-'))
      .map((a) => `${a.name}=${a.value}`)
      .join(' ')
      .toLowerCase();

    const combined = `${classes} ${dataAttrs} ${html.slice(0, 200)}`;

    // Check for user/customer indicators
    if (
      combined.includes('user') ||
      combined.includes('customer') ||
      combined.includes('self') ||
      combined.includes('outgoing') ||
      combined.includes('sent') ||
      combined.includes('right') ||
      combined.includes('own')
    ) {
      return 'user';
    }

    // Check for agent/bot indicators
    if (
      combined.includes('agent') ||
      combined.includes('operator') ||
      combined.includes('bot') ||
      combined.includes('incoming') ||
      combined.includes('received') ||
      combined.includes('left') ||
      combined.includes('admin') ||
      combined.includes('support')
    ) {
      return 'agent';
    }

    // Check for system indicators
    if (
      combined.includes('system') ||
      combined.includes('notice') ||
      combined.includes('info') ||
      combined.includes('timestamp') ||
      combined.includes('divider')
    ) {
      return 'system';
    }

    return 'unknown';
  }

  /**
   * Extract available buttons/quick replies from the widget.
   */
  function extractButtons(selectors) {
    const buttons = [];

    // Quick reply buttons
    const quickReplies = querySelectorAllSafe(selectors.quickReplyButtons);
    for (const btn of quickReplies) {
      const label = btn.textContent?.trim();
      if (label && !btn.disabled) {
        buttons.push({
          label,
          type: 'quick_reply',
          selector: generateUniqueSelector(btn),
        });
      }
    }

    // Also look for general interactive buttons in the chat area
    const chatContainer = document.querySelector(selectors.chatContainer);
    if (chatContainer) {
      const allButtons = chatContainer.querySelectorAll(
        'button:not([disabled]), [role="button"]:not([disabled]), a[class*="btn"], a[class*="button"]'
      );
      for (const btn of allButtons) {
        const label = btn.textContent?.trim();
        if (label && label.length < 60 && !buttons.some((b) => b.label === label)) {
          buttons.push({
            label,
            type: 'action',
            selector: generateUniqueSelector(btn),
          });
        }
      }
    }

    return buttons;
  }

  /**
   * Extract the state of the chat input field.
   */
  function extractInputState(selectors) {
    const input = findInputField(selectors);
    if (!input) return { found: false, value: '', placeholder: '' };

    const isContentEditable = input.getAttribute('contenteditable') === 'true';
    return {
      found: true,
      value: isContentEditable ? input.textContent?.trim() || '' : input.value || '',
      placeholder: input.getAttribute('placeholder') || input.getAttribute('aria-label') || '',
      type: isContentEditable ? 'contenteditable' : input.tagName.toLowerCase(),
      selector: generateUniqueSelector(input),
    };
  }

  /**
   * Find the chat input field.
   */
  function findInputField(selectors) {
    // Try profile's selector first
    const profileInput = document.querySelector(selectors.inputField);
    if (profileInput && isVisible(profileInput)) return profileInput;

    // Fallback: common input patterns
    const fallbacks = [
      '[contenteditable="true"]',
      'textarea:not([readonly])',
      'input[type="text"]:not([readonly])',
    ];
    for (const sel of fallbacks) {
      const el = document.querySelector(sel);
      if (el && isVisible(el)) return el;
    }

    return null;
  }

  /**
   * Check if the typing indicator is visible.
   */
  function isTyping(selectors) {
    const indicator = document.querySelector(selectors.typingIndicator);
    return indicator ? isVisible(indicator) : false;
  }

  /**
   * Extract messages from a container by analyzing child elements.
   */
  function extractMessagesFromContainer(container) {
    const messages = [];
    const children = container.children;

    for (const child of children) {
      const text = getCleanText(child);
      if (text && text.length > 1) {
        messages.push({
          sender: classifySender(child),
          text,
          timestamp: extractTimestamp(child),
        });
      }
    }

    return messages;
  }

  /**
   * Try to extract a timestamp from a message element.
   */
  function extractTimestamp(element) {
    const timeEl = element.querySelector('time, [datetime], [class*="time"], [class*="date"]');
    if (timeEl) {
      return timeEl.getAttribute('datetime') || timeEl.textContent?.trim() || null;
    }
    return null;
  }

  // ─── Utility helpers ────────────────────────────────────────────────────

  function querySelectorAllSafe(selector) {
    if (!selector) return [];
    try {
      return Array.from(document.querySelectorAll(selector));
    } catch {
      return [];
    }
  }

  function isVisible(el) {
    if (!el) return false;
    const style = window.getComputedStyle(el);
    return (
      style.display !== 'none' &&
      style.visibility !== 'hidden' &&
      style.opacity !== '0' &&
      el.offsetWidth > 0 &&
      el.offsetHeight > 0
    );
  }

  /**
   * Generate a unique CSS selector for an element (for later targeting).
   */
  function generateUniqueSelector(el) {
    if (el.id) return `#${CSS.escape(el.id)}`;

    const parts = [];
    let current = el;
    while (current && current !== document.body && parts.length < 5) {
      let selector = current.tagName.toLowerCase();
      if (current.id) {
        parts.unshift(`#${CSS.escape(current.id)}`);
        break;
      }
      if (current.className && typeof current.className === 'string') {
        const mainClass = current.className.split(/\s+/).filter((c) => c && !c.includes('--'))[0];
        if (mainClass) selector += `.${CSS.escape(mainClass)}`;
      }
      const parent = current.parentElement;
      if (parent) {
        const siblings = Array.from(parent.children).filter((c) => c.tagName === current.tagName);
        if (siblings.length > 1) {
          const idx = siblings.indexOf(current) + 1;
          selector += `:nth-of-type(${idx})`;
        }
      }
      parts.unshift(selector);
      current = current.parentElement;
    }
    return parts.join(' > ');
  }

  return { extractState };
})();
