package id.sch.sman11garut.library.importer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import id.sch.sman11garut.library.support.IntegrationTestBase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class ImportIntegrationTest extends IntegrationTestBase {

    @Test
    void importsBooksFromTheFolderAndReportsEachRow() throws Exception {
        Path books = Files.createDirectories(IMPORT_DIR.resolve("books"));
        byte[] pdf = uniquePdf();
        Files.write(books.resolve("fisika.pdf"), pdf);
        Files.write(books.resolve("salinan.pdf"), pdf); // same content → skipped as duplicate
        String category = "Kategori Impor " + unique();
        Files.writeString(books.resolve("books.csv"), String.join("\n",
                "filename,title,author,publisher,year,type,category,grade,curriculum,visibility",
                "fisika.pdf,\"Fisika, Jilid 1\",Budi,Erlangga,2023,EBOOK," + category + ",X,Merdeka,PUBLIC",
                "salinan.pdf,Salinan,,,,,,,,",
                "hilang.pdf,Tidak Ada,,,,,,,,"));

        String admin = adminToken();
        String started = mvc.perform(as(post("/api/admin/import"), admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("target", "BOOK")))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        long jobId = ((Number) JsonPath.read(started, "$.id")).longValue();

        await("import job", () -> !"RUNNING".equals(
                JsonPath.read(getQuietly("/api/admin/import/" + jobId, admin), "$.status")));

        String job = getBody("/api/admin/import/" + jobId, admin);
        assertEquals("COMPLETED", JsonPath.read(job, "$.status"));
        assertEquals(3, (int) JsonPath.read(job, "$.totalRows"));
        assertEquals(1, (int) JsonPath.read(job, "$.succeeded"));
        assertEquals(1, (int) JsonPath.read(job, "$.skipped"));
        assertEquals(1, (int) JsonPath.read(job, "$.failed"));
        List<String> errors = JsonPath.read(job, "$.errors");
        assertTrue(errors.get(0).contains("hilang.pdf"), errors.toString());

        // Unknown category names are created automatically.
        assertTrue(getBody("/api/categories?type=BOOK", null).contains(category));
    }

    @Test
    void attachesCoversFromTheCoverColumn() throws Exception {
        Path books = Files.createDirectories(IMPORT_DIR.resolve("books").resolve("sampul"));
        byte[] withCover = uniquePdf();
        byte[] cover = uniquePng(30, 40);
        byte[] missingCover = uniquePdf();
        byte[] badCover = uniquePdf();
        Files.write(books.resolve("dengan.pdf"), withCover);
        Files.write(books.resolve("dengan.png"), cover);
        Files.write(books.resolve("tanpa-sampul.pdf"), missingCover);
        Files.write(books.resolve("rusak.pdf"), badCover);
        Files.write(books.resolve("rusak.png"), uniquePdf()); // a PDF named .png → rejected
        String title = "Bersampul " + unique();
        Files.writeString(IMPORT_DIR.resolve("books").resolve("books.csv"), String.join("\n",
                "filename,title,cover",
                "sampul/dengan.pdf," + title + ",sampul/dengan.png",
                "sampul/tanpa-sampul.pdf,Tanpa,sampul/hilang.png",
                "sampul/rusak.pdf,Rusak,sampul/rusak.png"));

        String admin = adminToken();
        String started = mvc.perform(as(post("/api/admin/import"), admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("target", "BOOK")))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        long jobId = ((Number) JsonPath.read(started, "$.id")).longValue();
        await("import job", () -> !"RUNNING".equals(
                JsonPath.read(getQuietly("/api/admin/import/" + jobId, admin), "$.status")));

        String job = getBody("/api/admin/import/" + jobId, admin);
        assertEquals(1, (int) JsonPath.read(job, "$.succeeded"), job);
        assertEquals(2, (int) JsonPath.read(job, "$.failed"), job);
        List<String> errors = JsonPath.read(job, "$.errors");
        assertTrue(errors.get(0).contains("Cover 'sampul/hilang.png'"), errors.toString());
        assertTrue(errors.get(1).contains("Cover: ") && errors.get(1).contains("rusak.pdf"), errors.toString());

        // The imported book serves the cover from the CSV.
        String found = getBody("/api/books?q=" + title.replace(" ", "+"), admin);
        long id = ((Number) JsonPath.read(found, "$.content[0].id")).longValue();
        assertTrue((boolean) JsonPath.read(found, "$.content[0].hasCover"));
        assertArrayEquals(cover, mvc.perform(get("/api/books/" + id + "/cover"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());

        // A rejected cover leaves no stored copy of its book file behind.
        assertFalse(storedFileWithContent(badCover), "book file is cleaned up when its cover is rejected");
    }
}
