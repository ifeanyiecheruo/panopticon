# Vendored hls.js

[hls.js](https://github.com/video-dev/hls.js) — JavaScript HLS client using Media Source
Extensions. Used by `src/components/LivePreview.tsx` to play the phone's live HLS feed in the
Phone-detail screen (WebView2 / Chromium has no native HLS support).

**Vendored, not an npm dependency.** The controller frontend is built with a private toolchain
under `.local/` and this repo deliberately avoids build-time registry fetches, so the built
files are checked in here directly.

## Version

**1.7.2** — Apache-2.0, © 2017 Dailymotion. See `LICENSE`.

## Files

| File | What | Used at runtime |
|---|---|---|
| `hls.min.mjs` | minified ESM bundle — this is what `LivePreview.tsx` imports | ✅ |
| `hls.mjs` | full (unminified) ESM bundle — reference / debugging only | ❌ |
| `hls.d.ts` | TypeScript declarations, verbatim from the distribution | type-check only |
| `hls.min.d.mts` | 2-line shim pointing `hls.min.mjs` at `hls.d.ts` (TS `bundler` resolution maps `.mjs` → `.d.mts`) | type-check only |
| `LICENSE` | Apache-2.0 license text from the hls.js distribution | — |

The trailing `//# sourceMappingURL=` comment is stripped from the two `.mjs` files (the `.map`
files are not vendored) so Vite doesn't warn about a missing sourcemap.

## How to update

From `controller/frontend/`:

```sh
npm install --no-save hls.js@<new-version>        # pull the dist into node_modules only
D=node_modules/hls.js/dist
sed 's|^//# sourceMappingURL=.*||' $D/hls.min.mjs > src/vendor/hlsjs/hls.min.mjs
sed 's|^//# sourceMappingURL=.*||' $D/hls.mjs     > src/vendor/hlsjs/hls.mjs
cp $D/hls.d.ts            src/vendor/hlsjs/hls.d.ts
cp node_modules/hls.js/LICENSE src/vendor/hlsjs/LICENSE
npm uninstall hls.js                               # keep it out of package.json
# hls.min.d.mts is a hand-written 2-liner — no need to recopy it
```

Then bump the version above, run `npm run build` (`tsc --noEmit && vite build`) and re-check
`LivePreview.tsx` against any API changes (`Hls.Events.*`, `Hls.ErrorDetails.*`, the config
shape). hls.js inlines its web worker as a blob, so there is no separate `hls.worker.js` to
vendor.
