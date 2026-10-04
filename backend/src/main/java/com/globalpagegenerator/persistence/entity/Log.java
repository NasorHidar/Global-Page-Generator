package com.globalpagegenerator.persistence.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Immutable audit record written once per execution attempt.
 * Never updated — append-only by design.
 */
@Entity
@Table(name = "log",
        indexes = @Index(name = "idx_log_request_id", columnList = "request_id"))
public class Log {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private RequestSave request;

    /** Human-readable action label, e.g. {@code "EXECUTE_API"}, {@code "SAVE_RESPONSE"}. */
    @Column(nullable = false, length = 64)
    private String action;

    /** HTTP status code returned by the upstream endpoint, or an internal synthetic code. */
    @Column(name = "status_code")
    private Integer statusCode;

    @Column(length = 1024)
    private String message;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Log() { /* JPA */ }

    private Log(Builder b) {
        this.request    = b.request;
        this.action     = b.action;
        this.statusCode = b.statusCode;
        this.message    = b.message;
    }

    public Long getId()             { return id; }
    public RequestSave getRequest() { return request; }
    public String getAction()       { return action; }
    public Integer getStatusCode()  { return statusCode; }
    public String getMessage()      { return message; }
    public Instant getCreatedAt()   { return createdAt; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private RequestSave request;
        private String action;
        private Integer statusCode;
        private String message;

        public Builder request(RequestSave r)   { this.request = r; return this; }
        public Builder action(String a)         { this.action = a; return this; }
        public Builder statusCode(Integer c)    { this.statusCode = c; return this; }
        public Builder message(String m)        { this.message = m; return this; }
        public Log build()                      { return new Log(this); }
    }
}
