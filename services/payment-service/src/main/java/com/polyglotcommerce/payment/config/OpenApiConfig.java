package com.polyglotcommerce.payment.config;

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
                        .title("Payment Service")
                        .version("0.1.0")
                        .description("Pagamenti. Il percorso normale non passa da queste API ma dall'evento payment.requested pubblicato dalla saga; gli endpoint servono a consultazione e prove."))
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
