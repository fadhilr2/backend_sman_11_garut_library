package id.sch.sman11garut.library.importer;

import id.sch.sman11garut.library.archive.ArchiveMetadataRequest;
import id.sch.sman11garut.library.book.BookMetadataRequest;
import id.sch.sman11garut.library.book.BookType;
import id.sch.sman11garut.library.book.GradeLevel;
import id.sch.sman11garut.library.common.Visibility;
import id.sch.sman11garut.library.importer.ImportCsvReader.CsvRow;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Turns CSV rows into the same metadata requests the upload endpoints use, so defaults
 * and validation rules stay identical. Category names are returned separately; the
 * runner resolves them to ids. Pure class with no Spring or DB dependencies.
 *
 * <p>Books CSV: {@code filename,title,author,publisher,year,type,category,grade,curriculum,visibility,cover}
 * <br>Archive CSV: {@code filename,title,description,category,eventDate,visibility}
 * <br>Only {@code filename} is required; a blank title falls back to the file name.
 */
public final class ImportRowMapper {

    public static final List<String> REQUIRED_COLUMNS = List.of("filename");

    /** {@code coverFilename} is the optional {@code cover} column (null when blank). */
    public record BookRow(String filename, String coverFilename, String categoryName, BookMetadataRequest metadata) {
        public BookMetadataRequest metadataWithCategory(Integer categoryId) {
            BookMetadataRequest m = metadata;
            return new BookMetadataRequest(m.title(), m.author(), m.publisher(), m.publicationYear(), m.description(),
                    m.bookType(), categoryId, m.gradeLevel(), m.curriculum(), m.visibility());
        }
    }

    public record ArchiveRow(String filename, String categoryName, ArchiveMetadataRequest metadata) {
        public ArchiveMetadataRequest metadataWithCategory(Integer categoryId) {
            ArchiveMetadataRequest m = metadata;
            return new ArchiveMetadataRequest(m.title(), m.description(), categoryId, m.eventDate(), m.visibility());
        }
    }

    private ImportRowMapper() {
    }

    public static BookRow toBook(CsvRow row) {
        String filename = requireFilename(row);
        BookMetadataRequest metadata = new BookMetadataRequest(
                titleOrFilename(row, filename),
                row.get("author"),
                row.get("publisher"),
                parseYear(row.get("year")),
                null,
                parseEnum(BookType.class, row.get("type"), "type"),
                null,
                parseEnum(GradeLevel.class, row.get("grade"), "grade"),
                row.get("curriculum"),
                parseEnum(Visibility.class, row.get("visibility"), "visibility"));
        return new BookRow(filename, row.get("cover"), row.get("category"), metadata);
    }

    public static ArchiveRow toArchive(CsvRow row) {
        String filename = requireFilename(row);
        ArchiveMetadataRequest metadata = new ArchiveMetadataRequest(
                titleOrFilename(row, filename),
                row.get("description"),
                null,
                parseDate(row.get("eventDate")),
                parseEnum(Visibility.class, row.get("visibility"), "visibility"));
        return new ArchiveRow(filename, row.get("category"), metadata);
    }

    private static String requireFilename(CsvRow row) {
        String filename = row.get("filename");
        if (filename == null) {
            throw new ImportRowException("The 'filename' column is empty");
        }
        return filename;
    }

    private static String titleOrFilename(CsvRow row, String filename) {
        String title = row.get("title");
        if (title != null) {
            return title;
        }
        String base = filename.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        int dot = base.lastIndexOf('.');
        return dot > 0 ? base.substring(0, dot) : base;
    }

    private static Integer parseYear(String value) {
        if (value == null) {
            return null;
        }
        int year;
        try {
            year = Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            year = -1;
        }
        if (year < 1000 || year > 9999) {
            throw new ImportRowException("Invalid year '" + value + "' (expected 4 digits, e.g. 2023)");
        }
        return year;
    }

    private static LocalDate parseDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ex) {
            throw new ImportRowException("Invalid eventDate '" + value + "' (expected YYYY-MM-DD, e.g. 2026-08-17)");
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String column) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        try {
            return Enum.valueOf(type, normalized);
        } catch (IllegalArgumentException ex) {
            String allowed = Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.joining(", "));
            throw new ImportRowException("Invalid " + column + " '" + value + "' (allowed: " + allowed + ")");
        }
    }
}
