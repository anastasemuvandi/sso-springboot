# Playwright gate

```
export PATH=$PWD/../.tools/node/bin:$PATH   # from console-web/
npm run build
npx playwright install chromium             # once
npm run test:e2e
```

`playwright.config.ts` starts `e2e/stub-server.mjs` (BFF + wrapper stand-in on
:3999) and `vite preview` on :4173 with `CONSOLE_BFF_URL` pointing at the stub,
so the built console and its real proxy config are what is tested.

If Chromium cannot start on the host (missing system libraries, no sudo for
`playwright install-deps`), run the same thing in the matching image:

```
docker run --rm --network host -v "$PWD":/work -w /work \
  mcr.microsoft.com/playwright:v1.48.0-jammy \
  bash -lc "npx playwright test"
```
