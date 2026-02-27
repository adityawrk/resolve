# Contributing

## Setup

```bash
cd android-companion
./gradlew assembleDebug
```

Requires: Android SDK, Java 17, Kotlin. minSdk 28, targetSdk 35.

## Development

- Build: `./gradlew assembleDebug` (from `android-companion/`)
- Test on a real Android device (Samsung recommended — they have unique Keystore behavior)
- Emulator or physical device — `adb install` works for quick iteration

## Pull requests

- Keep PRs focused and small
- Include a short test plan in PR description
- Do not commit API keys or credentials
- Test on a physical device before submitting
