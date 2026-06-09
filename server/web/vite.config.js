import { defineConfig } from 'vite'
import { svelte } from '@sveltejs/vite-plugin-svelte'

export default defineConfig({
  plugins: [svelte()],
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: {
      '/webhook': 'http://localhost:9090',
      '/messages': 'http://localhost:9090',
      '/api': 'http://localhost:9090',
      '/status': 'http://localhost:9090',
      '/health': 'http://localhost:9090',
    }
  }
})
