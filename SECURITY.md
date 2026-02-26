# Security Policy

## Reporting a vulnerability

Please open a private security report with:
- impact summary,
- affected files,
- reproduction steps.

If private reporting is not available, avoid posting exploit details publicly and open an issue requesting a secure contact channel.

## Security model

- API keys are stored using Android's EncryptedSharedPreferences (AES-256-GCM) with a plain SharedPreferences backup for Samsung Keystore failures
- PII patterns (SSN, credit card, passwords) are detected and blocked by SafetyPolicy before reaching the LLM
- Financial actions require explicit user approval
- The app sends zero data to any backend server — all LLM calls go directly from the device to the configured provider
- No analytics, telemetry, or crash reporting is included
