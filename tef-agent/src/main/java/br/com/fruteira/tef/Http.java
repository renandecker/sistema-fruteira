package br.com.fruteira.tef;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

final class Http {
    private Http() {}
    static WebApplicationException erro(int status, String msg) {
        return new WebApplicationException(Response.status(status).entity(msg).type(MediaType.TEXT_PLAIN).build());
    }
}
