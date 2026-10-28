package com.natsx.smartcafe.common.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ApiFailureException.class)
    ResponseEntity<ApiError> apiFailure(ApiFailureException ex, HttpServletRequest request) {
        return ResponseEntity.status(ex.status())
            .header("Cache-Control", "no-store")
            .body(ApiError.of(ex.code(), ex.getMessage(), RequestIdFilter.get(request), Map.of()));
    }

    @ExceptionHandler({
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.bind.MissingRequestHeaderException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class
    })
    ResponseEntity<ApiError> malformed(Exception ex, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiError.of("VALIDATION_ERROR",
            "Malformed or missing request field", RequestIdFilter.get(request), Map.of()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalid(MethodArgumentNotValidException ex, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiError.of("VALIDATION_ERROR",
            "Request validation failed", RequestIdFilter.get(request), Map.of()));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiError> databaseUnavailable(DataAccessException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiError.of("DB_UNAVAILABLE",
            "Service temporarily unavailable", RequestIdFilter.get(request), Map.of()));
    }
}
