package com.xupan.server.auth.web;

public record ErrorResponse(String code, String message, String requestId) {
}
