# Resolve Android App — Audit Fixes Tracker

Combined findings from Elite UI Designer and Elite Android Builder audits.
Status: [ ] = TODO, [x] = DONE, [-] = SKIPPED

---

## Phase 1: Safety-Critical Fixes (IMMEDIATE)

### 1.1 [C1] Approval Deny and Allow buttons do the same thing
- **File:** `MonitorActivity.kt` lines 94-102
- **Bug:** Both Deny and Allow call `CompanionAgentService.resume()`. The human-in-the-loop safety gate is non-functional.
- **Fix:** Deny should stop the agent or skip the action. Allow resumes.
- [x] DONE

### 1.2 [E1] SettingsActivity exported=true — security vulnerability
- **File:** `AndroidManifest.xml` lines 53-54
- **Bug:** Any app on the device can launch SettingsActivity and inject arbitrary API keys/endpoints via intent extras, redirecting LLM traffic to a malicious server.
- **Fix:** Set `exported="false"`. Gate `handleConfigIntent()` behind `BuildConfig.DEBUG`.
- [x] DONE

### 1.3 [E2] AppPrefs stores API key in plain SharedPreferences
- **File:** `AppPrefs.kt`
- **Bug:** `api_key` stored in cleartext `SharedPreferences`. Violates project rule: "Never store API keys in plain SharedPreferences."
- **Fix:** Remove `KEY_API_KEY` from AppPrefs. Ensure all callers use `AuthManager` instead.
- [x] DONE

### 1.4 [C2] Terminal state detection uses fragile string matching
- **File:** `MonitorActivity.kt` lines 127-149
- **Bug:** Checks `displayMessage.contains("resolved")` — could false-trigger on "We have not yet resolved".
- **Fix:** Add explicit `LogCategory` values: `TERMINAL_RESOLVED`, `TERMINAL_FAILED`, `APPROVAL_NEEDED`. Use category checks instead of string matching.
- [x] DONE

---

## Phase 2: Product-Critical Fixes (same day)

### 2.1 [A1] Model chip "GPT-5 Nano" exposed to OAuth users
- **Files:** `MainActivity.kt` lines 126-133, `activity_main.xml` lines 87-95
- **Fix:** Hide chip for OAuth users. Only show for API-key users. Default visibility=gone in XML.
- [x] DONE

### 2.2 [A2] API key users get lost after onboarding — 4-screen scavenger hunt
- **Files:** `WelcomeActivity.kt` lines 54-57, `OnboardingActivity.kt`
- **Fix:** Mark API-key path in shared prefs. After accessibility enabled, route API-key users to Settings first.
- [x] DONE

### 2.3 [C4] navigateToComplete fires multiple times from StateFlow
- **File:** `MonitorActivity.kt` line 131
- **Fix:** Add `hasNavigatedToComplete` guard boolean.
- [x] DONE (already fixed in Phase 1)

### 2.4 [E3] Raw exception class names shown to users on LLM failures
- **File:** `AgentLoop.kt` lines 72-76, 153
- **Fix:** Map common exceptions (401, 429, timeout, no internet) to user-friendly messages.
- [x] DONE (already fixed in Phase 1)

### 2.5 [E4] No network connectivity check before starting agent
- **File:** `MainActivity.kt` line 226
- **Fix:** Check `ConnectivityManager` before starting. Show Snackbar if offline.
- [x] DONE

### 2.6 [UI-3] No error when target app isn't installed
- **File:** `MainActivity.kt` lines 246-250
- **Fix:** If `getLaunchIntentForPackage` returns null, show Snackbar asking user to open manually.
- [x] DONE

---

## Phase 3: UX Improvements

### 3.1 [B2] Toast validation → inline field errors + Snackbar
- **File:** `MainActivity.kt` lines 177-204
- **Fix:** Use `TextInputLayout.error` for field validation. Snackbar with action for cross-cutting issues (API key, accessibility).
- [x] DONE

### 3.2 [A4] No success feedback after enabling accessibility
- **File:** `OnboardingActivity.kt` lines 27-31
- **Fix:** Show brief "All set!" state with check icon before navigating. 800ms delay.
- [x] DONE

