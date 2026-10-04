import React from 'react'; import { createRoot } from 'react-dom/client'; import App from './App.jsx'; import { iniciarAuth } from './auth.js'
const root = createRoot(document.getElementById('root'))
iniciarAuth()
  .then(u => root.render(<App usuarioInicial={u} />))
  .catch(e => { root.render(<pre style={{ padding: 24 }}>Falha ao conectar ao Keycloak: {String(e)}</pre>) })
