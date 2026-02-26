# Resolve Android Companion - Agent Memory

## Project Structure
- Package: `com.cssupport.companion`
- minSdk 28, targetSdk 35, Kotlin, View system (NOT Compose), Material 3
- Theme: `Theme.Resolve.Dark` (OLED true black, purple seed #6C5CE7)
- No DI framework (manual construction in activities/services)

## Activity Flow
`WelcomeActivity -> OnboardingActivity -> MainActivity -> MonitorActivity -> CompleteActivity`
Settings reachable from MainActivity gear icon.
API-key users: OnboardingActivity routes to SettingsActivity (with MainActivity in back stack) via `needs_api_key_setup` flag in `resolve_prefs`.

## Key Architecture Patterns
- FULLY STANDALONE: no backend, no server communication, no telemetry
- `AgentLogStore` singleton drives MonitorActivity feed via `StateFlow`
- `AuthManager` uses EncryptedSharedPreferences for credentials
- `CompanionAgentService` foreground service owns coroutine scope (SupervisorJob + IO), single mode only
- `SupportAccessibilityService` singleton pattern via `instance` companion property
- `LLMClient` uses raw `HttpURLConnection` (no OkHttp/Retrofit)
- Two auth paths: "Continue with ChatGPT" (OpenAI key dialog) and "Use your own API key" (multi-provider via Settings)
- BackendClient.kt and AppPrefs.kt DELETED (no backend polling mode)

## Key Files
- Activities: WelcomeActivity.kt, OnboardingActivity.kt, MainActivity.kt, MonitorActivity.kt, CompleteActivity.kt, SettingsActivity.kt
- Core: AgentLoop.kt, LLMClient.kt, AccessibilityEngine.kt, CompanionAgentService.kt
- Data: AuthManager.kt, AgentLogStore.kt, SafetyPolicy.kt
- Service: SupportAccessibilityService.kt

## Audit Fix Progress
- Phase 1 (safety-critical): ALL DONE (1.1-1.4)
- Phase 2 (product-critical): ALL DONE (2.1-2.6)
- Phase 3 (UX improvements): ALL DONE (3.1-3.9)
- Phase 4 (UI polish/hardening): PARTIAL (4.3,4.4,4.5,4.8,4.13,4.16,4.17,4.18,4.19,4.20,4.21,4.22,4.23 DONE)

## Completed Fixes Summary
### Phase 1 (done before this session)
- Deny button now stops agent instead of resuming
- SettingsActivity exported=false, intent config gated behind DEBUG
- API key removed from AppPrefs, all callers use AuthManager
- Terminal state detection uses LogCategory sealed values instead of string matching
- hasNavigatedToComplete guard in MonitorActivity
- User-friendly error messages in AgentLoop catch block

### Phase 2 (done this session)
- 2.1: Model chip hidden for OAuth users (visibility=gone default in XML, refreshModelChip checks isOAuthTokenValid)
- 2.2: API-key path sets `needs_api_key_setup` flag; OnboardingActivity routes to Settings+Main
- 2.3: Already done in Phase 1 (hasNavigatedToComplete guard)
- 2.4: Already done in Phase 1 (exception-to-friendly-message mapping)
- 2.5: Network check via ConnectivityManager before starting agent, Snackbar on failure
- 2.6: Null launch intent shows Snackbar "open it manually" instead of silently failing

### Phase 3 (done this session: 3.1, 3.3, 3.5, 3.7, 3.8)
- 3.1: Toast validations replaced with TextInputLayout.error + Snackbar with actions
- 3.3: OnBackPressedCallback in MonitorActivity with MaterialAlertDialogBuilder confirmation
- 3.5: mapAppToPackage expanded with 10 more known apps + fuzzy label matching fallback (companion object KNOWN_APP_PACKAGES)
- 3.7: SafetyPolicy gains autoApproveSafeActions param; ClickButton NeedsApproval auto-approved when true; CompanionAgentService reads resolve_prefs/auto_approve
- 3.8: MonitorActivity snapshots transcript+stepCount into intent extras; CompleteActivity reads from extras with AgentLogStore fallback

### Phase 4 (done this session: 4.3,4.4,4.5,4.8,4.13,4.16,4.17,4.18,4.19,4.20,4.21,4.22,4.23)
- 4.3: Hardcoded strings extracted to strings.xml (MonitorActivity, SettingsActivity, OnboardingActivity, CompanionAgentService, MainActivity)
- 4.4: Empty/loading state on MonitorActivity (CircularProgressIndicator + text, toggles with RecyclerView)
- 4.5: "LLM Provider" renamed to "AI Model" in settings_llm_provider string
- 4.8: Network security config restricts cleartext to localhost/127.0.0.1 only; usesCleartextTraffic=false
- 4.13: Removed backward-compatible color aliases (bg_top, bg_mid, bg_bottom, surface, ink, ink_soft, primary, secondary, secondary_container, card_stroke) -- no references found
- 4.16: Time-aware greeting (morning/afternoon/evening) in MainActivity using Calendar.HOUR_OF_DAY
- 4.17: Exponential backoff on LLM retries (3s * 2^retry, capped at 30s) with llmRetryCount counter
- 4.18: Step counter changed from "Step X of ~30" to "X actions taken" (single arg)
- 4.19: Attachment X remove button via FrameLayout wrapper + ImageView overlay, replaced long-press removal
- 4.20: Dead displayName() method and unused OpenableColumns import removed from MainActivity
- 4.21: Share button on CompleteActivity (tonal style, horizontal row with New Issue, shares resolution text)
- 4.22: Accessibility live region (assertive) on approvalOverlay FrameLayout
- 4.23: Expired OAuth token detection in WelcomeActivity (clears stale tokens before credential check)

## ChatGPT OAuth Infrastructure
- `ChatGPTOAuth.kt`: PKCE Authorization Code flow against `auth.openai.com`
- Generates code_verifier (32-byte SecureRandom, Base64 URL-safe), code_challenge (SHA-256), state (16-byte)
- Builds auth URL for `https://auth.openai.com/oauth/authorize` with PKCE S256
- Token exchange + refresh via `https://auth.openai.com/oauth/token` using raw HttpURLConnection
- Client ID from `BuildConfig.CHATGPT_OAUTH_CLIENT_ID` (Codex public client: `app_EMoamEEZ73f0CkXaXp7hrann`)
- Redirect URI: `http://localhost:1455/auth/callback` (localhost callback server, NOT custom scheme)
- Scope: `openid profile email offline_access` (offline_access needed for refresh tokens)
- `startCallbackServer()` spins up one-shot ServerSocket on port 1455, accepts redirect, returns `OAuthCallbackResult`
- 60-second timeout on callback server; responds with HTML success/failure page
- `OAuthCallbackResult`, `OAuthTokenResponse`, `OAuthException` data types in same file
- Default model for OAuth users: `gpt-4o`
- `AuthManager` gained `needsOAuthRefresh()` (5-min window) and `refreshOAuthTokenIfNeeded()` (suspend, auto-saves)
- AndroidManifest: NO deep link intent-filter (removed); WelcomeActivity keeps `launchMode="singleTop"`
- `androidx.browser:browser:1.8.0` added for Chrome Custom Tabs
- network_security_config.xml allows cleartext for localhost/127.0.0.1 (needed for callback server)
- JSONObject.optString(key, null) causes Kotlin type mismatch warning -- use `if (has(key)) getString(key) else null`

## ChatGPT OAuth UI Integration (DONE)
- WelcomeActivity: starts localhost callback server FIRST, then launches Chrome Custom Tab
- PKCE verifier+state persisted in `resolve_oauth_flow` SharedPreferences
- Callback server receives redirect with code+state; no deep link / onNewIntent needed
- Loading state: indeterminate ProgressBar + disabled buttons + "Signing in..." text
- Error handling: Toast messages for failures (oauth_error, oauth_state_mismatch strings)
- CompanionAgentService: `runLocalLoop` refreshes OAuth token before creating LLMClient (llmConfig is var)
- Layout: ProgressBar (id=oauthLoadingIndicator) added above sign-in button in activity_welcome.xml

## Known Remaining Issues
- No "return to monitor" navigation from target app (only notification)
- Phase 4 remaining: 4.1,4.2,4.6,4.7,4.9,4.10,4.11,4.12,4.14,4.15
- strings.xml linter auto-reformats on save (may silently drop/reorder entries)
