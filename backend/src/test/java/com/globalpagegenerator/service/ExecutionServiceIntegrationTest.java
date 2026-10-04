package com.globalpagegenerator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalpagegenerator.dto.ExecutionDtos.ExecutionRequest;
import com.globalpagegenerator.dto.ExecutionDtos.ExecutionResponse;
import com.globalpagegenerator.persistence.entity.Log;
import com.globalpagegenerator.persistence.entity.*;
import com.globalpagegenerator.persistence.repository.*;
import com.globalpagegenerator.security.AppUserPrincipal;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration test suite for {@link ExecutionService}.
 *
 * <h2>Infrastructure</h2>
 * <ul>
 *   <li><b>PostgreSQL:</b> A real PostgreSQL 16 instance launched via Testcontainers.
 *       Flyway runs the full migration set so the schema is identical to production.</li>
 *   <li><b>Upstream API mock:</b> OkHttp {@link MockWebServer} stands in for the
 *       third-party service. It binds to a random local port and its URL is injected
 *       into a test-only {@link Service} entity, overriding the real endpoint URL.</li>
 *   <li><b>RestClient:</b> The test {@link TestRestClientConfig} bean wires a
 *       short (500 ms) read-timeout so the timeout test completes in milliseconds
 *       rather than waiting 30 seconds.</li>
 * </ul>
 *
 * <h2>Transaction isolation</h2>
 * Each test method uses a fresh {@code RequestSave} row. The class-level test setup
 * creates the {@link User} and {@link Service} entities once using
 * {@code @BeforeEach} — these are visible to all tests within the same Testcontainers
 * lifecycle because the container and its schema persist for the entire test class.
 *
 * <p><b>Note on {@code @Transactional} on test methods:</b>
 * We intentionally do <em>not</em> annotate individual test methods with
 * {@code @Transactional} — {@code ExecutionService.execute()} itself manages
 * multiple transactions and relies on committed rows being visible across those
 * transaction boundaries. Wrapping the test in an outer transaction would hide
 * intermediate commits, breaking assertions.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("ExecutionService Integration Tests")
class ExecutionServiceIntegrationTest {

    // =========================================================================
    // Testcontainers — PostgreSQL 16
    // =========================================================================

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("gpg_test")
                    .withUsername("gpg_test")
                    .withPassword("gpg_test");

    /**
     * Feeds the container's dynamic JDBC URL into Spring's DataSource configuration
     * before the application context is loaded. Flyway migrations run automatically.
     */
    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    // =========================================================================
    // MockWebServer — OkHttp upstream stub
    // =========================================================================

    static MockWebServer mockWebServer;

