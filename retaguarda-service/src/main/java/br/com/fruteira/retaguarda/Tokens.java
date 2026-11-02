package br.com.fruteira.retaguarda;

import br.com.fruteira.retaguarda.Entities.Usuario;
import io.smallrye.jwt.algorithm.SignatureAlgorithm;
import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import javax.crypto.spec.SecretKeySpec;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Emite o token (JWT HS256) que o front envia em todas as chamadas /api. Os 4 serviços validam com o mesmo segredo (JWT_SEGREDO). Sem segredo (desenvolvimento) não há token. */
@ApplicationScoped
public class Tokens {
    @ConfigProperty(name = "fruteira.jwt.segredo") Optional<String> segredo;
    @ConfigProperty(name = "fruteira.jwt.horas", defaultValue = "12") int horas;

    /** null quando não há segredo configurado (desenvolvimento). */
    public String emitir(Usuario u) {
        String s = segredo.map(String::trim).filter(x -> !x.isEmpty()).orElse(null);
        if (s == null) return null;
        if (s.getBytes(StandardCharsets.UTF_8).length < 32) throw new IllegalStateException("JWT_SEGREDO precisa de pelo menos 32 caracteres");
        return Jwt.issuer("fruteira").upn(u.login).subject(u.login).groups(Set.of(u.perfil)).claim("name", u.nome)
            .expiresIn(Duration.ofHours(horas))
            .jws().algorithm(SignatureAlgorithm.HS256).sign(new SecretKeySpec(s.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    }
}
