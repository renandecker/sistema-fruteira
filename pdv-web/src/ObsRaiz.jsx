import React, { Profiler } from 'react'
import { erro, aoRenderizar } from './observabilidade.js'

// Fronteira de erro do app: reporta (já sem dados pessoais) para as ferramentas ligadas e mostra uma tela de recuperação.
export class ObsRaiz extends React.Component {
  state = { falhou: false }
  static getDerivedStateFromError() { return { falhou: true } }
  componentDidCatch(e, info) { erro(e, { origem: 'react', pilha: info?.componentStack }) }
  render() {
    if (this.state.falhou) return <div role="alert" style={{ padding: 24 }}><h2>Algo deu errado</h2><p>O erro foi registrado. Recarregue a página para continuar; vendas pendentes continuam salvas.</p><button className="btn" onClick={() => location.reload()}>Recarregar</button></div>
    return <Profiler id="app" onRender={(id, fase, real) => aoRenderizar(id, fase, real)}>{this.props.children}</Profiler>
  }
}
