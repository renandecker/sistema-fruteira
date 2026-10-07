package br.com.fruteira.vendas;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** API do Pix. O React só fala com este serviço (nunca com o PSP); o PSP chama o webhook. */
@Path("/pix") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class PixResource {
    @Inject PixService pix;
    @Inject Auditor auditor;
    @ConfigProperty(name = "pix.webhook.segredo", defaultValue = "") String segredo;
    @ConfigProperty(name = "pix.webhook.url", defaultValue = "") String webhookUrl;
    @ConfigProperty(name = "pix.chave", defaultValue = "") String chave;

    public record Nova(BigDecimal valor, String descricao, String terminal) {}
    public record PixDTO(Long id, String txid, String status, BigDecimal valor, BigDecimal valorPago, String pixCopiaECola, LocalDateTime criadaEm, LocalDateTime expiraEm,
                         long segundosRestantes, String provedor, String endToEndId, Long vendaId, LocalDateTime pagoEm, LocalDateTime devolvidaEm) {}
    private static PixDTO dto(PixCobranca c) {
        long resta = "ATIVA".equals(c.status) && c.expiraEm != null ? Math.max(0, Duration.between(LocalDateTime.now(), c.expiraEm).getSeconds()) : 0;
        return new PixDTO(c.id, c.txid, c.status, c.valor, c.valorPago, c.pixCopiaECola, c.criadaEm, c.expiraEm, resta, c.provedor, c.endToEndId, c.vendaId, c.pagoEm, c.devolvidaEm);
    }

    /** 1) Gera a cobrança no PSP e devolve o QR Code / Pix Copia e Cola. */
    @POST @Path("/cobrancas")
    public PixDTO criar(Nova n) { return dto(pix.criar(n.valor(), n.descricao(), n.terminal(), auditor.usuario())); }

    /** 2) Polling do front enquanto o pagamento está pendente. */
    @GET @Path("/cobrancas/{id}")
    public PixDTO consultar(@PathParam("id") Long id) { return dto(pix.atualizar(id)); }

    @POST @Path("/cobrancas/{id}/cancelar")
    public PixDTO cancelar(@PathParam("id") Long id) { return dto(pix.cancelar(id)); }

    /** Devolução do Pix recebido (supervisor/gerente). Se a venda também deve ser cancelada, devolva ANTES. */
    @POST @Path("/cobrancas/{id}/devolver")
    public PixDTO devolver(@PathParam("id") Long id) {
        if (!auditor.supervisorOuMais()) throw new ForbiddenException("Somente supervisor ou gerente pode devolver um Pix");
        return dto(pix.devolver(id));
    }

    @GET @Path("/cobrancas")
    public List<PixDTO> listar(@QueryParam("limit") @DefaultValue("100") int limit) {
        pix.expirarVencidas();
        return PixCobranca.<PixCobranca>find("order by id desc").page(0, Math.min(Math.max(limit, 1), 500)).list().stream().map(PixResource::dto).toList();
    }
    @GET @Path("/pendencias")
    public List<PixDTO> pendencias() { return pix.pendencias().stream().map(PixResource::dto).toList(); }

    /**
     * 3) Webhook chamado pelo PSP (público). Autenticação por segredo (cabeçalho X-Webhook-Token ou ?token=); com mTLS no PSP,
     * valide também o certificado no proxy. Idempotente: notificação repetida responde 200 sem reprocessar.
     */
    @POST @Path("/webhook")
    public Map<String, Object> webhook(@HeaderParam("X-Webhook-Token") String cabecalho, @QueryParam("token") String consulta, JsonNode corpo) {
        if (segredo.isBlank()) {
            if (!"simulador".equals(pix.provider().nome())) throw Http.erro(503, "Webhook sem segredo configurado (FRUTEIRA_PIX_WEBHOOK_SEGREDO)");
        } else {
            String recebido = cabecalho != null ? cabecalho : consulta;
            if (recebido == null || !MessageDigest.isEqual(recebido.getBytes(StandardCharsets.UTF_8), segredo.getBytes(StandardCharsets.UTF_8)))
                throw Http.erro(401, "Webhook não autorizado");
        }
        return Map.of("ok", true, "aplicados", corpo == null ? 0 : pix.receberNotificacao(corpo));
    }

    @GET @Path("/status")
    public Map<String, Object> status() {
        return Map.of("provedor", pix.provider().nome(), "chave", chave.isBlank() ? "" : chave, "webhookConfigurado", !webhookUrl.isBlank(), "simulador", pix.simulador().isPresent());
    }
    /** (gerente) Registra no PSP a URL do webhook definida em FRUTEIRA_PIX_WEBHOOK_URL. */
    @POST @Path("/webhook/registrar")
    public Map<String, Object> registrarWebhook() {
        if (!auditor.gerente()) throw new ForbiddenException("Somente o gerente pode registrar o webhook");
        if (webhookUrl.isBlank()) throw Http.erro(422, "Defina FRUTEIRA_PIX_WEBHOOK_URL (https://seu-dominio/api/vendas/pix/webhook?token=SEGREDO)");
        try { pix.provider().registrarWebhook(webhookUrl); } catch (Exception e) { throw Http.erro(502, "O PSP não registrou o webhook: " + e.getMessage()); }
        return Map.of("registrado", true);
    }
    /** Só no simulador: faz de conta que o cliente pagou (o "PSP" chama o webhook). */
    @POST @Path("/simulador/cobrancas/{id}/pagar")
    public PixDTO simularPagamento(@PathParam("id") Long id) {
        SimuladorPixProvider sim = pix.simulador().orElseThrow(() -> Http.erro(404, "Disponível apenas com PIX_PROVIDER=simulador"));
        PixCobranca c = pix.achar(id);
        sim.pagar(c.txid, 1);
        return dto(pix.achar(id));
    }
}
