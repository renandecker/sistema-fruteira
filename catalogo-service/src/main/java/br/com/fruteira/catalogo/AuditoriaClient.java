package br.com.fruteira.catalogo;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.annotation.RegisterClientHeaders;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "auditoria") @RegisterClientHeaders @Path("/auditoria")
public interface AuditoriaClient {
    @POST @Consumes(MediaType.APPLICATION_JSON) void registrar(AuditoriaEvento e);
}
