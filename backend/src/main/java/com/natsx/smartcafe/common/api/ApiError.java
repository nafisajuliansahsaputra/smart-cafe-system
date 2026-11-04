package com.natsx.smartcafe.common.api;

import java.util.Map;

public record ApiError(ErrorDetail error) {
    public record ErrorDetail(String code, String message, String requestId, Map<String, String> details) {}

    public static ApiError of(String code, String message, String requestId, Map<String, String> details) {
        return new ApiError(new ErrorDetail(code, message, requestId, details));
    }
}
