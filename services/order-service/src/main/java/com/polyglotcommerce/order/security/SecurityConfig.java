package com.polyglotcommerce.order.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Un ordine e' sempre di qualcuno: qui non c'e' nulla di pubblico.
 *
 * Creare ordini spetta ai clienti; cambiarne lo stato a mano e' un'azione
 * da back office (ADMIN) — lo stato "normale" lo decide la saga per
 * evento, non via HTTP. Chi puo' vedere quali ordini e' una regola sui
 * dati, non sull'URL, e sta quindi nel service (vedi
 * {@code OrderService}).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf().disable()
                .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                .and()
                .authorizeHttpRequests(auth -> auth
                        // Le metriche sono raccolte da Prometheus, che non ha un
                        // token: l'endpoint deve essere raggiungibile senza
                        // autenticazione.
                        //
                        // Non espone dati sensibili (nomi di endpoint, latenze,
                        // metriche JVM), ma non e' informazione da regalare: in
                        // un ambiente reale gli endpoint di management vanno su
                        // una **porta separata** (management.server.port), non
                        // instradata dal gateway e raggiungibile solo dalla rete
                        // interna del cluster.
                        .antMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .antMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .antMatchers(HttpMethod.POST, "/api/orders").hasRole("CUSTOMER")
                        .antMatchers(HttpMethod.PATCH, "/api/orders/*/status").hasRole("ADMIN")
                        .antMatchers(HttpMethod.DELETE, "/api/orders/*").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt()
                        .jwtAuthenticationConverter(jwtAuthenticationConverter()));

        return http.build();
    }

    static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRolesConverter());
        return converter;
    }
}
