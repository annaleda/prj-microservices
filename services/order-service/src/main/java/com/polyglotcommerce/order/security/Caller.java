package com.polyglotcommerce.order.security;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;

/**
 * Chi sta chiamando, letto dal token: identita' e capacita' di vedere
 * ordini altrui.
 *
 * L'email non arriva piu' dal corpo della richiesta ma dal token: prima
 * chiunque poteva creare un ordine intestandolo a un indirizzo qualsiasi.
 */
public class Caller {

    private static final String ADMIN = "ADMIN";
    private static final String SUPPORT = "SUPPORT";

    private final String email;
    private final String username;
    private final boolean staff;

    private Caller(String email, String username, boolean staff) {
        this.email = email;
        this.username = username;
        this.staff = staff;
    }

    @SuppressWarnings("unchecked")
    public static Caller from(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        Collection<String> roles = realmAccess != null && realmAccess.get("roles") instanceof Collection
                ? (Collection<String>) realmAccess.get("roles")
                : Collections.emptyList();

        String username = jwt.getClaimAsString("preferred_username");
        String email = jwt.getClaimAsString("email");

        return new Caller(
                // Un utente Keycloak potrebbe non avere l'email valorizzata:
                // in quel caso l'ordine resta comunque riconducibile a chi lo
                // ha creato tramite lo username.
                email != null && !email.isEmpty() ? email : username,
                username,
                roles.contains(ADMIN) || roles.contains(SUPPORT));
    }

    public String getEmail() {
        return email;
    }

    public String getUsername() {
        return username;
    }

    /**
     * Personale interno (ADMIN, SUPPORT): vede e gestisce gli ordini di
     * tutti i clienti, non solo i propri.
     */
    public boolean isStaff() {
        return staff;
    }
}
