package id.sch.sman11garut.library.importer;

/**
 * What a bulk import creates. Each target has its own subfolder and CSV inside {@code app.storage.import-dir}:
 * {@code books/books.csv} and {@code archive/archive.csv}.
 */
public enum ImportTarget {
    BOOK("books", "books.csv"),
    ARCHIVE("archive", "archive.csv");

    private final String folder;
    private final String csvFileName;

    ImportTarget(String folder, String csvFileName) {
        this.folder = folder;
        this.csvFileName = csvFileName;
    }

    public String folder() {
        return folder;
    }

    public String csvFileName() {
        return csvFileName;
    }
}
