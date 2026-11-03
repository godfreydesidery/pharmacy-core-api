package tz.co.pharmacy.delivery.edge;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The OpenAPI 3.1 document at /v3/api-docs and Swagger UI at /swagger-ui.html. */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

  @Bean
  OpenAPI pharmacyOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Pharmacy Core API")
                .version("v1")
                .description(
                    "Multi-tenant, multi-branch pharmacy management. Send Authorization: Bearer"
                        + " <token>, X-Tenant-Id and, on branch routes, X-Branch-Id."))
        .components(
            new Components()
                .addSecuritySchemes(
                    "session", new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer"))
                .addParameters(
                    "tenant", new HeaderParameter().name(EdgeFilter.TENANT_HEADER).required(false))
                .addParameters(
                    "branch", new HeaderParameter().name(EdgeFilter.BRANCH_HEADER).required(false)))
        .addSecurityItem(new SecurityRequirement().addList("session"));
  }
}
