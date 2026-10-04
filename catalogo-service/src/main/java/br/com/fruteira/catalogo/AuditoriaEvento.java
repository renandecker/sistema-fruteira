package br.com.fruteira.catalogo;

/** Evento enviado ao retaguarda-service, onde a trilha de auditoria é gravada. */
public record AuditoriaEvento(String acao, String entidade, String entidadeId, String descricao,
                              String antes, String depois, String usuario, String perfil, String origem) {}
