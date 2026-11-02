import React from 'react'; import { createRoot } from 'react-dom/client'; import App from './App.jsx'; import { ObsRaiz } from './ObsRaiz.jsx'; import { iniciar as iniciarObs, erro as erroObs } from './observabilidade.js'; import { iniciarAuth, instalarIdentidadeDev } from './auth.js'
const root = createRoot(document.getElementById('root'))
instalarIdentidadeDev()
iniciarObs()
iniciarAuth()
  .then(u => root.render(<ObsRaiz><App usuarioInicial={u} /></ObsRaiz>))
  .catch(e => { erroObs(e, { origem: 'auth' }); root.render(<pre style={{ padding: 24 }}>Falha ao iniciar: {String(e)}</pre>) })
