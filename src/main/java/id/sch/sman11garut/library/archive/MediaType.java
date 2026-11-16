package id.sch.sman11garut.library.archive;

public enum MediaType {
    IMAGE,
    VIDEO,
    DOCUMENT;

    public static MediaType resolveFromMime(String mime) {
        if (mime == null) return DOCUMENT;
        String m = mime.toLowerCase();
        if (m.startsWith("image/")) return IMAGE;
        if (m.startsWith("video/")) return VIDEO;
        return DOCUMENT;
    }
}