### 3.3 [E7] Back press from MonitorActivity — no confirmation
- **File:** `MonitorActivity.kt`
- **Fix:** Add `OnBackPressedCallback` with MaterialAlertDialog: "Keep Running" or "Stop Agent".
- [x] DONE

### 3.4 [A3] Onboarding steps are Samsung-specific
- **File:** `strings.xml` lines 18-20
- **Fix:** Generic instructions: 'Find "Resolve" in the list (search or scroll)', 'Tap on it', 'Turn switch ON and confirm'.
- [x] DONE

### 3.5 [B3] mapAppToPackage broken for "Other" apps
- **File:** `MainActivity.kt` lines 284-292
- **Fix:** Add more known packages (Flipkart, Zomato, PhonePe, Paytm, Myntra). Fuzzy-search installed apps by label as fallback.
- [x] DONE

### 3.6 [C3] No way to return to MonitorActivity from target app
- **File:** `CompanionAgentService.kt` notification builder
- **Fix:** Make notification high-priority with explicit "View Progress" and "Stop" actions.
- [x] DONE

### 3.7 [E6] Auto-approve toggle does nothing — broken promise
- **Files:** `SettingsActivity.kt` lines 142-147, `SafetyPolicy.kt`
- **Fix:** Wire toggle to SafetyPolicy. Pass `autoApproveSafeActions` flag. Auto-allow non-financial NeedsApproval results.
- [x] DONE

### 3.8 [D1] CompleteActivity log race condition
- **Files:** `CompleteActivity.kt` line 51, `MonitorActivity.kt`
- **Fix:** Snapshot log entries into intent extras in `navigateToComplete()`. CompleteActivity reads from extras, not AgentLogStore.
- [x] DONE

### 3.9 [B1] Form cognitive overload — 7 input areas
- **File:** `activity_main.xml`, `MainActivity.kt`
- **Fix:** Merge "Issue title" and "Description" into single "What happened?" field. Collapse Order ID + Attachments under "Add details" expander.
- [x] DONE

---

## Phase 4: UI Polish and Hardening

### 4.1 [UI-4] WCAG contrast failures — compounded alpha values
- **Files:** `activity_welcome.xml` (lines 92, 134, 164), `item_activity_action.xml` (42), `item_previous_case.xml` (88), `activity_main.xml` (393)
- **Fix:** Remove alpha below 0.7 on text. Use semantic color tokens at full opacity instead. Rule: never alpha < 0.7 on text.
- [x] DONE

### 4.2 [UI-5] Hardcoded colors break light theme
- **Files:** `bg_approval_card.xml`, `bg_bubble_agent.xml`, `bg_bubble_resolve.xml`, `item_chat_bubble_sent.xml`, `item_chat_bubble_received.xml`, `activity_complete.xml`
- **Fix:** Replace hardcoded colors with theme attributes (`?attr/colorSurfaceContainerHigh`, `?attr/colorPrimaryContainer`, etc.).
- [x] DONE

### 4.3 [UI-6] Hardcoded strings in Kotlin code
- **Files:** `MonitorActivity.kt` ("Resume", "Agent stopped"), `SettingsActivity.kt` ("Hide API key fields", "ChatGPT (OAuth)", "Not configured"), provider array
- **Fix:** Extract all to `strings.xml`.
- [x] DONE

### 4.4 [UI-7] Missing loading/empty state on MonitorActivity
- **File:** `activity_monitor.xml`
- **Fix:** Add empty state with CircularProgressIndicator + "Starting agent..." text. Toggle visibility when entries arrive.
- [x] DONE

### 4.5 [UI-8] "LLM Provider" jargon in Settings
- **File:** `strings.xml` line 71
- **Fix:** Change to "AI Model". Hide entire section for OAuth users.
- [x] DONE

### 4.6 [UI-9] Welcome subtitle readability
- **File:** `activity_welcome.xml` lines 81-92
- **Fix:** Remove `alpha="0.7"`. Increase horizontal margins to 32dp. Keep center alignment.
- [x] DONE

