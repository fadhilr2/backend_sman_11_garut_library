package id.sch.sman11garut.library.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import id.sch.sman11garut.library.TestcontainersConfiguration;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base for integration tests: the full app on a MySQL 8 container (Testcontainers + Docker)
 * and a temporary storage folder. All subclasses share one Spring context and one database,
 * so tests use unique names and file contents instead of relying on an empty database.
 *
 * <p>Without Docker these tests are <b>skipped</b>, not failed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
public abstract class IntegrationTestBase {

    /** The seeded librarian (dev fallback credentials). */
    protected static final String ADMIN = "admin";
    protected static final String ADMIN_PASSWORD = "admin123";

    protected static final Path STORAGE_ROOT;
    protected static final Path IMPORT_DIR;

    static {
        try {
            Path base = Files.createTempDirectory("library-it-");
            STORAGE_ROOT = base.resolve("storage");
            IMPORT_DIR = base.resolve("import");
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("app.storage.root", STORAGE_ROOT::toString);
        registry.add("app.storage.import-dir", IMPORT_DIR::toString);
        registry.add("app.storage.auto-create-root", () -> "true");
        registry.add("app.storage.min-free-space", () -> "1MB");
        registry.add("spring.jpa.show-sql", () -> "false");
    }

    @Autowired
    protected MockMvc mvc;

    // ---- auth -------------------------------------------------------------------------------

    protected String login(String username, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("username", username, "password", password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }

    protected String adminToken() throws Exception {
        return login(ADMIN, ADMIN_PASSWORD);
    }

    /** Creates a teacher with a unique username; returns {username, token}. */
    protected String[] newTeacher() throws Exception {
        String username = "guru_" + unique();
        mvc.perform(post("/api/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("username", username, "password", "rahasia123",
                                "fullName", "Guru " + username, "role", "TEACHER")))
                .andExpect(status().isCreated());
        return new String[] {username, login(username, "rahasia123")};
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }

    protected static <T extends AbstractMockHttpServletRequestBuilder<?>> T as(T request, String token) {
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, bearer(token));
        }
        return request;
    }

    // ---- uploads ----------------------------------------------------------------------------

    /** A valid PDF whose bytes (and so checksum) are unique per call. */
    protected static byte[] uniquePdf() {
        return ("""
                %PDF-1.4
                % unique-""" + unique() + """

                1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj
                2 0 obj<</Type/Pages/Count 0/Kids[]>>endobj
                trailer<</Root 1 0 R>>
                %%EOF
                """).getBytes(StandardCharsets.US_ASCII);
    }

    /** A PNG with unique pixels. */
    protected static byte[] uniquePng(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, UUID.randomUUID().hashCode());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    protected static MockMultipartHttpServletRequestBuilder upload(String url, String filename, String type,
            byte[] content, String metadataJson) {
        return multipart(url)
                .file(new MockMultipartFile("file", filename, type, content))
                .file(new MockMultipartFile("metadata", "", MediaType.APPLICATION_JSON_VALUE,
                        metadataJson.getBytes(StandardCharsets.UTF_8)));
    }

    /** Uploads a PDF book and returns its id. */
    protected long uploadBook(String token, String title, String visibility) throws Exception {
        String body = mvc.perform(as(upload("/api/books", "buku.pdf", "application/pdf", uniquePdf(),
                                json("title", title, "visibility", visibility)), token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    // ---- misc -------------------------------------------------------------------------------

    protected static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** Minimal JSON object from key/value pairs (values are strings, or raw if they look numeric). */
    protected static String json(String... keyValues) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < keyValues.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            String value = keyValues[i + 1];
            sb.append('"').append(keyValues[i]).append("\":");
            if (value == null) {
                sb.append("null");
            } else if (value.matches("-?\\d+|true|false")) {
                sb.append(value);
            } else {
                sb.append('"').append(value.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            }
        }
        return sb.append('}').toString();
    }

    /** Waits for async work (download counters, thumbnails, imports). */
    protected static void await(String what, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }

    protected String getBody(String url, String token) throws Exception {
        return mvc.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url), token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** {@link #getBody} for use inside {@link #await} lambdas. */
    protected String getQuietly(String url, String token) {
        try {
            return getBody(url, token);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
