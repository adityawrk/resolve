/**
 * Resolve - Content Script (main entry)
 *
 * Injected into every frame. Detects chat widgets, extracts state,
 * and executes actions commanded by the background service worker.
 */

(() => {
  // Guard against double-injection
  if (window.__resolveContentLoaded) return;
  window.__resolveContentLoaded = true;

  let detectedProfile = null;
  let observerActive = false;
  let messageObserver = null;

  // ─── Widget detection ───────────────────────────────────────────────────

  function runDetection() {
    const result = ResolveDetector.detect();
    if (!result) return;

    detectedProfile = result.profile;

    // Notify the background service worker
    ResolveBus.send('widget_found', {
      provider: result.profile.name,
      profileId: result.profile.id,
      context: result.context,
    });

    // Start observing for new messages
    startObserver();
  }

  // Run detection after a short delay (widgets often load asynchronously)
  setTimeout(runDetection, 1000);
  setTimeout(runDetection, 3000);
  setTimeout(runDetection, 6000);

  // Also observe DOM changes for late-loading widgets
  const bodyObserver = new MutationObserver(() => {
    if (!detectedProfile) {
      runDetection();
    }
  });

  if (document.body) {
    bodyObserver.observe(document.body, { childList: true, subtree: true });
    // Stop watching after 15 seconds to save resources
    setTimeout(() => {
      if (!detectedProfile) bodyObserver.disconnect();
    }, 15000);
  }

  // ─── Message observer (watches for new chat messages) ─────────────────

  function startObserver() {
    if (observerActive || !detectedProfile) return;
    observerActive = true;

    // Disconnect the body observer since we found the widget
    bodyObserver.disconnect();

    const selectors = detectedProfile.selectors;
    const container =
      document.querySelector(selectors.messageList) ||
      document.querySelector(selectors.chatContainer);

    if (!container) return;

    messageObserver = new MutationObserver((mutations) => {
      // Check if any actual content was added (not just attributes)
      const hasNewContent = mutations.some(
        (m) => m.type === 'childList' && m.addedNodes.length > 0
      );

      if (hasNewContent) {
        // Send updated state to background
        const state = ResolveExtractor.extractState(detectedProfile);
        ResolveBus.send('widget_state_update', { state });
      }
    });

    messageObserver.observe(container, {
      childList: true,
      subtree: true,
      characterData: true,
    });
  }

  // ─── Handle messages from the background service worker ───────────────

  ResolveBus.onMessage(async (msg) => {
    if (msg.type === 'get_widget_state') {
      if (!detectedProfile) return { found: false };
      const state = ResolveExtractor.extractState(detectedProfile);
      return { found: true, ...state };
    }

    if (msg.type === 'execute_action') {
      if (!detectedProfile) {
        return { success: false, error: 'No widget detected in this frame' };
      }
      const result = await ResolveExecutor.execute(msg.action, detectedProfile);
      return result;
    }

    if (msg.type === 'ping') {
      return { pong: true, hasWidget: !!detectedProfile };
    }
  });

  // ─── Cleanup on page unload ───────────────────────────────────────────

  window.addEventListener('beforeunload', () => {
    if (messageObserver) messageObserver.disconnect();
    bodyObserver.disconnect();
    if (detectedProfile) {
      ResolveBus.send('widget_lost');
    }
  });
})();
