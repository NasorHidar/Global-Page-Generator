package com.globalpagegenerator.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when the upstream third-party API call fails (timeout, 5xx, network error).
 * Maps to HTTP 502 Bad Gateway — the backend received an invalid response from
 * the upstream endpoint.
 */
@ResponseStatus(HttpStatus.BAD_GATEWAY)
public class UpstreamApiException extends RuntimeException {

    private final int upstreamStatusCode;

    public UpstreamApiException(String message, int upstreamStatusCode, Throwable cause) {
        super(message, cause);
        this.upstreamStatusCode = upstreamStatusCode;
    }

    public UpstreamApiException(String message, Throwable cause) {
        this(message, -1, cause);
    }

    public int getUpstreamStatusCode() {
        return upstreamStatusCode;
    }
}
