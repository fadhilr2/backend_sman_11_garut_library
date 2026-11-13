package id.sch.sman11garut.library.storage;

public enum StorageCategory {
    BOOK("books"),
    COVER("books"),
    ARCHIVE("archive"),
    THUMBNAIL("thumbnails");

    private final String directory;

    StorageCategory(String directory) {
        this.directory = directory;
    }

    public String directory() {
        return directory;
    }
}
