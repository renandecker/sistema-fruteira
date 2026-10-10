package br.com.fruteira.vendas;

import jakarta.ws.rs.*;
import java.math.BigDecimal;
import org.eclipse.microprofile.rest.client.annotation.RegisterClientHeaders;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

public class Clients {
    public record ProdutoDTO(Long id, String nome, String unidade, BigDecimal precoVarejo, BigDecimal precoAtacado, BigDecimal precoPromocional,
                             BigDecimal custoMedio, Boolean ativo, String promocaoDescricao, String ncm, String ncmStatus) {
        /** Preço normal (sem promoção) */
        /** null = NCM apto para a NFC-e (8 dígitos, existente e vigente); senão o motivo */
        public String problemaNcm() {
            if (ncm == null || !ncm.matches("\\d{8}")) return "sem NCM de 8 dígitos";
            if ("VALIDO".equals(ncmStatus)) return null;
            return switch (ncmStatus == null ? "NAO_VERIFICADO" : ncmStatus) { case "INVALIDO" -> "NCM inexistente na tabela oficial"; case "VENCIDO" -> "NCM com vigência encerrada"; default -> "NCM ainda não verificado"; };
        }
        public BigDecimal precoBase(boolean atacado) { return atacado && precoAtacado != null ? precoAtacado : precoVarejo; }
        /** Preço cobrado: a promoção vigente só vale se for mais barata que o preço normal */
        public BigDecimal preco(boolean atacado) {
            BigDecimal base = precoBase(atacado);
            return precoPromocional != null && precoPromocional.compareTo(base) < 0 ? precoPromocional : base;
        }
    }
    public record BaixaDTO(Long produtoId, BigDecimal quantidade, String tipo, String motivo) {}

    @RegisterRestClient(configKey = "catalogo") @RegisterClientHeaders @Path("/produtos")
    public interface Catalogo { @GET @Path("/{id}") ProdutoDTO buscar(@PathParam("id") Long id); }

    @RegisterRestClient(configKey = "estoque") @RegisterClientHeaders @Path("/estoque")
    public interface Estoque { @POST @Path("/baixa") @Consumes("application/json") void baixa(BaixaDTO b); }
}