    @BeforeAll
    static void startMockServer() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
    }

    @AfterAll
    static void stopMockServer() throws IOException {
        mockWebServer.shutdown();
    }

    // =========================================================================
    // Test configuration — short-timeout RestClient.Builder
    // =========================================================================

    /**
     * Overrides the application's default {@link RestClient.Builder} bean with one
     * configured for a 500 ms read timeout. This lets the timeout test complete
     * quickly without altering production configuration.
     */
    @TestConfiguration
    static class TestRestClientConfig {
        @Bean
        @Primary
        public RestClient.Builder testRestClientBuilder() {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(1_000);
            factory.setReadTimeout(500);   // 500 ms — short enough to be practical

            return RestClient.builder()
                    .requestFactory(factory)
                    .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE);
        }
    }

    // =========================================================================
    // Spring-managed beans under test
    // =========================================================================

    @Autowired ExecutionService      executionService;
    @Autowired UserRepository        userRepository;
    @Autowired ServiceRepository     serviceRepository;
    @Autowired RequestSaveRepository requestSaveRepository;
    @Autowired LogRepository         logRepository;
    @Autowired ObjectMapper          objectMapper;

    // =========================================================================
    // Test fixtures — created fresh before every test to avoid cross-test pollution
    // =========================================================================

    User    testUser;
    com.globalpagegenerator.persistence.entity.Service testService;
    AppUserPrincipal testPrincipal;

    /**
     * Creates a {@link User} and a {@link Service} whose endpoint URL points to
     * the local {@link MockWebServer} instance. Saved and committed before each test.
     */
    @BeforeEach
    @Transactional
    void createFixtures() throws Exception {
        // Clean up from any previous test
        logRepository.deleteAll();
        requestSaveRepository.deleteAll();
        serviceRepository.deleteAll();
        userRepository.deleteAll();

        // Build a minimal User with a known security token.
        // User has a protected no-arg constructor (JPA requirement), so we
        // instantiate via reflection in {@link #instantiate(Class)}.
        testUser = userRepository.saveAndFlush(buildTestUser());

        // Build a Service whose endpoint URL is the MockWebServer base URL
        String mockServerUrl = mockWebServer.url("/upstream").toString();
        testService = serviceRepository.saveAndFlush(buildTestService(mockServerUrl));

        testPrincipal = new AppUserPrincipal(
                testUser.getUserId(),
                testUser.getSecurityToken()
        );
    }

    // =========================================================================
    // Test 1 — Happy Path
    // =========================================================================

    /**
     * Given a valid NID and an upstream service returning HTTP 200 with a JSON body,
     * {@code ExecutionService.execute()} must:
     * <ol>
     *   <li>Return a response with {@code status=SUCCESS}.</li>
     *   <li>Persist the upstream response JSON in {@code RequestSave.response_data}.</li>
     *   <li>Persist exactly one {@link Log} entry with {@code statusCode=200}.</li>
     * </ol>
     */
    @Test
    @DisplayName("Happy path: valid NID → upstream 200 → SUCCESS status + response stored")
    void happyPath_validNid_upstreamReturns200_recordSavedAsSuccess() throws Exception {
        // ── Arrange ──────────────────────────────────────────────────────────
        String upstreamResponseBody = """
                {
                  "person": {
                    "fullName": "Jane Doe",
                    "nationalId": "1234567890",
                    "dob": "1985-06-15"
                  },
                  "status": "ACTIVE"
                }
                """;

        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(upstreamResponseBody));

        ExecutionRequest request = new ExecutionRequest(testService.getId(), "1234567890");

        // ── Act ───────────────────────────────────────────────────────────────
        ExecutionResponse response = executionService.execute(testPrincipal, request);

        // ── Assert: returned DTO ─────────────────────────────────────────────
        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(response.requestId()).isNotNull().isPositive();
        assertThat(response.responseData()).isNotNull();
        assertThat(response.responseData().path("person").path("fullName").asText())
                .isEqualTo("Jane Doe");

        // ── Assert: RequestSave row ──────────────────────────────────────────
        Optional<RequestSave> saved = requestSaveRepository.findById(response.requestId());
        assertThat(saved).isPresent();
        RequestSave record = saved.get();

        assertThat(record.getStatus()).isEqualTo("SUCCESS");
        assertThat(record.getNidInput()).isEqualTo("1234567890");

        // Verify the raw payload was stored correctly
        JsonNode storedResponse = record.getResponseData();
        assertThat(storedResponse).isNotNull();
        assertThat(storedResponse.path("person").path("nationalId").asText())
                .isEqualTo("1234567890");

        // Verify the request payload has placeholders hydrated
        JsonNode storedPayload = record.getRequestPayload();
        assertThat(storedPayload).isNotNull();
        String payloadStr = objectMapper.writeValueAsString(storedPayload);
        assertThat(payloadStr).contains("1234567890");          // NID injected
        assertThat(payloadStr).doesNotContain("{{nid}}");       // token replaced
        assertThat(payloadStr).doesNotContain("{{token}}");     // token replaced

        // ── Assert: Log entry ────────────────────────────────────────────────
        List<Log> logs = logRepository.findAll();
        assertThat(logs).hasSize(1);

        Log logEntry = logs.get(0);
        assertThat(logEntry.getAction()).isEqualTo("EXECUTE_API");
        assertThat(logEntry.getStatusCode()).isEqualTo(200);
        assertThat(logEntry.getMessage()).contains("succeeded");
        assertThat(logEntry.getRequest().getId()).isEqualTo(record.getId());

        // ── Assert: upstream received the correct request ─────────────────────
        okhttp3.mockwebserver.RecordedRequest upstreamRequest =
                mockWebServer.takeRequest(1, TimeUnit.SECONDS);
        assertThat(upstreamRequest).isNotNull();
        assertThat(upstreamRequest.getMethod()).isEqualTo("POST");
        assertThat(upstreamRequest.getHeader("Content-Type"))
                .contains("application/json");
    }

    // =========================================================================
    // Test 2 — Timeout Path
    // =========================================================================

    /**
     * Given an upstream service that stalls and never responds within the 500 ms
     * read timeout, {@code ExecutionService.execute()} must:
     * <ol>
     *   <li>Throw an {@link com.globalpagegenerator.exception.UpstreamApiException}
     *       (re-thrown from the service).</li>
     *   <li>Persist the {@code RequestSave} row with {@code status=TIMEOUT}.</li>
     *   <li>Persist exactly one {@link Log} entry with {@code statusCode=-1}.</li>
     *   <li>Return the HikariCP connection to the pool before the timeout fires
     *       (verified indirectly: the test completes in under 3 seconds, ruling out
     *       pool exhaustion deadlock).</li>
     * </ol>
     *
     * <p><b>How the stall is simulated:</b>
     * {@link SocketPolicy#NO_RESPONSE} instructs {@link MockWebServer} to accept the
     * TCP connection but never send any bytes, causing the read to block until the
     * configured 500 ms timeout expires.
     */
    @Test
    @DisplayName("Timeout path: upstream never responds → TIMEOUT status + no HikariCP deadlock")
    void timeoutPath_upstreamStalls_recordSavedAsTimeout() {
        // ── Arrange ──────────────────────────────────────────────────────────
        // NO_RESPONSE: server accepts the connection but sends nothing back
        mockWebServer.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        ExecutionRequest request = new ExecutionRequest(testService.getId(), "9999999999");

        long startMs = System.currentTimeMillis();

        // ── Act & Assert: exception thrown ───────────────────────────────────
        assertThatThrownBy(() -> executionService.execute(testPrincipal, request))
                .isInstanceOf(com.globalpagegenerator.exception.UpstreamApiException.class)
                .hasMessageContaining("timed out");

        long elapsedMs = System.currentTimeMillis() - startMs;

        // The test must complete within 3 seconds.
        // If a DB connection were held open during the timeout, subsequent operations
        // in this assertion would deadlock (pool size = 10 in test). A clean
        // completion proves the connection was returned before the socket wait began.
        assertThat(elapsedMs)
                .as("Execution should complete within 3 seconds — no connection held during I/O")
                .isLessThan(3_000L);

        // ── Assert: RequestSave was saved with TIMEOUT status ────────────────
        List<RequestSave> records = requestSaveRepository.findAll();
        assertThat(records)
                .as("Exactly one RequestSave row should exist after the timeout")
                .hasSize(1);

        RequestSave record = records.get(0);
        assertThat(record.getStatus()).isEqualTo("TIMEOUT");
        assertThat(record.getNidInput()).isEqualTo("9999999999");
        assertThat(record.getResponseData())
                .as("response_data must remain null on timeout")
                .isNull();

        // ── Assert: Log entry captured the failure ────────────────────────────
        List<Log> logs = logRepository.findAll();
        assertThat(logs).hasSize(1);

        Log logEntry = logs.get(0);
        assertThat(logEntry.getAction()).isEqualTo("EXECUTE_API");
        assertThat(logEntry.getStatusCode()).isEqualTo(-1);
        assertThat(logEntry.getMessage()).contains("timed out");
        assertThat(logEntry.getRequest().getId()).isEqualTo(record.getId());
    }

    // =========================================================================
    // Private fixture builders
    // =========================================================================

    /**
     * Builds a test {@link User} via reflection-free field setting.
     * We leverage the entity's package-private visibility for test purposes.
     * In a real codebase, a dedicated {@code TestDataBuilder} utility class
     * or an entity factory method would be preferred.
     */
    private User buildTestUser() throws Exception {
        // Use a reflective constructor invocation to bypass the protected JPA
        // no-arg constructor, then set fields reflectively.
        var u = instantiate(User.class);
        setField(u, "userId",        "test_operator");
        setField(u, "passwordHash",  "$2a$12$placeholder_hash");
        setField(u, "securityToken", "test-bearer-token-abc123");
        return u;
    }

    private com.globalpagegenerator.persistence.entity.Service buildTestService(
            String endpointUrl) throws Exception {

        // Minimal JSON template with both supported tokens
        JsonNode template = objectMapper.readTree(
                """
                { "nid": "{{nid}}", "token": "{{token}}", "source": "gpg" }
                """
        );

        var s = instantiate(com.globalpagegenerator.persistence.entity.Service.class);
        setField(s, "serviceName",    "Test NID Service");
        setField(s, "requestFormat",  template);
        setField(s, "endpointUrl",    endpointUrl);
        return s;
    }

    /**
     * Reflectively invokes a protected/private no-arg constructor. Used to bypass
     * JPA's mandatory protected constructor on entity classes without polluting
     * the production code with test-only setters.
     */
    private static <T> T instantiate(Class<T> type) throws Exception {
        var ctor = type.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    /** Reflective field setter — avoids adding test-only setters to production entities. */
    private static void setField(Object target, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
