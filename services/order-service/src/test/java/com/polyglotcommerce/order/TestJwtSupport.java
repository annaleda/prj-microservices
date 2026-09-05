package com.polyglotcommerce.order;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Token finti per i test: un {@link JwtDecoder} che, invece di verificare
 * una firma, interpreta il token come {@code utente:RUOLI:email}.
 *
 * Perche' non un Keycloak vero anche qui: cio' che questi test devono
 * dimostrare sono le <i>regole di autorizzazione</i> del servizio (chi
 * puo' fare cosa), non che Keycloak sappia firmare un JWT. Avviare un
 * identity provider per ogni classe di test costerebbe minuti e legherebbe
 * i test alla sua configurazione. Che i token veri di Keycloak vengano
 * accettati e' verificato end-to-end a mano (vedi README, sezione 5).
 *
 * Due dettagli non ovvi:
 * <ul>
 *   <li>il payload viaggia in <b>base64url</b> perche' il filtro dei bearer
 *       token accetta solo i caratteri previsti da RFC 6750 ({@code token68}):
 *       un token con ':' o '@' viene scartato come malformato, con 401,
 *       prima ancora di arrivare al decoder;</li>
 *   <li>dichiarando un bean {@code JwtDecoder} l'auto-configurazione di
 *       Spring Boot non ne crea uno proprio e quindi non contatta l'issuer
 *       all'avvio dei test.</li>
 * </ul>
 */
@TestConfiguration
public class TestJwtSupport {

    public static final String ANONYMOUS = null;
    public static final String ADMIN = token("admin", "ADMIN", "admin@example.com");
    public static final String CUSTOMER = token("customer", "CUSTOMER", "customer@example.com");
    /** Un secondo cliente, per verificare che non veda gli ordini del primo. */
    public static final String OTHER_CUSTOMER = token("other", "CUSTOMER", "other@example.com");
    public static final String SUPPORT = token("support", "SUPPORT", "support@example.com");
    /**
     * Utente diverso da CUSTOMER ma con la stessa email: serve a
     * verificare che la proprieta' degli ordini dipenda dall'identita' e
     * non dall'indirizzo dichiarato.
     */
    public static final String SAME_EMAIL_OTHER_ACCOUNT =
            token("impostor", "CUSTOMER", "customer@example.com");

    public static String token(String username, String roles, String email) {
        String payload = username + ":" + roles + ":" + email;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        return token -> {
            String payload = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = payload.split(":");
            List<String> roles = parts.length > 1 && !parts[1].isEmpty()
                    ? Arrays.stream(parts[1].split(",")).map(String::trim).collect(Collectors.toList())
                    : Collections.emptyList();

            return Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .subject("test-" + parts[0])
                    .claim("preferred_username", parts[0])
                    .claim("email", parts.length > 2 ? parts[2] : "")
                    .claim("realm_access", Collections.singletonMap("roles", roles))
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                    .build();
        };
    }

    /** Entita' HTTP con (o senza, se {@code token} e' null) bearer token. */
    public static <T> HttpEntity<T> as(String token, T body) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return new HttpEntity<>(body, headers);
    }

    public static HttpEntity<Void> as(String token) {
        return as(token, null);
    }
}