### 4.7 [UI-10] Onboarding step numbers use wrong drawable
- **File:** `activity_onboarding.xml` lines 81-83, 109-111, 136-138
- **Fix:** Create `bg_step_circle.xml` (oval shape, colorPrimary fill). Replace `bg_thumbnail_placeholder` refs.
- [x] DONE

### 4.8 [E5] usesCleartextTraffic=true in manifest
- **File:** `AndroidManifest.xml` line 13
- **Fix:** Create `network_security_config.xml` allowing cleartext only for localhost. Set `usesCleartextTraffic="false"`.
- [x] DONE

### 4.9 [UI-11] Bottom CTA bar edge-to-edge inset handling
- **File:** `activity_main.xml` lines 406-431
- **Fix:** Increase paddingBottom to 48dp or add `ViewCompat.setOnApplyWindowInsetsListener`.
- [x] DONE

### 4.10 [UI-12] Redundant fontFamily on styled elements
- **Files:** `activity_welcome.xml` (69), `activity_onboarding.xml` (39), `activity_main.xml` (72), `activity_complete.xml` (80), `activity_monitor.xml` (196), `activity_settings.xml` (63)
- **Fix:** Remove `fontFamily="sans-serif-medium"` where textAppearance already sets medium weight.
- [x] DONE

### 4.11 [UI-13] Approval button touch targets too small
- **File:** `activity_monitor.xml` lines 217-232
- **Fix:** Increase both buttons to 48dp height. Allow button minWidth 120dp.
- [x] DONE

### 4.12 [UI-14] Chat bubble line spacing too tight
- **Files:** `item_chat_bubble_sent.xml` (37), `item_chat_bubble_received.xml` (38)
- **Fix:** Change `lineSpacingMultiplier` from 1.3 to 1.4.
- [x] DONE

### 4.13 [UI-16] Legacy color resources still present
- **File:** `colors.xml` lines 130-141
- **Fix:** Search for references. If none, delete. If referenced, migrate to M3 tokens.
- [x] DONE

### 4.14 [UI-17] Transcript uses monospace font
- **File:** `activity_complete.xml` line 274
- **Fix:** Remove `fontFamily="monospace"`. Let default system font handle it.
- [x] DONE

### 4.15 [UI-18] Redundant outline+fill on cards
- **Files:** `activity_settings.xml` (90-96), `activity_complete.xml` (222-229)
- **Fix:** Use filled card without stroke on OLED. Drop Outlined style.
- [x] DONE

### 4.16 [UI-19] Greeting text is static and impersonal
- **Files:** `strings.xml` (24), `MainActivity.kt`
- **Fix:** Time-aware greeting: "Good morning/afternoon/evening". Warmer tone.
- [x] DONE

### 4.17 [E9] No exponential backoff on LLM retries
- **File:** `AgentLoop.kt` lines 154-157
- **Fix:** Add exponential backoff: 3s, 6s, 12s, 24s, capped at 30s.
- [x] DONE

### 4.18 [C5] Step counter shows "of ~30"
- **File:** `strings.xml` line 57, `MonitorActivity.kt`
- **Fix:** Change to "%d actions taken". No max shown.
- [x] DONE

### 4.19 [B4] Attachment long-press-to-remove undiscoverable
- **File:** `MainActivity.kt` lines 167-170
- **Fix:** Add visible "X" badge overlay on each thumbnail.
- [x] DONE

### 4.20 [B5] Dead displayName() utility
- **File:** `MainActivity.kt` lines 294-301
- **Fix:** Remove dead code.
- [x] DONE

### 4.21 [D2] No share capability for resolution
- **File:** `CompleteActivity.kt`
- **Fix:** Add share button that sends resolution summary as text.
- [x] DONE

### 4.22 [E10] Accessibility live region on approval overlay
- **File:** `activity_monitor.xml`
- **Fix:** Add `accessibilityLiveRegion="assertive"` to approval overlay.
- [x] DONE

### 4.23 [A5] Expired OAuth token handling
- **File:** `WelcomeActivity.kt` lines 25-33
- **Fix:** Detect expired OAuth separately. Clear stale tokens. Show re-auth.
- [x] DONE
