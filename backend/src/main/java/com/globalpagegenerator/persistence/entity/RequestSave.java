package com.globalpagegenerator.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Persists the raw third-party API request/response pair for a single NID
 * execution. Both {@code requestPayload} and {@code responseData} are stored
 * as PostgreSQL {@code jsonb} columns — this preserves the original byte-for-byte
 * structure and enables server-side JSON operators in future analytics queries.
 *
 * <p><b>JSONB Mapping Strategy:</b>
 * <ol>
 *   <li>The field type is {@link JsonNode} — the most flexible Jackson type,
 *       capable of holding any valid JSON (object, array, scalar).</li>
 *   <li>The {@code @Column(columnDefinition = "jsonb")} annotation instructs
 *       Hibernate to declare the column with the correct PostgreSQL type during
 *       schema generation.</li>
 *   <li>{@code @ColumnTransformer} casts the bound JDBC parameter to {@code jsonb}
 *       on write. Without this, Hibernate sends the value as {@code text}, causing
 *       a type-mismatch error on PostgreSQL.</li>
 *   <li>The actual String↔JsonNode conversion is handled by the auto-applied
 *       {@code JsonNodeConverter}.</li>
 * </ol>
 */
@Entity
@Table(name = "request_save")
public class RequestSave {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ── Foreign keys (lazy ManyToOne to avoid N+1 on list views) ──────────────

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", nullable = false)
    private Service service;

    // ── Execution inputs ───────────────────────────────────────────────────────

    @Column(name = "nid_input", nullable = false, length = 64)
    private String nidInput;

    // ── JSONB columns ──────────────────────────────────────────────────────────

    /**
     * The exact payload posted to the upstream service endpoint.
     * Stored as {@code jsonb} so it can be inspected with Postgres JSON operators.
     */
    @Column(name = "request_payload", columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private JsonNode requestPayload;

    /**
     * The raw JSON response received from the upstream service.
     * Intentionally stored unmodified — the frontend applies JSONPath at render time.
     */
    @Column(name = "response_data", columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private JsonNode responseData;

    // ── Status ─────────────────────────────────────────────────────────────────

    /**
     * Terminal status of the execution: {@code SUCCESS}, {@code API_ERROR},
     * {@code TIMEOUT}, {@code VALIDATION_ERROR}.
     */
    @Column(nullable = false, length = 32)
    private String status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // ── Constructors ───────────────────────────────────────────────────────────

    protected RequestSave() { /* JPA */ }

    private RequestSave(Builder builder) {
        this.user            = builder.user;
        this.service         = builder.service;
        this.nidInput        = builder.nidInput;
        this.requestPayload  = builder.requestPayload;
        this.responseData    = builder.responseData;
        this.status          = builder.status;
    }

    // ── Accessors ──────────────────────────────────────────────────────────────

    public Long getId()                   { return id; }
    public User getUser()                 { return user; }
    public Service getService()           { return service; }
    public String getNidInput()           { return nidInput; }
    public JsonNode getRequestPayload()   { return requestPayload; }
    public JsonNode getResponseData()     { return responseData; }
    public String getStatus()             { return status; }
    public Instant getCreatedAt()         { return createdAt; }

    public void setStatus(String status)          { this.status = status; }
    public void setResponseData(JsonNode data)    { this.responseData = data; }

    // ── Builder ────────────────────────────────────────────────────────────────

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private User user;
        private Service service;
        private String nidInput;
        private JsonNode requestPayload;
        private JsonNode responseData;
        private String status = "PENDING";

        public Builder user(User user)                      { this.user = user; return this; }
        public Builder service(Service service)             { this.service = service; return this; }
        public Builder nidInput(String nidInput)            { this.nidInput = nidInput; return this; }
        public Builder requestPayload(JsonNode payload)     { this.requestPayload = payload; return this; }
        public Builder responseData(JsonNode data)          { this.responseData = data; return this; }
        public Builder status(String status)                { this.status = status; return this; }
        public RequestSave build()                          { return new RequestSave(this); }
    }
}
