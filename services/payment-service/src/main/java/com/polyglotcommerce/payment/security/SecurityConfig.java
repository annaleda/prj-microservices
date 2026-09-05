package com.polyglotcommerce.payment.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Nessun cliente parla direttamente con questo servizio: i pagamenti
 * della saga arrivano per evento Kafka, non via HTTP, e Kafka non passa
 * da questo filtro.
 *
 * Le API REST restano quindi un ingresso di back office: creare un
 * pagamento a mano e' da ADMIN, consultarlo e' consentito anche a
 * SUPPORT, che deve poter rispondere a un cliente senza poter muovere
 * denaro.
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
                        .antMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .antMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .antMatchers(HttpMethod.POST, "/api/payments").hasRole("ADMIN")
                        .antMatchers(HttpMethod.GET, "/api/payments/**").hasAnyRole("ADMIN", "SUPPORT")
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
