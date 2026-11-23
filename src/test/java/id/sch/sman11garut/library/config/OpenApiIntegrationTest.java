package id.sch.sman11garut.library.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jayway.jsonpath.JsonPath;
import id.sch.sman11garut.library.support.IntegrationTestBase;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The roadmap's "OpenAPI spec matches the real endpoints" check: the generated document must
 * list exactly the endpoints of backend-spec.md §3. Adding or removing an endpoint without
 * updating the spec (and this list) fails the build.
 */
class OpenApiIntegrationTest extends IntegrationTestBase {

    /** backend-spec.md §3, one entry per method + path. */
    private static final Set<String> SPEC_ENDPOINTS = Set.of(
            // 3.1 Public: Books
            "GET /api/books", "GET /api/books/{id}", "GET /api/books/{id}/file",
            "GET /api/books/{id}/download", "GET /api/books/{id}/cover",
            // 3.2 Public: Archive
            "GET /api/archive", "GET /api/archive/{id}", "GET /api/archive/{id}/file",
            "GET /api/archive/{id}/download", "GET /api/archive/{id}/thumbnail",
            // 3.3 Public: Shared
            "GET /api/categories", "GET /api/meta/filters", "GET /api/health",
            // 3.4 Auth
            "POST /api/auth/login", "GET /api/auth/me", "POST /api/auth/change-password",
            // 3.5 Teacher + Librarian
            "POST /api/books", "PUT /api/books/{id}", "PUT /api/books/{id}/file", "PUT /api/books/{id}/cover",
            "DELETE /api/books/{id}",
            "GET /api/books/mine",
            "POST /api/archive", "PUT /api/archive/{id}", "DELETE /api/archive/{id}", "GET /api/archive/mine",
            // 3.6 Librarian only
            "GET /api/admin/users", "GET /api/admin/users/{id}", "POST /api/admin/users", "PUT /api/admin/users/{id}",
            "POST /api/admin/categories", "PUT /api/admin/categories/{id}", "DELETE /api/admin/categories/{id}",
            "POST /api/admin/books/{id}/restore", "POST /api/admin/archive/{id}/restore",
            "GET /api/admin/books/trash", "DELETE /api/admin/books/trash/{id}", "DELETE /api/admin/books/trash",
            "GET /api/admin/archive/trash", "DELETE /api/admin/archive/trash/{id}", "DELETE /api/admin/archive/trash",
            "GET /api/admin/stats",
            "POST /api/admin/import", "GET /api/admin/import/{jobId}", "GET /api/admin/import",
            "GET /api/admin/storage");

    @Test
    void documentedEndpointsMatchTheSpecExactly() throws Exception {
        String doc = getBody("/v3/api-docs", null);
        Map<String, Map<String, Object>> paths = JsonPath.read(doc, "$.paths");

        Set<String> actual = new TreeSet<>();
        paths.forEach((path, operations) -> operations.keySet().stream()
                .filter(method -> Set.of("get", "post", "put", "delete", "patch").contains(method))
                .forEach(method -> actual.add(method.toUpperCase() + " " + path)));

        assertEquals(new TreeSet<>(SPEC_ENDPOINTS), actual);
    }

    @Test
    void documentHasJwtAuthErrorsAndJsonMetadataParts() throws Exception {
        String doc = getBody("/v3/api-docs", null);

        assertEquals("bearer", JsonPath.read(doc, "$.components.securitySchemes.bearerAuth.scheme"));
        assertEquals("#/components/schemas/ApiError",
                JsonPath.read(doc, "$.paths['/api/books/{id}'].get.responses['404'].content['application/json'].schema.$ref"));
        // So Swagger UI's "Try it out" sends the metadata part as JSON.
        assertEquals("application/json", JsonPath.read(doc,
                "$.paths['/api/books'].post.requestBody.content['multipart/form-data'].encoding.metadata.contentType"));
    }
}
