package id.sch.sman11garut.library.importer;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * Reads an import CSV into rows keyed by lower-cased header name.
 * <ul>
 *   <li>UTF-8, with an optional BOM (Excel adds one).</li>
 *   <li>Delimiter auto-detected from the header line: {@code ;} (Excel with Indonesian locale) or {@code ,}.</li>
 *   <li>Quoted fields may contain delimiters and line breaks.</li>
 *   <li>Empty lines are ignored.</li>
 * </ul>
 * Pure class with no Spring or DB dependencies.
 */
public final class ImportCsvReader {

    private static final char BOM = '﻿';

    /** One data row. {@code rowNumber} matches the spreadsheet row (the header is row 1). */
    public record CsvRow(int rowNumber, Map<String, String> values) {

        /** Trimmed value for a column, or {@code null} when the column is missing or blank. */
        public String get(String column) {
            String value = values.get(column.toLowerCase(Locale.ROOT));
            if (value == null) {
                return null;
            }
            String trimmed = value.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
    }

    private ImportCsvReader() {
    }

    public static List<CsvRow> read(Path csvFile, List<String> requiredColumns) throws IOException {
        return parse(Files.readString(csvFile, StandardCharsets.UTF_8), requiredColumns);
    }

    public static List<CsvRow> parse(String content, List<String> requiredColumns) throws IOException {
        if (!content.isEmpty() && content.charAt(0) == BOM) {
            content = content.substring(1);
        }
        if (content.isBlank()) {
            throw new IllegalArgumentException("The CSV file is empty");
        }

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setDelimiter(detectDelimiter(content))
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setIgnoreSurroundingSpaces(true)
                .get();

        try (CSVParser parser = CSVParser.parse(new StringReader(content), format)) {
            List<String> headers = parser.getHeaderNames().stream()
                    .map(h -> h.trim().toLowerCase(Locale.ROOT))
                    .toList();
            for (String required : requiredColumns) {
                if (!headers.contains(required.toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("The CSV header is missing the required column '" + required
                            + "'. Found columns: " + String.join(", ", headers));
                }
            }

            List<CsvRow> rows = new ArrayList<>();
            int dataIndex = 0;
            for (CSVRecord record : parser) {
                dataIndex++;
                Map<String, String> values = new LinkedHashMap<>();
                for (int i = 0; i < headers.size() && i < record.size(); i++) {
                    values.put(headers.get(i), record.get(i));
                }
                if (values.values().stream().allMatch(v -> v == null || v.isBlank())) {
                    continue; // a line of only delimiters, e.g. ";;;;" from Excel
                }
                rows.add(new CsvRow(dataIndex + 1, values));
            }
            return rows;
        } catch (IllegalStateException | UncheckedIOException ex) {
            throw new IllegalArgumentException("The CSV file could not be read: " + ex.getMessage(), ex);
        }
    }

    static char detectDelimiter(String content) {
        int end = content.indexOf('\n');
        String header = end >= 0 ? content.substring(0, end) : content;
        long semicolons = header.chars().filter(c -> c == ';').count();
        long commas = header.chars().filter(c -> c == ',').count();
        return semicolons > commas ? ';' : ',';
    }
}
