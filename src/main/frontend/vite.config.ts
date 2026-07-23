import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  build: {
    outDir: '../resources/static',
    emptyOutDir: true,
  },
  server: {
    // Proxy API calls to Spring Boot during local dev
    proxy: {
      '/api': 'http://localhost:8081',
      '/login': 'http://localhost:8081',
      '/logout': 'http://localhost:8081',
      '/oauth2': 'http://localhost:8081',
    }
  }
})
