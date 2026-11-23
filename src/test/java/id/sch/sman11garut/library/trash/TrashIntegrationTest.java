package id.sch.sman11garut.library.trash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import id.sch.sman11garut.library.support.IntegrationTestBase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class TrashIntegrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TrashPurgeJob trashPurgeJob;

    @Test
    void trashListsDeletedBooksNewestFirstWithPurgeDate() throws Exception {
        String[] teacher = newTeacher();
        String admin = adminToken();
        long first = uploadBook(teacher[1], "Sampah A " + unique(), "PUBLIC");
        long second = uploadBook(teacher[1], "Sampah B " + unique(), "STAFF");
        mvc.perform(as(delete("/api/books/" + first), teacher[1])).andExpect(status().isNoContent());
        Thread.sleep(1100); // deleted_at has second precision
        mvc.perform(as(delete("/api/books/" + second), teacher[1])).andExpect(status().isNoContent());

        String body = getBody("/api/admin/books/trash?size=50", admin);
        List<Number> ids = JsonPath.read(body, "$.content[*].id");
        int secondPos = indexOf(ids, second);
        int firstPos = indexOf(ids, first);
        assertTrue(secondPos >= 0 && firstPos > secondPos, "most recently deleted first");

        Map<String, Object> row = JsonPath.read(body, "$.content[" + secondPos + "]");
        assertEquals("EBOOK", row.get("kind"));
        assertEquals("buku.pdf", row.get("originalName"));
        assertEquals("application/pdf", row.get("mimeType"));
        assertEquals("Guru " + teacher[0], row.get("uploadedByName"));
        Instant deletedAt = Instant.parse((String) row.get("deletedAt"));
        Instant purgeAt = Instant.parse((String) row.get("purgeAt"));
        assertEquals(Duration.ofDays(30), Duration.between(deletedAt, purgeAt));

        // Active books are not in the trash; only librarians can see it.
        long active = uploadBook(teacher[1], "Aktif " + unique(), "PUBLIC");
        assertEquals(-1, indexOf(JsonPath.read(getBody("/api/admin/books/trash?size=50", admin), "$.content[*].id"), active));
        mvc.perform(as(get("/api/admin/books/trash"), teacher[1])).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/books/trash")).andExpect(status().isUnauthorized());
    }

    @Test
    void purgingABookRemovesRowAndFileAndAllowsReupload() throws Exception {
        String admin = adminToken();
        byte[] pdf = uniquePdf();
        long id = uploadBookBytes(admin, pdf);
        Path stored = storedPath("books", id);
        assertTrue(Files.exists(stored));

        // Not in the trash yet → 404, and nothing is deleted.
        mvc.perform(as(delete("/api/admin/books/trash/" + id), admin)).andExpect(status().isNotFound());
        mvc.perform(as(delete("/api/books/" + id), admin)).andExpect(status().isNoContent());

        // Duplicate check still matches the trashed file.
        mvc.perform(as(upload("/api/books", "lagi.pdf", "application/pdf", pdf, json("title", "Lagi " + unique())), admin))
                .andExpect(status().isConflict());

        mvc.perform(as(delete("/api/admin/books/trash/" + id), admin)).andExpect(status().isNoContent());
        assertFalse(Files.exists(stored));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM books WHERE id = ?", Integer.class, id));
        mvc.perform(as(delete("/api/admin/books/trash/" + id), admin)).andExpect(status().isNotFound());
        mvc.perform(as(post("/api/admin/books/" + id + "/restore"), admin)).andExpect(status().isNotFound());

        // Purged → the same file can be uploaded again.
        uploadBookBytes(admin, pdf);
    }

    @Test
    void emptyingTheArchiveTrashLeavesTheBookTrashAlone() throws Exception {
        String admin = adminToken();
        long bookId = uploadBook(admin, "Tetap " + unique(), "PUBLIC");
        mvc.perform(as(delete("/api/books/" + bookId), admin)).andExpect(status().isNoContent());

        long a = uploadPhoto(admin);
        long b = uploadPhoto(admin);
        await("thumbnails", () -> thumbnailPath(a) != null && thumbnailPath(b) != null);
        Path fileA = storedPath("archive_items", a);
        Path thumbA = STORAGE_ROOT.resolve(thumbnailPath(a));
        mvc.perform(as(delete("/api/archive/" + a), admin)).andExpect(status().isNoContent());
        mvc.perform(as(delete("/api/archive/" + b), admin)).andExpect(status().isNoContent());

        String archiveTrash = getBody("/api/admin/archive/trash", admin);
        assertEquals("IMAGE", JsonPath.read(archiveTrash, "$.content[0].kind"));

        String result = mvc.perform(as(delete("/api/admin/archive/trash"), admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(((Number) JsonPath.read(result, "$.purged")).intValue() >= 2);

        mvc.perform(as(get("/api/admin/archive/trash"), admin)).andExpect(jsonPath("$.totalElements").value(0));
        assertFalse(Files.exists(fileA));
        assertFalse(Files.exists(thumbA));
        assertTrue(indexOf(JsonPath.read(getBody("/api/admin/books/trash?size=50", admin), "$.content[*].id"), bookId) >= 0);

        // Emptying an empty trash is fine.
        mvc.perform(as(delete("/api/admin/archive/trash"), admin)).andExpect(jsonPath("$.purged").value(0));
    }

    @Test
    void nightlyJobPurgesOnlyExpiredItemsAndTheirDownloadLogs() throws Exception {
        String admin = adminToken();
        long expired = uploadBook(admin, "Lama " + unique(), "PUBLIC");
        long recent = uploadBook(admin, "Baru " + unique(), "PUBLIC");

        mvc.perform(get("/api/books/" + expired + "/download")).andExpect(status().isOk());
        await("download log", () -> downloadLogs(expired) > 0);

        mvc.perform(as(delete("/api/books/" + expired), admin)).andExpect(status().isNoContent());
        mvc.perform(as(delete("/api/books/" + recent), admin)).andExpect(status().isNoContent());
        jdbc.update("UPDATE books SET deleted_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(31))), expired);

        assertTrue(trashPurgeJob.purgeExpired() >= 1);

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM books WHERE id = ?", Integer.class, expired));
        assertEquals(0, downloadLogs(expired));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM books WHERE id = ?", Integer.class, recent));
    }

    // ---- helpers ----------------------------------------------------------------------------

    private long uploadBookBytes(String token, byte[] pdf) throws Exception {
        String body = mvc.perform(as(upload("/api/books", "buku.pdf", "application/pdf", pdf,
                                json("title", "Buku " + unique())), token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private long uploadPhoto(String token) throws Exception {
        String body = mvc.perform(as(upload("/api/archive", "foto.png", "image/png", uniquePng(64, 48),
                                json("title", "Foto " + unique())), token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private Path storedPath(String table, long id) {
        return STORAGE_ROOT.resolve(jdbc.queryForObject("SELECT stored_path FROM " + table + " WHERE id = ?", String.class, id));
    }

    private String thumbnailPath(long id) {
        return jdbc.queryForObject("SELECT thumbnail_path FROM archive_items WHERE id = ?", String.class, id);
    }

    private int downloadLogs(long bookId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM download_logs WHERE item_type = 'BOOK' AND item_id = ?", Integer.class, bookId);
    }

    private static int indexOf(List<Number> ids, long id) {
        for (int i = 0; i < ids.size(); i++) {
            if (ids.get(i).longValue() == id) {
                return i;
            }
        }
        return -1;
    }
}
