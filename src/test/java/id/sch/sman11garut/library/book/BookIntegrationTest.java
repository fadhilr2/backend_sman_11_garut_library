package id.sch.sman11garut.library.book;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import id.sch.sman11garut.library.support.IntegrationTestBase;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

class BookIntegrationTest extends IntegrationTestBase {

    @Test
    void teacherUploadsAndAnonymousFindsAndReadsIt() throws Exception {
        String teacher = newTeacher()[1];
        String word = "kuantum" + unique();
        long id = uploadBook(teacher, "Fisika " + word, "PUBLIC");

        // FULLTEXT path (3+ characters)
        mvc.perform(get("/api/books").param("q", word))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", Matchers.hasItem((int) id)));
        // LIKE path (under 3 characters)
        mvc.perform(get("/api/books").param("q", "Fi").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", Matchers.hasItem((int) id)));

        // Inline reading with a Range request
        mvc.perform(get("/api/books/" + id + "/file").header(HttpHeaders.RANGE, "bytes=0-3"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, Matchers.startsWith("bytes 0-3/")))
                .andExpect(content().string("%PDF"));
    }

    @Test
    void downloadsAreCountedAndNamesAreEncoded() throws Exception {
        String teacher = newTeacher()[1];
        String body = mvc.perform(as(upload("/api/books", "Matematika – Kelas X.pdf", "application/pdf", uniquePdf(),
                                json("title", "Matematika " + unique())), teacher))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(body, "$.id")).longValue();

        mvc.perform(get("/api/books/" + id + "/download"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        Matchers.containsString("filename*=UTF-8''Matematika%20%E2%80%93%20Kelas%20X.pdf")));

        await("download counter", () ->
                ((Number) JsonPath.read(getQuietly("/api/books/" + id, null), "$.downloadCount")).intValue() == 1);
    }

    @Test
    void duplicatesAreRejectedEvenWhenTheOriginalIsInTheTrash() throws Exception {
        String teacher = newTeacher()[1];
        byte[] pdf = uniquePdf();
        String body = mvc.perform(as(upload("/api/books", "a.pdf", "application/pdf", pdf, json("title", "Asli")), teacher))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(body, "$.id")).longValue();

        mvc.perform(as(upload("/api/books", "b.pdf", "application/pdf", pdf, json("title", "Salinan")), teacher))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_FILE"));

