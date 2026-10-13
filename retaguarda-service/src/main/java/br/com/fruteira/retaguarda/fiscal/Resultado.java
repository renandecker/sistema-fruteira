package br.com.fruteira.retaguarda.fiscal;

/** Retorno da emissão/consulta. status: AUTORIZADA · CONTINGENCIA · REJEITADA · DENEGADA. */
public class Resultado {
    public String status, chave, protocolo, xmlAssinado, xmlAutorizado, qrCode, urlChave, xMotivo, dhAutorizacao, cNF;
    public int cStat, numero, tpEmis;
    /** true se a rejeição é sobre o CPF/destinatário — o caixa pode corrigir o CPF ou emitir sem CPF. */
    public boolean erroCpf;
    /** true = não houve comunicação com a SEFAZ (a venda pode seguir em contingência offline). */
    public boolean semComunicacao;
    public boolean autorizada() { return "AUTORIZADA".equals(status); }
}
