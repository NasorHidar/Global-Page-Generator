package com.globalpagegenerator.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.globalpagegenerator.dto.ExecutionDtos.ExecutionRequest;
import com.globalpagegenerator.dto.ExecutionDtos.ExecutionResponse;
import com.globalpagegenerator.persistence.repository.LogRepository;
import com.globalpagegenerator.persistence.repository.PageLoadRepository;
import com.globalpagegenerator.persistence.repository.RequestSaveRepository;
import com.globalpagegenerator.persistence.repository.ServiceRepository;
import com.globalpagegenerator.persistence.repository.UserRepository;
import com.globalpagegenerator.security.AppUserPrincipal;
import com.globalpagegenerator.service.ExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer tests for {@link ExecutionController}.
 *
 * <h2>Scope</h2>
 * <p>Uses {@code @SpringBootTest} + {@code @AutoConfigureMockMvc} so the full
 * Spring Security filter chain — including our {@code SecurityConfig},
 * {@code BearerTokenAuthenticationFilter}, and CORS — is wired exactly as in
 * production. The {@link ExecutionService} is mocked; all persistence beans
 * are {@link MockBean}-replaced so no database connection is needed.
 *
 * <p>This is equivalent in intent to a {@code @WebMvcTest} slice but works
 * here because {@code SecurityConfig} transitively requires JPA repositories
 * that the web-slice would normally exclude — making the {@code @WebMvcTest}
 * bootstrap fail. By booting the full context and mocking the persistence
 * beans we keep the web-layer assertions isolated while preserving a
 * production-identical security chain.
 *
 * <h2>Three scenarios</h2>
 * <ul>
 *   <li><b>HTTP 200</b> — valid authenticated request reaches the service
 *       and the response JSON is rendered.</li>
 *   <li><b>HTTP 400</b> — invalid NID is rejected by {@code @ValidNid}
 *       before the service is invoked; the global handler returns
 *       an RFC 9457 {@code ProblemDetail}.</li>
 *   <li><b>HTTP 401</b> — an anonymous request is rejected by the
 *       {@code .anyRequest().authenticated()} rule, returning 401
 *       (not the Spring default of 403) thanks to the
 *       {@code unauthorizedEntryPoint} configured in {@code SecurityConfig}.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // Persistence connections — no DB needed, repositories are mocked
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
                "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration"
})
@DisplayName("ExecutionController Web-Layer Tests")
class ExecutionControllerTest {

    @Autowired private MockMvc      mockMvc;
    @Autowired private ObjectMapper objectMapper;

    // ── Service under test (mocked) ───────────────────────────────────────────
    @MockBean private ExecutionService executionService;

    // ── Persistence beans (mocked — no DB connection required) ───────────────
    @MockBean private UserRepository        userRepository;
    @MockBean private ServiceRepository     serviceRepository;
    @MockBean private RequestSaveRepository requestSaveRepository;
    @MockBean private LogRepository         logRepository;
    @MockBean private PageLoadRepository    pageLoadRepository;

    @BeforeEach
    void resetMocks() {
        org.mockito.Mockito.reset(executionService,
                userRepository, serviceRepository,
                requestSaveRepository, logRepository,
                pageLoadRepository);
    }

    // =========================================================================
    // a) HTTP 200 — happy path
    // =========================================================================

