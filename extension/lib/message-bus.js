/**
 * Resolve - Message Bus
 * Thin wrapper over chrome.runtime messaging for content scripts.
 */

// eslint-disable-next-line no-var
var ResolveBus = (() => {
  function send(type, data = {}) {
    return chrome.runtime.sendMessage({ type, ...data }).catch(() => null);
  }

  function onMessage(handler) {
    chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
      const result = handler(msg, sender);
      if (result instanceof Promise) {
        result.then(sendResponse);
        return true;
      }
      if (result !== undefined) {
        sendResponse(result);
      }
      return false;
    });
  }

  return { send, onMessage };
})();
