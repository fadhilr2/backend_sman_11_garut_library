package id.sch.sman11garut.library.common;

import org.springframework.http.HttpStatus;

@SuppressWarnings("serial")
public class AppException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public AppException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }

    public static AppException notFound(String message) {
        return new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }
    public static AppException badRequest(String code, String message) {
        return new AppException(HttpStatus.BAD_REQUEST, code, message);
    }
    public static AppException conflict(String code, String message) {
        return new AppException(HttpStatus.CONFLICT, code, message);
    }
    public static AppException forbidden(String message) {
        return new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }
    public static AppException unavailable(String code, String message) {
        return new AppException(HttpStatus.SERVICE_UNAVAILABLE, code, message);
    }
}