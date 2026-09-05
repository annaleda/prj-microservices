package com.polyglotcommerce.order.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Traduce i ruoli di realm Keycloak in authority di Spring Security.
 *
 * Keycloak li mette in {@code realm_access.roles} (es. {@code ["ADMIN"]}),
 * mentre Spring Security si aspetta authority con prefisso {@code ROLE_}
 * per far funzionare {@code hasRole(...)}: senza questa conversione ogni
 * richiesta autenticata risulterebbe priva di ruoli, quindi 403.
 *
 * Ogni servizio ne ha una copia: come per gli eventi, il contratto
 * condiviso e' il JWT, non una libreria comune (l'Inventory Service, in
 * Python, fa la stessa lettura del claim).
 */
public class KeycloakRealmRolesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final String REALM_ACCESS_CLAIM = "realm_access";
    private static final String ROLES = "roles";

    @Override
    @SuppressWarnings("unchecked")
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap(REALM_ACCESS_CLAIM);
        if (realmAccess == null || !(realmAccess.get(ROLES) instanceof Collection)) {
            return Collections.emptyList();
        }

        return ((Collection<String>) realmAccess.get(ROLES)).stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .collect(Collectors.toList());
    }
}
