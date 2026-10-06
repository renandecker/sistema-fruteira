package br.com.fruteira.vendas;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** Erro HTTP com mensagem em texto no corpo (o front exibe ao operador). */
public final class Http {
    private Http() {}
    public static WebApplicationException erro(int status, String msg) {
        return new WebApplicationException(Response.status(status).entity(msg).type(MediaType.TEXT_PLAIN).build());
    }
}
