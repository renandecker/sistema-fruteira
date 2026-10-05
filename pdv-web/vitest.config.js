import react from '@vitejs/plugin-react'
export default { plugins: [react()], test: { environment: 'jsdom', globals: true }, define: { __APP_ENV__: '"desenvolvimento"', __KC__: '{}' } }
