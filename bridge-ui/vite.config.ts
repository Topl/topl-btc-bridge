import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    port: 3001,
    strictPort: true,
    // host: "0.0.0.0",
    proxy: {
      // Note: we only proxy /api URLs to the server, if you need more,
      // you need to set that up.
      // #TODO Can't we just proxy everything except index.html and /app?
      // #TODO hide away the proxy config (do we even need it?)
      "/api": {
        target: "http://localhost:4000",
        secure: false,
        configure: (proxy, _options) => {
          proxy.on("error", (err, _req, _res) => {
            console.log("proxy error", err);
          });
          proxy.on("proxyReq", (proxyReq, req, _res) => {
            console.log("Sending Request to the Target:", req.method, req.url);
          });
          proxy.on("proxyRes", (proxyRes, req, _res) => {
            console.log(
              "Received Response from the Target:",
              proxyRes.statusCode,
              req.url
            );
          });
        },
      }
    }
  }
})
