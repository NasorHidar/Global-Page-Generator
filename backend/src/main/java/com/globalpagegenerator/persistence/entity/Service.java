package com.globalpagegenerator.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.ColumnTransformer;

/**
 * Represents a deployable back-end service. The {@code requestFormat} column
 * stores a JSON template (tokenised with placeholders such as {@code {{nid}}}
 * and {@code {{token}}}) that {@code ExecutionService} hydrates at runtime.
 */
@Entity
@Table(name = "service")
public class Service {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "service_name", nullable = false, unique = true, length = 128)
    private String serviceName;

    /**
     * JSON request template stored as {@code jsonb}.
     * Example value: {@code {"nid":"{{nid}}","token":"{{token}}"}}
     */
    @Column(name = "request_format", columnDefinition = "jsonb", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private JsonNode requestFormat;

    @Column(name = "endpoint_url", nullable = false, length = 512)
    private String endpointUrl;

    protected Service() { /* JPA */ }

    public Long getId()              { return id; }
    public String getServiceName()   { return serviceName; }
    public JsonNode getRequestFormat(){ return requestFormat; }
    public String getEndpointUrl()   { return endpointUrl; }
}
