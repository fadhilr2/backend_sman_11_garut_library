package id.sch.sman11garut.library.importer;

/** A single CSV row could not be imported. The message is shown to the librarian in the error report. */
@SuppressWarnings("serial")
public class ImportRowException extends RuntimeException {

    public ImportRowException(String message) {
        super(message);
    }
}
