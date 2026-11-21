package id.sch.sman11garut.library.health;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.sch.sman11garut.library.storage.StorageDirectories;
import id.sch.sman11garut.library.support.IntegrationTestBase;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class HealthIntegrationTest extends IntegrationTestBase {

    @Test
    void healthIsUpAndTurnsDegradedWhenTheDriveDisappears() throws Exception {
        long bookId = uploadBook(adminToken(), "Kesehatan " + unique(), "PUBLIC");

        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database").value("UP"))
                .andExpect(jsonPath("$.storage").value("UP"));

        // Simulate an unplugged drive by removing the marker (restored afterwards: the
        // Spring context and storage folder are shared with the other integration tests).
        Path marker = STORAGE_ROOT.resolve(StorageDirectories.MARKER);
        Files.delete(marker);
        try {
            mvc.perform(get("/api/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DEGRADED"))
                    .andExpect(jsonPath("$.storage").value("UNAVAILABLE"));
            // Browsing only needs MySQL.
            mvc.perform(get("/api/books/" + bookId)).andExpect(status().isOk());
            mvc.perform(get("/api/books")).andExpect(status().isOk());
            mvc.perform(get("/api/admin/storage").header("Authorization", bearer(adminToken())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.available").value(false));
        } finally {
            Files.createFile(marker);
        }

        mvc.perform(get("/api/health")).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void fileRequestsReturn503WhileTheDriveIsGone() throws Exception {
        long bookId = uploadBook(adminToken(), "Offline " + unique(), "PUBLIC");
        Path marker = STORAGE_ROOT.resolve(StorageDirectories.MARKER);
        Path books = STORAGE_ROOT.resolve("books");
        Path parked = STORAGE_ROOT.resolveSibling("books-parked-" + unique());

        // Both the marker and the files are gone, as with a real unplug.
        Files.delete(marker);
        Files.move(books, parked);
        try {
            mvc.perform(get("/api/books/" + bookId + "/file"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"));
            mvc.perform(get("/api/books/" + bookId + "/cover"))
                    .andExpect(status().isOk()); // placeholder keeps the catalog browsable
        } finally {
            Files.move(parked, books);
            Files.createFile(marker);
        }
        mvc.perform(get("/api/books/" + bookId + "/file")).andExpect(status().isOk());
    }

    @Test
    void storageReportIsForLibrariansOnly() throws Exception {
        mvc.perform(get("/api/admin/storage").header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.books.count").isNumber())
                .andExpect(jsonPath("$.archive.IMAGE").exists());
        mvc.perform(get("/api/admin/storage").header("Authorization", bearer(newTeacher()[1])))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/storage")).andExpect(status().isUnauthorized());
    }
}
