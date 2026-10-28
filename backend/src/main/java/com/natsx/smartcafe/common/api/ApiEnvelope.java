package com.natsx.smartcafe.common.api;

public record ApiEnvelope<T>(T data, Meta meta) {
    public record Meta(String requestId) {}

    public static <T> ApiEnvelope<T> of(T data, String requestId) {
        return new ApiEnvelope<>(data, new Meta(requestId));
    }
}
