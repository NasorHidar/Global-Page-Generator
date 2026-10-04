package com.globalpagegenerator.persistence.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Application user. The {@code securityToken} is a plain opaque string
 * (e.g., a UUID or a JWT) obtained from an upstream IdP and persisted here
 * for use in downstream API calls. At-rest encryption is not required for
 * this internal, firewalled deployment.
 */
@Entity
@Table(name = "\"user\"",          // "user" is a reserved word in PostgreSQL
        indexes = @Index(name = "idx_user_user_id", columnList = "user_id", unique = true))
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true, length = 64)
    private String userId;

    @Column(name = "password_hash", nullable = false, length = 256)
    private String passwordHash;

    @Column(name = "security_token", length = 512)
    private String securityToken;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected User() { /* JPA */ }

    public Long getId()             { return id; }
    public String getUserId()       { return userId; }
    public String getPasswordHash() { return passwordHash; }
    public String getSecurityToken(){ return securityToken; }
    public Instant getCreatedAt()   { return createdAt; }

    public void setSecurityToken(String token) { this.securityToken = token; }
}
