package id.sch.sman11garut.library.config;

import id.sch.sman11garut.library.common.ApiError;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI document for the front-end team: {@code /v3/api-docs} and {@code /swagger-ui.html}
 * (dev only; disabled in the prod profile).
 */
@Configuration
public class OpenApiConfig {

    static final String BEARER = "bearerAuth";

    /** Error statuses any endpoint may return, all with the standard {@link ApiError} body. */
    private static final Map<String, String> ERROR_RESPONSES = Map.of(
            "400", "Invalid request (`VALIDATION_FAILED`, `INVALID_FILE_TYPE`, ...)",
            "401", "Missing or invalid token on a protected endpoint (`UNAUTHORIZED`)",
            "403", "Wrong role, or not the owner (`FORBIDDEN`)",
            "404", "Not found, soft-deleted, or a STAFF item requested anonymously (`NOT_FOUND`)",
            "409", "Conflict (`DUPLICATE_FILE`, `DUPLICATE_USERNAME`, ...)",
            "503", "Storage full or unavailable (`STORAGE_FULL`, `STORAGE_UNAVAILABLE`)");

    @Bean
    public OpenAPI libraryOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Perpustakaan Digital SMAN 11 Garut API")
                        .version("1.0")
                        .description("""
                                Offline school library API. Students are anonymous; teachers and librarians \
                                log in with `POST /api/auth/login` and send `Authorization: Bearer <token>` \
                                (valid 8 hours). Public endpoints also accept a token, which unlocks STAFF items. \
                                Every error uses the `ApiError` body.\
                                """))
                .servers(java.util.List.of(new Server().url("/").description("Same origin (Nginx)")))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }

    /** Registers the {@code ApiError} schema and adds the shared error responses to every operation. */
    @Bean
    public OpenApiCustomizer errorResponsesCustomizer() {
        return openApi -> {
            @SuppressWarnings("rawtypes")
            Map<String, Schema> schemas = ModelConverters.getInstance().read(ApiError.class);
            schemas.forEach(openApi.getComponents()::addSchemas);

            Content errorBody = new Content().addMediaType("application/json",
                    new MediaType().schema(new Schema<>().$ref("#/components/schemas/ApiError")));

            openApi.getPaths().values().forEach(path -> path.readOperations().forEach(operation -> {
                ApiResponses responses = operation.getResponses();
                ERROR_RESPONSES.forEach((status, description) -> {
                    if (!responses.containsKey(status)) {
                        responses.addApiResponse(status,
                                new ApiResponse().description(description).content(errorBody));
                    }
                });
            }));
        };
    }
}
