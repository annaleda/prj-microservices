package com.polyglotcommerce.catalog.config;

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
 * pagina non mostra il pulsante "Authorize" e ogni prova dalle API
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
    public OpenAPI catalogOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Catalog Service")
                        .version("0.1.0")
                        .description("Prodotti e categorie del negozio. "
                                + "La lettura e' pubblica (e' la vetrina); "
                                + "creare, modificare ed eliminare richiede il ruolo ADMIN."))
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
