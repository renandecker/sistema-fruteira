package br.com.fruteira.estoque;

import jakarta.ws.rs.*;
import java.math.BigDecimal;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "catalogo") @Path("/produtos")
public interface CatalogoClient {
    @PUT @Path("/{id}/custo-medio") void custo(@PathParam("id") Long id, @QueryParam("valor") BigDecimal valor);
}
