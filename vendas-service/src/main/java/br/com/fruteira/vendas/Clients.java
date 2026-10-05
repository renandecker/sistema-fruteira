package br.com.fruteira.vendas;

import jakarta.ws.rs.*;
import java.math.BigDecimal;
import org.eclipse.microprofile.rest.client.annotation.RegisterClientHeaders;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

public class Clients {
    public record ProdutoDTO(Long id, String nome, String unidade, BigDecimal precoVarejo, BigDecimal precoAtacado, BigDecimal precoPromocional,
                             BigDecimal custoMedio, Boolean ativo, String promocaoDescricao) {
        /** Preço normal (sem promoção) */
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
