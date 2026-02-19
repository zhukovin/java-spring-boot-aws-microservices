package com.orderflow.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        int status,
        String error,
        String message,
        String path,
        Instant timestamp,
        List<FieldError> fieldErrors
) {
    public record FieldError(
            String field,
            String message
    ) {}

    public static ErrorResponse of(int status, String error, String message, String path) {
        return new ErrorResponse(status, error, message, path, Instant.now(), null);
    }

    public static ErrorResponse of(int status, String error, String message, String path,
                                   List<FieldError> fieldErrors) {
        return new ErrorResponse(status, error, message, path, Instant.now(), fieldErrors);
    }
}
