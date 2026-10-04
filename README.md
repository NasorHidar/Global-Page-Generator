# Global Page Generator

> **Internal enterprise platform** — dynamically renders data-entry UIs from PostgreSQL configuration and proxies NID lookups to internal third-party APIs.

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.3.4-6DB33F.svg)](https://spring.io/projects/spring-boot)
[![React](https://img.shields.io/badge/React-18-61DAFB.svg)](https://react.dev)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-336791.svg)](https://www.postgresql.org)
[![Redis](https://img.shields.io/badge/Redis-7-DC382D.svg)](https://redis.io)

---

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Repository Layout](#repository-layout)
3. [Technology Decisions](#technology-decisions)
4. [Database Schema](#database-schema)
5. [Security Model](#security-model)
6. [Caching](#caching)
7. [Input Validation](#input-validation)
8. [Prerequisites](#prerequisites)
9. [Local Development Setup](#local-development-setup)
10. [Running Tests](#running-tests)
11. [API Reference](#api-reference)
12. [Configuration Reference](#configuration-reference)
13. [Key Design Patterns](#key-design-patterns)
14. [Roadmap / Next Steps](#roadmap--next-steps)

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│  React 18 (Vite)                                                │
│  ┌──────────────┐   GET /api/v1/services   ┌─────────────────┐ │
│  │  App.jsx     │ ─────────────────────────▶│                 │ │
│  │  (Shell)     │                           │  Spring Boot    │ │
│  └──────┬───────┘   GET /api/v1/layout/:id  │  Backend        │ │
│         │         ─────────────────────────▶│                 │ │
│  ┌──────▼───────────────┐                   │  ┌───────────┐  │ │
│  │  DynamicServicePage  │ POST /api/v1/exec │  │ Execution │  │ │
│  │  (renders from DB    │ ─────────────────▶│  │ Service   │  │ │
│  │   layout config)     │                   │  └─────┬─────┘  │ │
│  └──────────────────────┘                   │        │        │ │
│                                             │        ▼        │ │
│                                             │  ┌───────────┐  │ │
│                                             │  │ 3rd-Party │  │ │
│                                             │  │ Upstream  │  │ │
│                                             │  │    API    │  │ │
│                                             │  └─────┬─────┘  │ │
│                                             │        │        │ │
│                                             │  ┌─────▼─────┐  │ │
│                                             │  │PostgreSQL │  │ │
│                                             │  │   16      │  │ │
│                                             │  └───────────┘  │ │
│                                             └─────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

### Core Flows

#### Layout Flow
`GET /api/v1/layout/{serviceId}` → `LayoutService` → `PageLoadRepository` (JOIN FETCH) → `PageLayoutDto` → Frontend renders component tree.

#### Execution Flow
`POST /api/v1/execute` → `ExecutionController` (injects `AppUserPrincipal`) → `ExecutionService` (three-transaction boundary) → Upstream API → `RequestSave` + `Log` persisted → Raw JSON returned to frontend → JSONPath applied client-side.

---

## Repository Layout

```
Global-Page-Generator/
│
├── backend/                              Spring Boot application
│   ├── pom.xml                           Maven build — Java 21, SB 3.3.4
│   └── src/
│       ├── main/
│       │   ├── java/com/globalpagegenerator/
│       │   │   ├── config/
│       │   │   │   ├── JacksonConfig.java           Shared ObjectMapper
│       │   │   │   ├── RestClientConfig.java         Timeout-aware builder
│       │   │   │   ├── CacheConfig.java              Redis CacheManager + 5-min layout TTL
│       │   │   │   └── SecurityConfig.java           Stateless chain + 401 entry point
│       │   │   ├── dto/
│       │   │   │   ├── ExecutionDtos.java            ExecutionRequest / Response records
│       │   │   │   ├── LayoutDtos.java               PageLayoutDto / ComponentDto records
│       │   │   │   └── ServiceDto.java               Dropdown projection record
│       │   │   ├── exception/
│       │   │   │   ├── ResourceNotFoundException.java  → 404
│       │   │   │   └── UpstreamApiException.java       → 502
│       │   │   ├── persistence/
│       │   │   │   ├── converter/JsonNodeConverter.java  JSONB ↔ JsonNode (autoApply)
│       │   │   │   ├── entity/  User, Service, PageLoad, Component, RequestSave, Log
│       │   │   │   └── repository/  5 JPA repositories
│       │   │   ├── security/
│       │   │   │   ├── AppUserPrincipal.java          Record + UserDetails impl
│       │   │   │   └── BearerTokenAuthenticationFilter.java  OncePerRequestFilter
│       │   │   ├── service/
│       │   │   │   ├── ExecutionService.java          Core orchestrator (3 TXs)
│       │   │   │   └── LayoutService.java             @Cacheable layout retrieval
│       │   │   ├── validation/
│       │   │   │   ├── ValidNid.java                  @ValidNid Bean Validation annotation
│       │   │   │   └── NidValidator.java              10 / 13 / 17-digit, ASCII-only rule
│       │   │   └── web/
│       │   │       ├── ExecutionController.java       @AuthenticationPrincipal injection
│       │   │       ├── LayoutController.java
│       │   │       ├── ServiceController.java          Dropdown endpoint
│       │   │       └── GlobalExceptionHandler.java     RFC 9457 ProblemDetail
│       │   └── resources/
│       │       ├── application.yml
│       │       └── db/migration/V1__initial_schema.sql
│       └── test/
│           ├── java/com/globalpagegenerator/
│           │   ├── service/
│           │   │   └── ExecutionServiceIntegrationTest.java    Testcontainers + MockWebServer
│           │   └── web/
│           │       ├── ExecutionControllerTest.java            6 web-layer scenarios
│           │       └── WithMockAppUser.java                    Custom SecurityContextFactory
│           └── resources/application.yml
│
└── frontend/                             React 18 + Vite application
    ├── index.html
    ├── package.json
    ├── vite.config.js                    Dev proxy: /api → localhost:8080
    └── src/
        ├── main.jsx                      React 18 createRoot entrypoint
        ├── App.jsx                       Shell: token gate, service selector
        ├── App.css                       Global design system
        └── components/
            ├── DynamicServicePage.jsx    Renderer registry: DataTable, CardDisplay, BadgeDisplay
            └── DynamicServicePage.css    Component-scoped styles
```

---

## Technology Decisions

| Concern | Choice | Rationale |
|---------|--------|-----------|
| **JSONB mapping** | `JsonNode` + `@ColumnTransformer(write="?::jsonb")` + `JsonNodeConverter(autoApply=true)` | Without the explicit cast, Hibernate sends text; PostgreSQL rejects it with a type-mismatch error |
| **Transactions** | Three fine-grained TXs in `ExecutionService` | A single `@Transactional` wrapping a 30 s network call would hold a DB connection open, exhausting HikariCP under load |
| **HTTP client** | `RestClient.Builder` injection (Spring 6) | Not `RestTemplate` (deprecated). Injecting the builder (not a built client) allows per-call customisation |
| **Authentication** | Bearer token lookup against `User.security_token` | Appropriate for an internal firewalled system; swap with JWT signature verification for public APIs |
| **userId source** | `@AuthenticationPrincipal` in controller | Accepting userId from the client payload is a privilege-escalation vector — the SecurityContext is the only trusted source |
| **Frontend JSONPath** | Zero-dependency `extractByPath()` | Avoids ~120 kB of `jsonpath-plus` bundle; covers all dot/bracket/index patterns used in `Component.properties` |
| **Component registry** | `COMPONENT_RENDERERS` map in JSX | Adding a new `componentType` requires one map entry — zero changes to routing or layout logic |
| **Layout caching** | Redis via `spring-boot-starter-data-redis` + `@Cacheable` | 5-min TTL shields the DB from repeat `GET /api/v1/layout/{id}` traffic; null/404 results are not cached |
| **NID validation** | Custom `@ValidNid` Bean Validation constraint + `ConstraintValidator` | Standard Bangladeshi NID is 10, 13, or 17 ASCII digits; rejected before the request reaches the service layer |

---

## Database Schema

```sql
-- "user"         — authenticated operators
-- service        — deployable service configurations with JSONB request templates
-- page_load      — page layout configuration (JSONB layoutConfig)
-- component      — UI component definitions with JSONPath mapping rules (JSONB properties)
-- request_save   — immutable audit record of each NID execution (JSONB request/response)
-- log            — append-only execution log
```

See [`V1__initial_schema.sql`](backend/src/main/resources/db/migration/V1__initial_schema.sql) for the full DDL.

### JSONB columns

| Table | Column | Purpose |
|-------|--------|---------|
| `service` | `request_format` | Template with `{{nid}}` / `{{token}}` placeholders |
| `page_load` | `layout_config` | Theme, grid, pagination hints for the page shell |
| `component` | `properties` | `columns[]: { label, jsonPath, type }` — drives frontend rendering |
| `request_save` | `request_payload` | Hydrated payload sent to upstream (audit/replay) |
| `request_save` | `response_data` | Raw upstream response (GIN-indexed for future analytics) |

---

## Security Model

### Authentication
Clients send an opaque Bearer token in the `Authorization` header:
```
Authorization: Bearer <security_token>
```

`BearerTokenAuthenticationFilter` resolves the token to a `User` row via `UserRepository.findBySecurityToken()` and populates `SecurityContextHolder` with an `AppUserPrincipal`.

### Authorization matrix

| Endpoint | Auth Required | Reason |
|----------|---------------|--------|
| `GET /api/v1/services` | ❌ Public | Populate dropdown before login |
| `GET /api/v1/layout/**` | ❌ Public | Read-only DB config, no PII |
| `POST /api/v1/execute` | ✅ Required | Submits NID, calls upstream, stores data |
| All other routes | ✅ Required | Deny by default |

### Trust boundary
`userId` is **never accepted from the client request body**. It is derived exclusively from the authenticated `AppUserPrincipal` in the `SecurityContextHolder`.

---

## Caching

Layout responses are cached in Redis to absorb repeated `GET /api/v1/layout/{serviceId}` traffic without hitting PostgreSQL.

### Configuration

Defined in `config/CacheConfig.java`:

- **Cache store** — Lettuce-backed Redis via `spring-boot-starter-data-redis`.
- **Key shape** — `layouts::{serviceId}` (e.g. `layouts::42`); human-readable via `StringRedisSerializer`.
- **Value serializer** — `GenericJackson2JsonRedisSerializer` reusing the application's shared `ObjectMapper`, so cached records preserve `JsonNode` payloads and JSR-310 date handling.
- **TTL** — 5 minutes for the `layouts` namespace (configurable via `app.cache.layout-ttl-ms`). Default for any other cache is 1 minute.
- **Null suppression** — `disableCachingNullValues()`; 404s from `ResourceNotFoundException` are never cached.

### Usage

The cache is transparent to callers — Spring's caching proxy intercepts the method invocation:

```java
@Cacheable(cacheNames = CacheConfig.LAYOUT_CACHE, key = "#serviceId")
@Transactional(readOnly = true)
public PageLayoutDto getLayoutForService(Long serviceId) { ... }
```

### Operational notes

- Cache **invalidation on edit** is implicit via TTL — if a `PageLoad` row is updated through the admin interface, stale entries are tolerated for at most 5 minutes.
- For immediate invalidation, inject `CacheManager` and call `cacheManager.getCache("layouts").evict(serviceId)` from the admin service.
- Redis is **stateless**; replacing the cache with Caffeine or a no-op in tests does not change the controller contract.

### Redis connection

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
  pool:        # Lettuce pool tuning
    max-active: 8
```

---

## Input Validation

The `POST /api/v1/execute` endpoint uses Bean Validation with a custom constraint for NIDs.

### `@ValidNid` constraint

Defined in `validation/ValidNid.java` and enforced by `validation/NidValidator.java`.

| Rule | Value |
|------|-------|
| Required | Non-null (paired with `@NotBlank`) |
| Length | Strictly `10`, `13`, or `17` characters |
| Characters | ASCII `0-9` only — no spaces, dashes, or Unicode digits |
| Default message | `"NID must be 10, 13, or 17 digits (digits only)"` |

Bangladesh issues three NID formats; this validator accepts all of them while rejecting every other input before it reaches the service layer.

### DTO application

```java
public record ExecutionRequest(
        @NotNull @Positive Long serviceId,
        @NotBlank @ValidNid String nid
) { }
```

### Error handling

`ExecutionController` declares `@Valid` on the body parameter, so a failing `@ValidNid` raises a `MethodArgumentNotValidException`. `GlobalExceptionHandler` converts it into an RFC 9457 `ProblemDetail`:

```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{
  "type":   "https://errors.globalpagegenerator.internal/validation-error",
  "title":  "Validation Failed",
  "status": 400,
  "detail": "nid: NID must be 10, 13, or 17 digits (digits only)"
}
```

Because validation runs **before** the controller method body, an invalid NID never triggers the upstream call, the database write, or the audit log.

---

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK | 21+ | Temurin / GraalVM |
| Maven | 3.9+ | Or use `./mvnw` wrapper |
| Node.js | 20+ LTS | For frontend |
| Docker | 24+ | Required for Testcontainers in tests |
| PostgreSQL | 16 | Dev: run via Docker |
| Redis | 7+ | Cache backend (see [Caching](#caching)) |

---

## Local Development Setup

### 1. Start Redis

```bash
docker run -d \
  --name gpg-redis \
  -p 6379:6379 \
  redis:7-alpine
```

2. Start PostgreSQL

```bash
docker run -d \
  --name gpg-postgres \
  -e POSTGRES_DB=global_page_generator \
  -e POSTGRES_USER=gpg_user \
  -e POSTGRES_PASSWORD=changeme \
  -p 5432:5432 \
  postgres:16-alpine
```

### 3. Seed minimum data

```sql
-- Insert a test user with a known token
INSERT INTO "user" (user_id, password_hash, security_token)
VALUES ('operator1', '$2a$12$placeholder', 'my-dev-token');

-- Insert a service
INSERT INTO service (service_name, request_format, endpoint_url)
VALUES (
  'NID Lookup',
  '{"nid": "{{nid}}", "token": "{{token}}"}',
  'https://your-internal-api.example.com/lookup'
);

-- Insert a page layout for that service
INSERT INTO page_load (service_id, page_title, layout_config)
VALUES (1, 'NID Information', '{"theme": "dark"}');

-- Insert a DATA_TABLE component
INSERT INTO component (page_id, component_type, properties, sort_order)
VALUES (1, 'DATA_TABLE', '{
  "columns": [
    { "label": "Full Name",     "jsonPath": "$.person.fullName",    "type": "text" },
    { "label": "Date of Birth", "jsonPath": "$.person.dob",         "type": "date" },
    { "label": "NID",           "jsonPath": "$.person.nationalId",  "type": "text" }
  ]
}', 1);
```

### 4. Start the backend

```bash
cd backend
./mvnw spring-boot:run
# Server starts on http://localhost:8080
```

### 5. Start the frontend

```bash
cd frontend
npm install
npm run dev
# Vite starts on http://localhost:5173
# /api requests are proxied to :8080 automatically
```

### 6. Authenticate in the browser

Open `http://localhost:5173`, paste `my-dev-token` into the token gate, select "NID Lookup" from the dropdown, and enter any NID.

---

## Running Tests

Docker must be running (Testcontainers launches a real PostgreSQL container).

```bash
cd backend
./mvnw test
```

The test suite:
- Starts a `postgres:16-alpine` Testcontainers instance
- Runs all Flyway migrations
- Executes `ExecutionServiceIntegrationTest` with 2 test methods:
  - **Happy path** — asserts `SUCCESS` status, stored response, and log entry
  - **Timeout path** — asserts `TIMEOUT` status and that no HikariCP deadlock occurs
- `ExecutionControllerTest` (web-slice with `MockMvc` + `@WithMockAppUser`):
  - **HTTP 200** — valid NID reaches the service and the response is rendered
  - **HTTP 400** — invalid NID triggers `@ValidNid` + `GlobalExceptionHandler`
  - **HTTP 401** — anonymous request blocked by `SecurityFilterChain`
  - **Principal forwarding** — `ArgumentCaptor` proves the correct `AppUserPrincipal` reaches the service
- Redis is not required for the web-slice tests — `ExecutionService` is fully mocked.

---

## API Reference

### `GET /api/v1/services`
Returns the list of available services for the frontend dropdown.

**Auth:** None required.

**Response `200 OK`:**
```json
[
  { "id": 1, "serviceName": "NID Lookup" },
  { "id": 2, "serviceName": "Address Verification" }
]
```

---

### `GET /api/v1/layout/{serviceId}`
Returns the page layout and ordered component list for a service.

**Auth:** None required.

**Response `200 OK`:**
```json
{
  "pageId": 1,
  "pageTitle": "NID Information",
  "layoutConfig": { "theme": "dark" },
  "components": [
    {
      "id": 10,
      "componentType": "DATA_TABLE",
      "sortOrder": 1,
      "properties": {
        "columns": [
          { "label": "Full Name",     "jsonPath": "$.person.fullName",   "type": "text" },
          { "label": "Date of Birth", "jsonPath": "$.person.dob",        "type": "date" }
        ]
      }
    }
  ]
}
```

---

### `POST /api/v1/execute`
Executes a NID lookup against the configured upstream service.

**Auth:** `Authorization: Bearer <token>` required.

**Request body:**
```json
{ "serviceId": 1, "nid": "1234567890" }
```

**Response `200 OK`:**
```json
{
  "requestId": 42,
  "status": "SUCCESS",
  "responseData": { ... }
}
```

**Error `400 Bad Request`** (validation failure, e.g. invalid NID):
```json
{
  "type":   "https://errors.globalpagegenerator.internal/validation-error",
  "title":  "Validation Failed",
  "status": 400,
  "detail": "nid: NID must be 10, 13, or 17 digits (digits only)"
}
```

**Error `401 Unauthorized`** (missing or invalid Bearer token):
Returns an empty body with `HTTP/1.1 401 Unauthorized`. The frontend detects this and redirects to the token gate.

**Error `502 Bad Gateway`** (upstream failure / timeout):
```json
{
  "type": "https://errors.globalpagegenerator.internal/upstream-error",
  "title": "Upstream API Error",
  "status": 502,
  "detail": "Upstream API call timed out or is unreachable: https://...",
  "upstreamStatusCode": -1,
  "timestamp": "2026-10-04T13:22:33Z"
}
```

---

## Configuration Reference

### `application.yml` — key properties

| Property | Default | Description |
|----------|---------|-------------|
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/global_page_generator` | Override via `DB_HOST`, `DB_PORT`, `DB_NAME` env vars |
| `spring.datasource.hikari.maximum-pool-size` | `10` | Max concurrent DB connections |
| `spring.datasource.hikari.connection-timeout` | `30000` ms | Pool wait timeout |
| `app.rest-client.connect-timeout-ms` | `5000` | TCP handshake limit |
| `app.rest-client.read-timeout-ms` | `30000` | Upstream response timeout |
| `app.cache.layout-ttl-ms` | `300000` (5 min) | TTL for the `layouts` Redis cache |
| `spring.data.redis.host` / `port` / `password` | `localhost` / `6379` / empty | Redis connection (override via env vars below) |

### Environment variables

| Variable | Required | Description |
|----------|----------|-------------|
| `DB_HOST` | No | PostgreSQL host (default: `localhost`) |
| `DB_PORT` | No | PostgreSQL port (default: `5432`) |
| `DB_NAME` | No | Database name (default: `global_page_generator`) |
| `DB_USERNAME` | **Yes in prod** | Database user |
| `DB_PASSWORD` | **Yes in prod** | Database password |
| `REDIS_HOST` | No | Redis host (default: `localhost`) |
| `REDIS_PORT` | No | Redis port (default: `6379`) |
| `REDIS_PASSWORD` | No | Redis password (default: empty) |

---

## Key Design Patterns

### 1. JSONB four-layer mapping
```
JsonNode field
+ @Column(columnDefinition = "jsonb")
+ @ColumnTransformer(write = "?::jsonb")   ← prevents text/jsonb mismatch error
+ JsonNodeConverter(autoApply = true)       ← automatic codec for all JsonNode fields
```

### 2. ExecutionService three-transaction boundary
```
execute(principal, request)
 ├─ TX-READ: resolve User + Service (readOnly)
 ├─ TX-INIT: persist PENDING row → committed
 │
 │  [NO active transaction — HikariCP connection returned to pool]
 ├─ callUpstreamApi()  ← up to 30 s network wait
 │
 ├─ TX-RESULT (success): update row + append Log
 └─ TX-RESULT (failure): set TIMEOUT/API_ERROR + append Log
```

### 3. Frontend dynamic rendering pipeline
```
Backend layout config (JSONB)
 └─ components[].properties.columns[].jsonPath
      └─ extractByPath(responseData, "$.person.fullName")
           └─ rendered in DataTable / Card / Badge cell
```

Renderer registry (`COMPONENT_RENDERERS`):
- `DATA_TABLE` → tabular results
- `CARD`       → titled summary panel with avatar + sub-fields
- `BADGE`      → status pill, colour mapped from value

---

## Roadmap / Next Steps

- [x] Add web-layer tests for `ExecutionController` (`ExecutionControllerTest` + custom `@WithMockAppUser`)
- [ ] Implement refresh-token / token-rotation endpoint
- [x] Redis caching for layout responses — `CacheConfig` + `@Cacheable("layouts")` with 5 min TTL
- [x] Extend `COMPONENT_RENDERERS` with `CARD` and `BADGE` (TIMELINE pending)
- [x] Custom `@ValidNid` Bean Validation constraint — 10 / 13 / 17 ASCII digits
- [ ] CI pipeline: GitHub Actions matrix testing Java 21 + Testcontainers
- [ ] Production Docker Compose with health checks and dependency ordering
- [ ] Add Actuator metrics endpoint + Micrometer/Prometheus integration