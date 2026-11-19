package id.sch.sman11garut.library.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.sch.sman11garut.library.book.BookType;
import id.sch.sman11garut.library.book.GradeLevel;
import id.sch.sman11garut.library.common.Visibility;
import id.sch.sman11garut.library.importer.ImportCsvReader.CsvRow;
import id.sch.sman11garut.library.importer.ImportRowMapper.ArchiveRow;
import id.sch.sman11garut.library.importer.ImportRowMapper.BookRow;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportRowMapperTest {

    @TempDir
    Path folder;

    private static CsvRow row(String... keyValues) {
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            values.put(keyValues[i].toLowerCase(), keyValues[i + 1]);
        }
        return new CsvRow(2, values);
    }

    @Test
    void mapsBookRowWithCaseInsensitiveEnums() {
        BookRow book = ImportRowMapper.toBook(row(
                "filename", "fisika.pdf", "title", "Fisika X", "year", "2023",
                "type", "teaching module", "grade", "x", "visibility", "staff", "category", "Fisika"));

        assertThat(book.filename()).isEqualTo("fisika.pdf");
        assertThat(book.categoryName()).isEqualTo("Fisika");
        assertThat(book.metadata().publicationYear()).isEqualTo(2023);
        assertThat(book.metadata().bookType()).isEqualTo(BookType.TEACHING_MODULE);
        assertThat(book.metadata().gradeLevel()).isEqualTo(GradeLevel.X);
        assertThat(book.metadata().visibility()).isEqualTo(Visibility.STAFF);
        assertThat(book.metadataWithCategory(7).categoryId()).isEqualTo(7);
    }

    @Test
    void blankOptionalCellsUseUploadDefaults() {
        BookRow book = ImportRowMapper.toBook(row("filename", "sub/Modul Kimia.pdf"));

        assertThat(book.metadata().title()).isEqualTo("Modul Kimia");
        assertThat(book.metadata().resolveBookType()).isEqualTo(BookType.EBOOK);
        assertThat(book.metadata().resolveGradeLevel()).isEqualTo(GradeLevel.ALL);
        assertThat(book.metadata().resolveVisibility()).isEqualTo(Visibility.PUBLIC);
        assertThat(book.categoryName()).isNull();
    }

    @Test
    void reportsInvalidValues() {
        assertThatThrownBy(() -> ImportRowMapper.toBook(row("filename", "a.pdf", "grade", "XIII")))
                .isInstanceOf(ImportRowException.class)
                .hasMessageContaining("grade 'XIII'")
                .hasMessageContaining("X, XI, XII, ALL");
        assertThatThrownBy(() -> ImportRowMapper.toBook(row("filename", "a.pdf", "year", "dua ribu")))
                .isInstanceOf(ImportRowException.class)
                .hasMessageContaining("year");
        assertThatThrownBy(() -> ImportRowMapper.toBook(row("title", "No file")))
                .isInstanceOf(ImportRowException.class)
                .hasMessageContaining("filename");
    }

    @Test
    void mapsArchiveRowAndDefaultsToStaff() {
        ArchiveRow item = ImportRowMapper.toArchive(row(
                "filename", "upacara.jpg", "eventDate", "2026-08-17", "category", "Kegiatan Sekolah"));

        assertThat(item.metadata().eventDate()).isEqualTo(LocalDate.of(2026, 8, 17));
        assertThat(item.metadata().resolveVisibility()).isEqualTo(Visibility.STAFF);
        assertThat(item.categoryName()).isEqualTo("Kegiatan Sekolah");
    }

    @Test
    void reportsInvalidEventDate() {
        assertThatThrownBy(() -> ImportRowMapper.toArchive(row("filename", "a.jpg", "eventDate", "17/08/2026")))
                .isInstanceOf(ImportRowException.class)
                .hasMessageContaining("YYYY-MM-DD");
    }

    @Test
    void resolveFileStaysInsideFolder() throws Exception {
        Files.writeString(folder.resolve("ok.pdf"), "x");

        assertThat(ImportRunner.resolveFile(folder, "ok.pdf")).isRegularFile();
        assertThatThrownBy(() -> ImportRunner.resolveFile(folder, "../secret.pdf"))
                .isInstanceOf(ImportRowException.class)
                .hasMessageContaining("inside the import folder");
        assertThatThrownBy(() -> ImportRunner.resolveFile(folder, "missing.pdf"))
                .isInstanceOf(ImportRowException.class)
                .hasMessageContaining("not found");
    }
}
