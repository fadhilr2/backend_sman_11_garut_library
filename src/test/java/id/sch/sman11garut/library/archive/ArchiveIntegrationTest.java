package id.sch.sman11garut.library.archive;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import id.sch.sman11garut.library.support.IntegrationTestBase;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class ArchiveIntegrationTest extends IntegrationTestBase {

    private long uploadImage(String token, String title, String extraJsonKey, String extraJsonValue) throws Exception {
        String metadata = extraJsonKey == null
                ? json("title", title)
                : json("title", title, extraJsonKey, extraJsonValue);
        String body = mvc.perform(as(upload("/api/archive", "foto.png", "image/png", uniquePng(800, 600), metadata), token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mediaType").value("IMAGE"))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    @Test
    void imagesDefaultToStaffAndGetAThumbnailAfterCommit() throws Exception {
        String teacher = newTeacher()[1];
        long id = uploadImage(teacher, "Upacara " + unique(), null, null);

        mvc.perform(as(get("/api/archive/" + id), teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("STAFF"));

        await("thumbnail", () -> JsonPath.read(getQuietly("/api/archive/" + id, teacher), "$.thumbnailPath") != null);
        mvc.perform(as(get("/api/archive/" + id), teacher))
                .andExpect(jsonPath("$.width").value(800))
                .andExpect(jsonPath("$.height").value(600));

        mvc.perform(as(get("/api/archive/" + id + "/thumbnail"), teacher))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/jpeg"));
        // A STAFF item's thumbnail is hidden from anonymous callers, like the item itself.
        mvc.perform(get("/api/archive/" + id + "/thumbnail")).andExpect(status().isNotFound());
        mvc.perform(get("/api/archive/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void filtersByMediaTypeAndEventDate() throws Exception {
        String teacher = newTeacher()[1];
        String word = "pentas" + unique();
        long inRange = uploadImage(teacher, word + " Mei", "eventDate", "2026-05-10");
        long outOfRange = uploadImage(teacher, word + " Agustus", "eventDate", "2026-08-17");

        mvc.perform(as(get("/api/archive"), teacher)
                        .param("q", word).param("mediaType", "IMAGE")
                        .param("from", "2026-05-01").param("to", "2026-05-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", Matchers.hasItem((int) inRange)))
                .andExpect(jsonPath("$.content[*].id", Matchers.not(Matchers.hasItem((int) outOfRange))));

        mvc.perform(as(get("/api/archive"), teacher).param("q", word).param("mediaType", "VIDEO"))
                .andExpect(jsonPath("$.totalElements").value(0));
    }
}
