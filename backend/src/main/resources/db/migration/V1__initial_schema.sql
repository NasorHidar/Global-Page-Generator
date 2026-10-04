-- ============================================================
-- V1__initial_schema.sql
-- Global Page Generator — baseline schema
-- PostgreSQL 16  |  JSONB for all dynamic-structure columns
-- ============================================================

CREATE TABLE "user" (
    id             BIGSERIAL    PRIMARY KEY,
    user_id        VARCHAR(64)  NOT NULL UNIQUE,
    password_hash  VARCHAR(256) NOT NULL,
    security_token VARCHAR(512),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_user_user_id ON "user" (user_id);

-- ──────────────────────────────────────────────────────────

CREATE TABLE service (
    id             BIGSERIAL     PRIMARY KEY,
    service_name   VARCHAR(128)  NOT NULL UNIQUE,
    -- JSON template with {{nid}} and {{token}} placeholders
    request_format JSONB         NOT NULL,
    endpoint_url   VARCHAR(512)  NOT NULL
);

-- ──────────────────────────────────────────────────────────

CREATE TABLE page_load (
    id            BIGSERIAL    PRIMARY KEY,
    service_id    BIGINT       NOT NULL REFERENCES service(id),
    page_title    VARCHAR(256) NOT NULL,
    -- Arbitrary presentation hints: theme, grid columns, pagination, etc.
    layout_config JSONB
);

-- ──────────────────────────────────────────────────────────

CREATE TABLE component (
    id             BIGSERIAL   PRIMARY KEY,
    page_id        BIGINT      NOT NULL REFERENCES page_load(id) ON DELETE CASCADE,
    component_type VARCHAR(64) NOT NULL,
    -- JSONPath mapping rules consumed by the React renderer:
    -- { "columns": [{ "label": "...", "jsonPath": "$...", "type": "text" }] }
    properties     JSONB,
    sort_order     INT         NOT NULL DEFAULT 0
);

CREATE INDEX idx_component_page_sort ON component (page_id, sort_order);

-- ──────────────────────────────────────────────────────────

CREATE TABLE request_save (
    id              BIGSERIAL   PRIMARY KEY,
    user_id         BIGINT      NOT NULL REFERENCES "user"(id),
    service_id      BIGINT      NOT NULL REFERENCES service(id),
    nid_input       VARCHAR(64) NOT NULL,
    -- Exact payload sent to upstream; retained for audit/replay
    request_payload JSONB,
    -- Raw response stored unmodified; frontend applies JSONPath at render time
    response_data   JSONB,
    status          VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- GIN index allows server-side querying inside response_data if needed later
CREATE INDEX idx_request_save_response_gin ON request_save USING gin (response_data);
CREATE INDEX idx_request_save_user         ON request_save (user_id);
CREATE INDEX idx_request_save_service      ON request_save (service_id);

-- ──────────────────────────────────────────────────────────

CREATE TABLE log (
    id          BIGSERIAL     PRIMARY KEY,
    request_id  BIGINT        NOT NULL REFERENCES request_save(id),
    action      VARCHAR(64)   NOT NULL,
    status_code INT,
    message     VARCHAR(1024),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_log_request_id ON log (request_id);
