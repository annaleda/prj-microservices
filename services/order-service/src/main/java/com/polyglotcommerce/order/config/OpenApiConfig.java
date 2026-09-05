package com.polyglotcommerce.order.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Documentazione OpenAPI del servizio (Swagger UI su /swagger-ui.html).
 *
 * Il pezzo che serve davvero e' lo <b>schema di sicurezza</b>: senza, la
 * pagina non mostra il pulsante "Authorize" e ogni prova sulle API
 * protette torna 401, il che rende la documentazione inutilizzabile
 * proprio dove servirebbe.
 *
 * Il token si ottiene da Keycloak (vedi README, sezione 3.2) e si incolla
 * nel pulsante Authorize.
 */
@Configuration
public class OpenApiConfig {

    static final String BEARER = "bearer-jwt";

    @Bean
    public OpenAPI serviceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Order Service")
                        .version("0.1.0")
                        .description("Ordini dei clienti. Un ordine e' intestato a chi presenta il token: un cliente vede solo i propri, ADMIN e SUPPORT tutti. L'esito (CONFIRMED / CANCELLED) non arriva da qui ma dalla saga, in modo asincrono."))
                .components(new Components().addSecuritySchemes(BEARER,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Access token emesso da Keycloak "
                                        + "(realm polyglot-commerce). Incollare il solo token, "
                                        + "senza il prefisso 'Bearer'.")));
    }
}
