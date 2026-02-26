# Contributing

## Setup

```bash
npm install
npx playwright install chromium
npm test
npm run build
```

## Development

- Run server: `npm run dev`
- Run mobile simulator: `npm run simulate:device`
- Android companion app: open `android-companion/` in Android Studio
- Keep APIs backward-compatible where practical.
- Add tests for new behavior.

## Pull requests

- Keep PRs focused and small.
- Include a short test plan in PR description.
- Do not commit secrets or private credentials.