    @Test
    @WithMockAppUser(userId = "operator1", securityToken = "secret-token")
    @DisplayName("200 OK — valid NID reaches ExecutionService and the response is rendered")
    void execute_returns200_whenRequestIsValid() throws Exception {
        ObjectNode upstreamPayload = objectMapper.createObjectNode()
                .put("status", "ACTIVE")
                .put("fullName", "Jane Doe");
        ExecutionResponse stubResponse =
                new ExecutionResponse(42L, "SUCCESS", upstreamPayload);

        when(executionService.execute(any(AppUserPrincipal.class), any(ExecutionRequest.class)))
                .thenReturn(stubResponse);

        String requestBody = objectMapper.writeValueAsString(
                new ExecutionRequest(7L, "1234567890"));   // 10-digit NID — valid

        mockMvc.perform(post("/api/v1/execute")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))

                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.requestId").value(42))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.responseData.status").value("ACTIVE"))
                .andExpect(jsonPath("$.responseData.fullName").value("Jane Doe"));

        verify(executionService).execute(any(AppUserPrincipal.class), any(ExecutionRequest.class));
    }

    // =========================================================================
    // b) HTTP 400 — validation failure
    // =========================================================================

    @Test
    @WithMockAppUser
    @DisplayName("400 Bad Request — invalid NID triggers @ValidNid + GlobalExceptionHandler")
    void execute_returns400_whenNidFailsValidation() throws Exception {
        // 5 digits — fails the length rule
        String requestBody = objectMapper.writeValueAsString(
                new ExecutionRequest(7L, "12345"));

        mockMvc.perform(post("/api/v1/execute")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))

                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.detail",
                        containsString("NID must be 10, 13, or 17 digits")))
                .andExpect(jsonPath("$.status").value(400));

        verify(executionService, never()).execute(any(), any());
    }

    @Test
    @WithMockAppUser
    @DisplayName("400 Bad Request — NID with non-digit characters is rejected")
    void execute_returns400_whenNidContainsNonDigits() throws Exception {
        String requestBody = objectMapper.writeValueAsString(
                new ExecutionRequest(7L, "12345-67890"));

        mockMvc.perform(post("/api/v1/execute")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))

                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Failed"));

        verify(executionService, never()).execute(any(), any());
    }

    @Test
    @WithMockAppUser
    @DisplayName("400 Bad Request — null serviceId fails @NotNull validation")
    void execute_returns400_whenServiceIdIsNull() throws Exception {
        String requestBody = "{ \"serviceId\": null, \"nid\": \"1234567890\" }";

        mockMvc.perform(post("/api/v1/execute")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))

                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Failed"));

        verify(executionService, never()).execute(any(), any());
    }

    // =========================================================================
    // c) HTTP 401 — unauthenticated
    // =========================================================================

    @Test
    @WithAnonymousUser
    @DisplayName("401 Unauthorized — anonymous request is blocked by SecurityFilterChain")
    void execute_returns401_whenNotAuthenticated() throws Exception {
        // Valid NID on purpose — proves the rejection comes from the security
        // layer, not from @ValidNid.
        String requestBody = objectMapper.writeValueAsString(
                new ExecutionRequest(7L, "1234567890"));

        mockMvc.perform(post("/api/v1/execute")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))

                .andExpect(status().isUnauthorized());

        verify(executionService, never()).execute(any(), any());
    }

    // =========================================================================
    // d) Sanity — principal forwarding
    // =========================================================================

    @Test
    @WithMockAppUser(userId = "captured-user", securityToken = "captured-token")
    @DisplayName("Sanity — the mocked principal is forwarded to ExecutionService")
    void execute_forwardsPrincipalToService() throws Exception {
        ObjectNode payload = objectMapper.createObjectNode().put("ok", true);
        ExecutionResponse stubResponse = new ExecutionResponse(1L, "SUCCESS", (JsonNode) payload);
        when(executionService.execute(any(AppUserPrincipal.class), any(ExecutionRequest.class)))
                .thenReturn(stubResponse);

        mockMvc.perform(post("/api/v1/execute")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ExecutionRequest(7L, "1234567890"))))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<AppUserPrincipal> principalCaptor =
                org.mockito.ArgumentCaptor.forClass(AppUserPrincipal.class);
        verify(executionService).execute(principalCaptor.capture(), any(ExecutionRequest.class));

        AppUserPrincipal captured = principalCaptor.getValue();
        org.assertj.core.api.Assertions.assertThat(captured.userId()).isEqualTo("captured-user");
        org.assertj.core.api.Assertions.assertThat(captured.securityToken()).isEqualTo("captured-token");
    }
}