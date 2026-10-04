import { defineConfig } from 'vite'; import react from '@vitejs/plugin-react'
export default defineConfig({ plugins:[react()], server:{ proxy:{
  '/api/catalogo': { target:'http://localhost:8081', rewrite:p=>p.replace('/api/catalogo','') },
  '/api/estoque':  { target:'http://localhost:8082', rewrite:p=>p.replace('/api/estoque','') },
  '/api/retaguarda': { target:'http://localhost:8084', rewrite:p=>p.replace('/api/retaguarda','') },
  '/api/vendas':   { target:'http://localhost:8083', rewrite:p=>p.replace('/api/vendas','') } } } })
