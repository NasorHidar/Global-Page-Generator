package com.globalpagegenerator.web;

import com.globalpagegenerator.exception.ResourceNotFoundException;
import com.globalpagegenerator.exception.UpstreamApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;
import java.util.stream.Collectors;

/**
 * Translates application exceptions into RFC 9457 {@link ProblemDetail}
 * responses. Using {@link ProblemDetail} keeps error payloads consistent
 * and aligns with the Spring Boot 3.x standard.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setType(URI.create("https://errors.globalpagegenerator.internal/not-found"));
        problem.setTitle("Resource Not Found");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(UpstreamApiException.class)
    public ProblemDetail handleUpstreamError(UpstreamApiException ex) {
        LOG.error("Upstream API failure: {}", ex.getMessage(), ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, ex.getMessage());
        problem.setType(URI.create("https://errors.globalpagegenerator.internal/upstream-error"));
        problem.setTitle("Upstream API Error");
        problem.setProperty("upstreamStatusCode", ex.getUpstreamStatusCode());
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setType(URI.create("https://errors.globalpagegenerator.internal/validation-error"));
        problem.setTitle("Validation Failed");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex) {
        LOG.error("Unhandled exception", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");
        problem.setType(URI.create("https://errors.globalpagegenerator.internal/internal-error"));
        problem.setTitle("Internal Server Error");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }
}
