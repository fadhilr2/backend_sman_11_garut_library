package id.sch.sman11garut.library.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(int status, String code, String message, Map<String, String> fieldErrors) {

    public ApiError(int status, String code, String message) {
        this(status, code, message, null);
    }
}