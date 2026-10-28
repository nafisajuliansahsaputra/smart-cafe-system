package com.natsx.smartcafe.common.api;

import org.springframework.http.HttpStatus;

public final class ApiFailureException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ApiFailureException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}
