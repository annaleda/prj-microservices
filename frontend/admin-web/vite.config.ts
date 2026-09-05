import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// Proxy di sviluppo. Come in Customer Web, le regole sono per prefisso:
// dietro /api ci sono servizi diversi, ed e' lo stesso instradamento che
// fara' il vero API Gateway in un deploy.
//
// Nota: questo file viene letto solo all'avvio di `vite`, non a caldo —
// dopo averlo modificato il dev server va riavviato.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api/inventory': {
        target: 'http://localhost:8083',
        changeOrigin: true,
      },
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
    },
  },
})
