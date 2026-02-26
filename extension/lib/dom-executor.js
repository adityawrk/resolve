/**
 * Resolve - DOM Executor
 *
 * Executes actions on chat widget DOM elements: typing messages, clicking buttons,
 * uploading files. Handles the quirks of different widget implementations
 * (contenteditable, React controlled inputs, etc.).
 */

// eslint-disable-next-line no-var
var ResolveExecutor = (() => {
  /**
   * Execute an action on the widget.
   * @param {object} action - The action to perform
   * @param {object} profile - The widget profile
   * @returns {Promise<{success: boolean, error?: string}>}
   */
  async function execute(action, profile) {
    switch (action.type) {
      case 'type_message':
        return await typeAndSend(action.text, profile);
      case 'click_button':
        return await clickButton(action.buttonLabel, action.selector, profile);
      case 'upload_file':
        return await uploadFile(action.fileData, action.fileName, profile);
      case 'wait':
        return await waitForChange(action.durationMs || 3000, profile);
      default:
        return { success: false, error: `Unknown action type: ${action.type}` };
    }
  }

  /**
   * Type a message into the chat input and send it.
   */
  async function typeAndSend(text, profile) {
    const selectors = profile.selectors;
    const input = findInput(selectors);
    if (!input) {
      return { success: false, error: 'Chat input field not found' };
    }

    // Focus the input
    input.focus();
    await sleep(100);

    // Set the value based on input type
    const isContentEditable = input.getAttribute('contenteditable') === 'true';
    if (isContentEditable) {
      setContentEditableValue(input, text);
    } else {
      setInputValue(input, text);
    }

    await sleep(200);

    // Send the message
    const sendMethod = profile.sendMethod || 'auto';
    const sent = await sendMessage(input, selectors, sendMethod);

    if (!sent) {
      return { success: false, error: 'Failed to send message (send button not found and Enter key failed)' };
    }

    return { success: true };
  }

  /**
   * Set value on a contenteditable element.
   * Uses execCommand for maximum compatibility with frameworks.
   */
  function setContentEditableValue(element, text) {
    element.focus();

    // Clear existing content
    if (document.queryCommandSupported('selectAll')) {
      document.execCommand('selectAll', false, null);
      document.execCommand('delete', false, null);
    } else {
      element.textContent = '';
    }

    // Insert text via execCommand (triggers React/Vue/Angular change detection)
    document.execCommand('insertText', false, text);

    // Fire input event
    element.dispatchEvent(new Event('input', { bubbles: true }));
    element.dispatchEvent(new Event('change', { bubbles: true }));
  }

  /**
   * Set value on a regular input/textarea.
   * Uses the native setter to bypass React's controlled input mechanism.
   */
  function setInputValue(element, text) {
    element.focus();

    // Use native setter to bypass React
    const descriptor =
      Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value') ||
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');

    if (descriptor && descriptor.set) {
      descriptor.set.call(element, text);
    } else {
      element.value = text;
    }

    // Fire all events that frameworks might listen to
    element.dispatchEvent(new Event('input', { bubbles: true }));
    element.dispatchEvent(new Event('change', { bubbles: true }));
    element.dispatchEvent(new KeyboardEvent('keydown', { bubbles: true }));
    element.dispatchEvent(new KeyboardEvent('keyup', { bubbles: true }));
  }

  /**
   * Send the message using the appropriate method.
   */
  async function sendMessage(input, selectors, method) {
    if (method === 'click' || method === 'auto') {
      const sendBtn = findSendButton(selectors);
      if (sendBtn) {
        simulateClick(sendBtn);
        return true;
      }
      if (method === 'click') return false;
    }

    if (method === 'enter' || method === 'auto') {
      // Simulate Enter key
      const enterEvent = new KeyboardEvent('keydown', {
        key: 'Enter',
        code: 'Enter',
        keyCode: 13,
        which: 13,
        bubbles: true,
        cancelable: true,
      });
      input.dispatchEvent(enterEvent);

      // Some widgets need keypress too
      const keypressEvent = new KeyboardEvent('keypress', {
        key: 'Enter',
        code: 'Enter',
        keyCode: 13,
        which: 13,
        bubbles: true,
        cancelable: true,
      });
      input.dispatchEvent(keypressEvent);

      const keyupEvent = new KeyboardEvent('keyup', {
        key: 'Enter',
        code: 'Enter',
        keyCode: 13,
        which: 13,
        bubbles: true,
      });
      input.dispatchEvent(keyupEvent);

      return true;
    }

    return false;
  }

  /**
   * Click a button by label or selector.
   */
  async function clickButton(label, selector, profile) {
    let button = null;

    // Try direct selector first
    if (selector) {
      try {
        button = document.querySelector(selector);
      } catch {
        // Invalid selector, fall through
      }
    }

    // Try finding by label text
    if (!button && label) {
      button = findButtonByLabel(label, profile.selectors);
    }

    if (!button) {
      return { success: false, error: `Button not found: "${label}"` };
    }

    simulateClick(button);
    return { success: true };
  }

  /**
   * Find a button by its label text.
   */
  function findButtonByLabel(label, selectors) {
    const normalizedLabel = label.toLowerCase().trim();

    // Search quick reply buttons first
    const quickReplies = selectors.quickReplyButtons
      ? Array.from(document.querySelectorAll(selectors.quickReplyButtons))
      : [];

    for (const btn of quickReplies) {
      if (btn.textContent?.toLowerCase().trim() === normalizedLabel) {
        return btn;
      }
    }

    // Search all visible buttons
    const allButtons = document.querySelectorAll('button, [role="button"], a[class*="btn"]');
    for (const btn of allButtons) {
      const btnText = btn.textContent?.toLowerCase().trim();
      if (btnText === normalizedLabel || btnText?.includes(normalizedLabel)) {
        return btn;
      }
    }

    // Fuzzy match: find closest match
    let bestMatch = null;
    let bestScore = 0;
    for (const btn of allButtons) {
      const btnText = btn.textContent?.toLowerCase().trim() || '';
      const score = similarityScore(normalizedLabel, btnText);
      if (score > bestScore && score > 0.6) {
        bestScore = score;
        bestMatch = btn;
      }
    }

    return bestMatch;
  }

  /**
   * Upload a file to the chat widget.
   */
  async function uploadFile(base64Data, fileName, profile) {
    // Find the file input
    const fileInput = findFileInput(profile.selectors);
    if (!fileInput) {
      return { success: false, error: 'File upload input not found' };
    }

    try {
      // Convert base64 to File object
      const byteString = atob(base64Data);
      const ab = new ArrayBuffer(byteString.length);
      const ia = new Uint8Array(ab);
      for (let i = 0; i < byteString.length; i++) {
        ia[i] = byteString.charCodeAt(i);
      }
      const mimeType = guessMimeType(fileName);
      const file = new File([ab], fileName, { type: mimeType });

      // Set files using DataTransfer
      const dt = new DataTransfer();
      dt.items.add(file);
      fileInput.files = dt.files;

      // Dispatch change event
      fileInput.dispatchEvent(new Event('change', { bubbles: true }));
      fileInput.dispatchEvent(new Event('input', { bubbles: true }));

      return { success: true };
    } catch (err) {
      return { success: false, error: `File upload failed: ${err.message}` };
    }
  }

  /**
   * Wait for DOM changes in the chat container (new messages, etc.).
   */
  function waitForChange(durationMs, profile) {
    return new Promise((resolve) => {
      const container = document.querySelector(
        profile.selectors.chatContainer || profile.selectors.messageList
      );

      if (!container) {
        setTimeout(() => resolve({ success: true, changed: false }), durationMs);
        return;
      }

      let changed = false;
      const observer = new MutationObserver(() => {
        changed = true;
      });

      observer.observe(container, {
        childList: true,
        subtree: true,
        characterData: true,
      });

      setTimeout(() => {
        observer.disconnect();
        resolve({ success: true, changed });
      }, durationMs);
    });
  }

  // ─── Utility functions ──────────────────────────────────────────────────

  function findInput(selectors) {
    const candidates = [
      selectors.inputField,
      '[contenteditable="true"]',
      'textarea:not([readonly])',
      'input[type="text"]:not([readonly])',
    ];

    for (const sel of candidates) {
      if (!sel) continue;
      try {
        const el = document.querySelector(sel);
        if (el && isVisible(el)) return el;
      } catch {
        continue;
      }
    }
    return null;
  }

  function findSendButton(selectors) {
    const candidates = [
      selectors.sendButton,
      'button[aria-label*="send" i]',
      'button[aria-label*="Send"]',
      'button[type="submit"]',
      'button[class*="send"]',
    ];

    for (const sel of candidates) {
      if (!sel) continue;
      try {
        const el = document.querySelector(sel);
        if (el && isVisible(el) && !el.disabled) return el;
      } catch {
        continue;
      }
    }
    return null;
  }

  function findFileInput(selectors) {
    const candidates = [
      selectors.fileUploadTrigger,
      'input[type="file"]',
    ];

    for (const sel of candidates) {
      if (!sel) continue;
      try {
        // File inputs might be hidden, so don't check visibility
        const el = document.querySelector(sel);
        if (el) return el;
      } catch {
        continue;
      }
    }
    return null;
  }

  /**
   * Simulate a realistic click on an element.
   */
  function simulateClick(element) {
    const rect = element.getBoundingClientRect();
    const x = rect.left + rect.width / 2;
    const y = rect.top + rect.height / 2;

    const eventOpts = { bubbles: true, cancelable: true, clientX: x, clientY: y };

    element.dispatchEvent(new PointerEvent('pointerdown', eventOpts));
    element.dispatchEvent(new MouseEvent('mousedown', eventOpts));
    element.dispatchEvent(new PointerEvent('pointerup', eventOpts));
    element.dispatchEvent(new MouseEvent('mouseup', eventOpts));
    element.dispatchEvent(new MouseEvent('click', eventOpts));
    element.focus();
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

  function guessMimeType(filename) {
    const ext = filename.split('.').pop()?.toLowerCase();
    const types = {
      jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png',
      gif: 'image/gif', webp: 'image/webp', pdf: 'application/pdf',
      txt: 'text/plain', doc: 'application/msword',
    };
    return types[ext] || 'application/octet-stream';
  }

  /**
   * Simple string similarity score (Dice coefficient).
   */
  function similarityScore(a, b) {
    if (a === b) return 1;
    if (a.length < 2 || b.length < 2) return 0;
    const bigrams = new Map();
    for (let i = 0; i < a.length - 1; i++) {
      const bi = a.substring(i, i + 2);
      bigrams.set(bi, (bigrams.get(bi) || 0) + 1);
    }
    let intersect = 0;
    for (let i = 0; i < b.length - 1; i++) {
      const bi = b.substring(i, i + 2);
      const count = bigrams.get(bi) || 0;
      if (count > 0) {
        bigrams.set(bi, count - 1);
        intersect++;
      }
    }
    return (2 * intersect) / (a.length + b.length - 2);
  }

  function sleep(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }

  return { execute };
})();