        mvc.perform(as(delete("/api/books/" + id), teacher)).andExpect(status().isNoContent());
        mvc.perform(as(upload("/api/books", "c.pdf", "application/pdf", pdf, json("title", "Lagi")), teacher))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_FILE"));
    }

    @Test
    void aTeacherCannotChangeAnotherTeachersBook() throws Exception {
        long id = uploadBook(newTeacher()[1], "Milik Guru A " + unique(), "PUBLIC");
        String otherTeacher = newTeacher()[1];

        mvc.perform(as(put("/api/books/" + id), otherTeacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("title", "Diubah")))
                .andExpect(status().isForbidden());
        mvc.perform(as(delete("/api/books/" + id), otherTeacher)).andExpect(status().isForbidden());

        // A librarian may.
        mvc.perform(as(put("/api/books/" + id), adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("title", "Diubah petugas")))
                .andExpect(status().isOk());
    }

    @Test
    void staffBooksAndTheirCoversAreHiddenFromAnonymousCallers() throws Exception {
        String teacher = newTeacher()[1];
        byte[] cover = uniquePng(60, 80);
        String body = mvc.perform(as(multipart("/api/books")
                                .file(new MockMultipartFile("file", "soal.pdf", "application/pdf", uniquePdf()))
                                .file(new MockMultipartFile("cover", "sampul.png", "image/png", cover))
                                .file(new MockMultipartFile("metadata", "", MediaType.APPLICATION_JSON_VALUE,
                                        json("title", "Bank Soal " + unique(), "visibility", "STAFF").getBytes())),
                        teacher))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(body, "$.id")).longValue();

        for (String path : List.of("", "/file", "/download", "/cover")) {
            mvc.perform(get("/api/books/" + id + path)).andExpect(status().isNotFound());
        }

        // Staff get the cover with its real type (PNG, not mislabelled as JPEG).
        MvcResult result = mvc.perform(as(get("/api/books/" + id + "/cover"), teacher))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/png"))
                .andReturn();
        assertArrayEquals(cover, result.getResponse().getContentAsByteArray());
    }

    @Test
    void replacingTheFileServesTheNewOneAndRemovesTheOld() throws Exception {
        String teacher = newTeacher()[1];
        byte[] oldPdf = uniquePdf();
        String body = mvc.perform(as(upload("/api/books", "lama.pdf", "application/pdf", oldPdf, json("title", "Modul")), teacher))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(body, "$.id")).longValue();
        assertTrue(storedFileWithContent(oldPdf), "old file is on disk before the replace");

        byte[] newPdf = uniquePdf();
        mvc.perform(as(multipart("/api/books/" + id + "/file")
                                .file(new MockMultipartFile("file", "baru.pdf", "application/pdf", newPdf))
                                .with(request -> {
                                    request.setMethod("PUT");
                                    return request;
                                }), teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalName").value("baru.pdf"));

        MvcResult file = mvc.perform(get("/api/books/" + id + "/file")).andExpect(status().isOk()).andReturn();
        assertArrayEquals(newPdf, file.getResponse().getContentAsByteArray());
        assertFalse(storedFileWithContent(oldPdf), "old file is deleted after the commit");
    }

    @Test
    void aCoverCanBeAddedLaterAndReplaced() throws Exception {
        String teacher = newTeacher()[1];
        long id = uploadBook(teacher, "Tanpa Sampul " + unique(), "PUBLIC");
        mvc.perform(get("/api/books/" + id)).andExpect(jsonPath("$.hasCover").value(false));

        byte[] first = uniquePng(40, 60);
        mvc.perform(as(putCover(id, "sampul.png", "image/png", first), teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasCover").value(true));
        assertArrayEquals(first, mvc.perform(get("/api/books/" + id + "/cover"))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/png"))
                .andReturn().getResponse().getContentAsByteArray());

        // Replacing it serves the new image and deletes the old file after the commit.
        byte[] second = uniquePng(50, 70);
        mvc.perform(as(putCover(id, "sampul-baru.png", "image/png", second), adminToken()))
                .andExpect(status().isOk());
        assertArrayEquals(second, mvc.perform(get("/api/books/" + id + "/cover"))
                .andReturn().getResponse().getContentAsByteArray());
        assertFalse(storedFileWithContent(first), "old cover is deleted after the commit");

        // Not an image -> 400; another teacher -> 403. The cover stays as it was.
        mvc.perform(as(putCover(id, "bukan.png", "image/png", uniquePdf()), teacher))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FILE_TYPE"));
        mvc.perform(as(putCover(id, "lain.png", "image/png", uniquePng(30, 30)), newTeacher()[1]))
                .andExpect(status().isForbidden());
        assertTrue(storedFileWithContent(second), "current cover is untouched by rejected uploads");
    }

    private static MockMultipartHttpServletRequestBuilder putCover(long id, String name, String type, byte[] content) {
        return multipart("/api/books/" + id + "/cover")
                .file(new MockMultipartFile("cover", name, type, content))
                .with(request -> {
                    request.setMethod("PUT");
                    return request;
                });
    }

    @Test
    void softDeleteHidesTheBookAndRestoreBringsItBack() throws Exception {
        String teacher = newTeacher()[1];
        long id = uploadBook(teacher, "Sejarah " + unique(), "PUBLIC");

        mvc.perform(as(delete("/api/books/" + id), teacher)).andExpect(status().isNoContent());
        mvc.perform(get("/api/books/" + id)).andExpect(status().isNotFound());

        mvc.perform(as(post("/api/admin/books/" + id + "/restore"), teacher)).andExpect(status().isForbidden());
        mvc.perform(as(post("/api/admin/books/" + id + "/restore"), adminToken())).andExpect(status().isOk());
        mvc.perform(get("/api/books/" + id)).andExpect(status().isOk());
    }

    @Test
    void searchFiltersByGrade() throws Exception {
        String teacher = newTeacher()[1];
        String word = "kimia" + unique();
        String body = mvc.perform(as(upload("/api/books", "k.pdf", "application/pdf", uniquePdf(),
                                json("title", "Kimia " + word, "gradeLevel", "XI")), teacher))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(body, "$.id")).longValue();

        mvc.perform(get("/api/books").param("q", word).param("grade", "XI"))
                .andExpect(jsonPath("$.content[*].id", Matchers.hasItem((int) id)));
        String other = getBody("/api/books?q=" + word + "&grade=X", null);
        assertEquals(0, ((Number) JsonPath.read(other, "$.totalElements")).intValue());
    }
}
