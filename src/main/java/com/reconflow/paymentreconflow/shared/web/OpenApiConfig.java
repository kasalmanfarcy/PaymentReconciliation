package com.reconflow.paymentreconflow.shared.web;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI document (M11.7), generated from the controllers by springdoc at {@code /v3/api-docs}
 * with Swagger UI at {@code /swagger-ui.html}; both are off in {@code prod}. The committed copy is
 * {@code docs/openapi.json}, and {@code OpenApiSpecIT} fails when the code and it disagree, so an API
 * change shows up as a diff in review. Prose, error catalogue and rules stay in docs/api-design.md.
 */
@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(
        info =
                @Info(
                        title = "ReconFlow API",
                        version = "v1",
                        description = "Payment reconciliation: imports, runs, exceptions, reports. Errors are"
                                + " RFC 9457 problem details (docs/api-design.md §3).",
                        license = @License(name = "Apache-2.0")),
        security = @SecurityRequirement(name = "bearer"))
@SecurityScheme(
        name = "bearer",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "An access token from Keycloak (realm reconflow, audience reconflow-api)")
class OpenApiConfig {

    /** Tenant selection (docs/api-design.md §2) applies to every tenant endpoint; declared once here. */
    @Bean
    OperationCustomizer organizationHeader() {
        return (operation, handler) -> {
            String type = handler.getBeanType().getPackageName();
            if (!type.contains(".platform")
                    && !handler.getBeanType().getSimpleName().startsWith("Platform")) {
                operation.addParametersItem(new HeaderParameter()
                        .name("X-Organization-Id")
                        .required(false)
                        .schema(new StringSchema().format("uuid"))
                        .description("The organization to act in. Required when the caller belongs to more"
                                + " than one; otherwise their only organization is used."));
            }
            return operation;
        };
    }

    /**
     * Every error is an RFC 9457 problem detail (docs/api-design.md §3), so each error response the
     * controllers declare gets that body, and the ones every endpoint can return (§8.1: 401, 403,
     * 429) are added once here rather than on each method.
     */
    @Bean
    OperationCustomizer problemResponses() {
        Map<String, String> common = Map.of(
                "401", "unauthenticated: no valid access token",
                "403", "forbidden: the caller lacks the permission",
                "429", "rate-limited: see Retry-After");
        return (operation, handler) -> {
            ApiResponses responses = operation.getResponses();
            common.forEach(
                    (code, description) -> responses.putIfAbsent(code, new ApiResponse().description(description)));
            responses.forEach((code, response) -> {
                if (code.startsWith("4") || code.startsWith("5")) {
                    response.setContent(new Content()
                            .addMediaType(
                                    "application/problem+json",
                                    new MediaType().schema(new Schema<>().$ref("#/components/schemas/ProblemDetail"))));
                }
            });
            return operation;
        };
    }

    /** The error body (docs/api-design.md §3): RFC 9457 fields plus ReconFlow's {@code code} and {@code requestId}. */
    @Bean
    OpenApiCustomizer problemDetailSchema() {
        return openApi -> openApi.getComponents()
                .addSchemas(
                        "ProblemDetail",
                        new ObjectSchema()
                                .description("RFC 9457 problem detail")
                                .addProperty("type", new StringSchema().format("uri"))
                                .addProperty("title", new StringSchema())
                                .addProperty("status", new IntegerSchema())
                                .addProperty("detail", new StringSchema())
                                .addProperty("instance", new StringSchema())
                                .addProperty(
                                        "code",
                                        new StringSchema().description("Stable error code (api-design.md §3.1)"))
                                .addProperty(
                                        "requestId",
                                        new StringSchema().description("Also in the X-Request-Id response header")));
    }
}
