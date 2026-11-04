import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

const backend = process.env.SYNAPSE_BACKEND ?? 'http://localhost:8080'

export default defineConfig({
  // Sub-path for static hosting, for example /synapse-city/ on GitHub Pages.
  base: process.env.SYNAPSE_BASE ?? '/',
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      '/api': backend,
      '/ws': { target: backend.replace(/^http/, 'ws'), ws: true },
    },
  },
  build: {
    chunkSizeWarningLimit: 1600,
  },
})
