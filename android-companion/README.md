# Android Companion App

Modern Android client for the CS Support Agent backend.

Customer flow is intentionally minimal:

1. Enter API key
2. Enter app name + issue + short description
3. Attach optional media
4. Tap **Submit Issue**

The app handles the rest:

- calls `POST /api/client/mobile/submit` to bootstrap/reuse session + create case
- starts foreground companion service automatically
- polls command queue via `POST /api/device-agent/poll`
- streams progress via `POST /api/device-agent/commands/:id/events`
- completes/fails via `POST /api/device-agent/commands/:id/complete|fail`

## App structure

- `MainActivity.kt`: single-screen submit UX and media picker
- `CompanionAgentService.kt`: foreground polling loop + command execution/reporting
- `BackendClient.kt`: REST client for client/mobile + device-agent endpoints
- `SupportAccessibilityService.kt`: accessibility bridge stub for app automation hooks
- `AppPrefs.kt`: local persistence of API key, app name, and device credentials

## Build and run

1. Open `android-companion/` in Android Studio (Jellyfish or newer).
2. Let Gradle sync.
3. Run on Android 8.0+ device/emulator.
4. Ensure backend is running on `http://10.0.2.2:8787` (emulator).
5. In app, submit an issue from the main form.

## Permissions and policies

- Uses `INTERNET` and foreground service permissions.
- Accessibility service remains optional in this prototype and can be enabled in system settings when needed.
- For production distribution, enforce explicit user consent per target app and respect platform automation policies.

## Current limitations

- UI automation steps are still placeholder events in this prototype.
- Per-app complaint navigation handlers are not yet implemented.
- No secure hardware-backed credential storage yet.
