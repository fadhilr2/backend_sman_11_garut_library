package id.sch.sman11garut.library.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.sch.sman11garut.library.importer.ImportCsvReader.CsvRow;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImportCsvReaderTest {

    private static final List<String> REQUIRED = List.of("filename");

    @Test
    void parsesCommaSeparatedWithQuotedCommas() throws Exception {
        String csv = """
                filename,title,author
                fisika.pdf,"Fisika, Jilid 1",Budi
                kimia.pdf,Kimia Dasar,
                """;

        List<CsvRow> rows = ImportCsvReader.parse(csv, REQUIRED);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("title")).isEqualTo("Fisika, Jilid 1");
        assertThat(rows.get(0).rowNumber()).isEqualTo(2);
        assertThat(rows.get(1).get("author")).isNull();
        assertThat(rows.get(1).rowNumber()).isEqualTo(3);
    }

    @Test
    void detectsSemicolonsAndStripsBomAndIsCaseInsensitive() throws Exception {
        String csv = "﻿FileName;Title;EventDate\r\nfoto.jpg;Upacara; 2026-08-17 \r\n";

        List<CsvRow> rows = ImportCsvReader.parse(csv, REQUIRED);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("filename")).isEqualTo("foto.jpg");
        assertThat(rows.get(0).get("eventDate")).isEqualTo("2026-08-17");
    }

    @Test
    void skipsBlankLinesAndDelimiterOnlyLines() throws Exception {
        String csv = "filename;title\na.pdf;A\n\n;\nb.pdf;B\n";

        List<CsvRow> rows = ImportCsvReader.parse(csv, REQUIRED);

        assertThat(rows).extracting(r -> r.get("filename")).containsExactly("a.pdf", "b.pdf");
    }

    @Test
    void shortRowsLeaveMissingColumnsNull() throws Exception {
        List<CsvRow> rows = ImportCsvReader.parse("filename,title,author\na.pdf\n", REQUIRED);

        assertThat(rows.get(0).get("title")).isNull();
    }

    @Test
    void rejectsMissingRequiredColumn() {
        assertThatThrownBy(() -> ImportCsvReader.parse("title,author\nA,B\n", REQUIRED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("filename");
    }

    @Test
    void rejectsEmptyFile() {
        assertThatThrownBy(() -> ImportCsvReader.parse("﻿  \n", REQUIRED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }
}
