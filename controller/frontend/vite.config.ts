import { defineConfig } from 'vite';
import preact from '@preact/preset-vite';

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [preact()],
  build: {
    // The bundle is ~630 kB, dominated by the vendored hls.js (~200 kB gzip).
    // This app loads from local disk in a WebView2, not over a network, so the
    // default 500 kB "consider code-splitting" warning isn't actionable here.
    chunkSizeWarningLimit: 900,
  },
});
