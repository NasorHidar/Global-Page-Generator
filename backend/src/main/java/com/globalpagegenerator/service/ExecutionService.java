package com.globalpagegenerator.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.globalpagegenerator.dto.ExecutionDtos.ExecutionRequest;
import com.globalpagegenerator.dto.ExecutionDtos.ExecutionResponse;
import com.globalpagegenerator.exception.ResourceNotFoundException;
import com.globalpagegenerator.exception.UpstreamApiException;
import com.globalpagegenerator.persistence.entity.Log;
import com.globalpagegenerator.persistence.entity.RequestSave;
import com.globalpagegenerator.persistence.entity.Service;
import com.globalpagegenerator.persistence.entity.User;
import com.globalpagegenerator.persistence.repository.LogRepository;
import com.globalpagegenerator.persistence.repository.RequestSaveRepository;
import com.globalpagegenerator.persistence.repository.ServiceRepository;
import com.globalpagegenerator.persistence.repository.UserRepository;
import com.globalpagegenerator.security.AppUserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Iterator;
import java.util.Map;

/**
 * Core orchestrator for the <em>Execution Flow</em>.
 *
 * <h2>Transaction Strategy</h2>
 * <p>Three separate, fine-grained transactions are used to avoid holding a
 * database connection open during network I/O:
 * <ol>
 *   <li><b>TX-READ</b> ({@code readOnly=true}) – resolves User and Service entities.</li>
 *   <li><b>TX-INIT</b> – persists the initial {@code RequestSave} row with
 *       {@code status=PENDING} and commits before the network call begins.</li>
 *   <li><b>TX-RESULT</b> – after the API call returns, updates the row to
 *       {@code SUCCESS} (or {@code API_ERROR}/{@code TIMEOUT}) and appends an
 *       immutable {@link Log} entry. Both writes are atomic.</li>
 * </ol>
 *
 * <h2>Payload Construction</h2>
 * <p>{@link Service#getRequestFormat()} holds a JSON template where every
 * string leaf may contain {@code {{nid}}} and/or {@code {{token}}} tokens.
 * A recursive tree walk via {@link #hydrateNode} replaces them without
 * mutating the entity's cached {@code JsonNode}.
 */
@Component
public class ExecutionService {

    private static final Logger LOG = LoggerFactory.getLogger(ExecutionService.class);

    private static final String TOKEN_NID        = "{{nid}}";
    private static final String TOKEN_TOKEN      = "{{token}}";
    private static final String STATUS_PENDING   = "PENDING";
    private static final String STATUS_SUCCESS   = "SUCCESS";
    private static final String STATUS_API_ERROR = "API_ERROR";
    private static final String STATUS_TIMEOUT   = "TIMEOUT";
    private static final int    LOG_MSG_MAX_LEN  = 1024;

    private final UserRepository        userRepository;
    private final ServiceRepository     serviceRepository;
    private final RequestSaveRepository requestSaveRepository;
    private final LogRepository         logRepository;
    private final ObjectMapper          objectMapper;
    private final RestClient.Builder    restClientBuilder;

    public ExecutionService(
            UserRepository userRepository,
            ServiceRepository serviceRepository,
            RequestSaveRepository requestSaveRepository,
            LogRepository logRepository,
            ObjectMapper objectMapper,
            RestClient.Builder restClientBuilder) {
        this.userRepository        = userRepository;
        this.serviceRepository     = serviceRepository;
        this.requestSaveRepository = requestSaveRepository;
        this.logRepository         = logRepository;
        this.objectMapper          = objectMapper;
        this.restClientBuilder     = restClientBuilder;
    }

    // =========================================================================
    // Public orchestration API
    // =========================================================================

    /**
     * Handles the complete Execution Flow for a single NID lookup.
     *
     * <p>The {@code principal} is resolved from the
     * {@link org.springframework.security.core.context.SecurityContextHolder}
     * by the calling controller — it is never derived from the request body.
     *
     * @param principal authenticated user principal from the SecurityContext
     * @param request   validated inbound DTO from the REST controller
     * @return execution response carrying the requestId and raw upstream JSON
     */
    public ExecutionResponse execute(AppUserPrincipal principal, ExecutionRequest request) {

        // TX-READ: resolve entities without holding a write lock
        User user       = findUser(principal.userId());
        Service service = findService(request.serviceId());

        // Pure computation — no transaction needed
        JsonNode finalPayload = buildPayload(
                service.getRequestFormat(), request.nid(), user.getSecurityToken());

        // TX-INIT: commit PENDING record before any I/O
        RequestSave savedRequest = persistInitialRecord(user, service, request.nid(), finalPayload);

        // Network call — deliberately outside any transaction boundary
        JsonNode responseData;
        try {
            responseData = callUpstreamApi(service.getEndpointUrl(), finalPayload);
        } catch (UpstreamApiException apiEx) {
            persistFailure(savedRequest, apiEx);   // TX-RESULT (failure branch)
            throw apiEx;                            // propagate to controller
        }

        // TX-RESULT: success branch
        return persistResult(savedRequest, responseData);
    }

    // =========================================================================
    // Entity resolution  (read-only transactions)
    // =========================================================================

    /**
     * Resolves the User entity from the authenticated principal's userId.
     * The userId originates from the SecurityContext — never from client input.
     */
    @Transactional(readOnly = true)
    protected User findUser(String userId) {
        return userRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
    }

    @Transactional(readOnly = true)
    protected Service findService(Long serviceId) {
        return serviceRepository.findById(serviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Service not found: " + serviceId));
    }

    // =========================================================================
    // Payload construction  (pure — no I/O)
    // =========================================================================

    /**
     * Deep-copies the template tree and replaces {@code {{nid}}} / {@code {{token}}}
     * in every string leaf node via a recursive walk.
     *
     * @param template      JSONB template from {@link Service#getRequestFormat()}
     * @param nid           National ID entered by the operator
     * @param securityToken opaque token from the {@link User} row
     * @return hydrated {@link JsonNode} ready to POST
     */
    JsonNode buildPayload(JsonNode template, String nid, String securityToken) {
        if (template == null) {
            throw new IllegalStateException("Service request_format template must not be null");
        }
        return hydrateNode(template.deepCopy(), nid, securityToken);
    }

    /**
     * Recursively walks {@code node} and replaces token strings in text leaves.
     * Numeric, boolean, and null nodes are returned unchanged.
     */
    private JsonNode hydrateNode(JsonNode node, String nid, String token) {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                obj.set(entry.getKey(), hydrateNode(entry.getValue(), nid, token));
            }
            return obj;
        }

        if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                arr.set(i, hydrateNode(arr.get(i), nid, token));
            }
            return arr;
        }

        if (node.isTextual()) {
            String replaced = node.asText()
                    .replace(TOKEN_NID,   nid)
                    .replace(TOKEN_TOKEN, token != null ? token : "");
            return objectMapper.getNodeFactory().textNode(replaced);
        }

        return node;
    }

    // =========================================================================
    // TX-INIT: persist initial PENDING row
    // =========================================================================

    @Transactional
    protected RequestSave persistInitialRecord(
            User user, Service service, String nid, JsonNode payload) {

        RequestSave record = RequestSave.builder()
                .user(user)
                .service(service)
                .nidInput(nid)
                .requestPayload(payload)
                .status(STATUS_PENDING)
                .build();

        return requestSaveRepository.save(record);
    }

    // =========================================================================
    // Network I/O — NO @Transactional (intentional)
    // =========================================================================

    /**
     * Executes a synchronous POST to the upstream endpoint using Spring's
     * {@link RestClient}. This method is deliberately <em>not</em> annotated with
     * {@code @Transactional} so no database connection is held open during the call.
     *
     * <p><b>Error handling:</b>
     * <ul>
     *   <li>{@link RestClientResponseException} – upstream 4xx/5xx: wrapped in
     *       {@link UpstreamApiException} with the HTTP status code.</li>
     *   <li>{@link ResourceAccessException} – TCP timeout / DNS failure: wrapped
     *       in {@link UpstreamApiException} with synthetic code {@code -1}.</li>
     * </ul>
     *
     * @param endpointUrl fully-qualified target URL
     * @param payload     hydrated request body
     * @return raw JSON response from the upstream service
     */
    JsonNode callUpstreamApi(String endpointUrl, JsonNode payload) {
        LOG.info("Calling upstream API: {}", endpointUrl);
        try {
            String bodyString = objectMapper.writeValueAsString(payload);

            return restClientBuilder.build()
                    .post()
                    .uri(endpointUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(bodyString)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        int code = res.getStatusCode().value();
                        throw new UpstreamApiException(
                                "Upstream API returned error status: " + code, code, null);
                    })
                    .body(JsonNode.class);

        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialise request payload", ex);

        } catch (ResourceAccessException ex) {
            LOG.error("Upstream API timeout/network error for [{}]: {}", endpointUrl, ex.getMessage());
            throw new UpstreamApiException(
                    "Upstream API call timed out or is unreachable: " + endpointUrl, ex);

        } catch (RestClientResponseException ex) {
            LOG.error("Upstream API HTTP error [{}]: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new UpstreamApiException(
                    "Upstream API error: " + ex.getMessage(),
                    ex.getStatusCode().value(),
                    ex);
        }
    }

    // =========================================================================
    // TX-RESULT: success branch
    // =========================================================================

    /**
     * Opens a new transaction to update the request row to {@code SUCCESS}
     * and append an immutable audit log entry. Both writes are atomic.
     */
    @Transactional
    protected ExecutionResponse persistResult(RequestSave requestSave, JsonNode responseData) {
        RequestSave managed = reattach(requestSave.getId());
        managed.setResponseData(responseData);
        managed.setStatus(STATUS_SUCCESS);
        requestSaveRepository.save(managed);

        appendLog(managed, "EXECUTE_API", 200, "Upstream API call succeeded.");

        LOG.info("Execution [id={}] completed successfully.", managed.getId());
        return new ExecutionResponse(managed.getId(), STATUS_SUCCESS, responseData);
    }

    // =========================================================================
    // TX-RESULT: failure branch
    // =========================================================================

    /**
     * Opens a new transaction to record the failure status and audit log.
     * Ensures the failure is durably committed even when the upstream is unreachable.
     */
    @Transactional
    protected void persistFailure(RequestSave requestSave, UpstreamApiException ex) {
        RequestSave managed = reattach(requestSave.getId());

        String status = (ex.getUpstreamStatusCode() == -1) ? STATUS_TIMEOUT : STATUS_API_ERROR;
        managed.setStatus(status);
        requestSaveRepository.save(managed);

        String msg = truncate("Upstream API failed: " + ex.getMessage());
        appendLog(managed, "EXECUTE_API", ex.getUpstreamStatusCode(), msg);

        LOG.error("Execution [id={}] failed with status [{}]. Cause: {}",
                managed.getId(), status, ex.getMessage());
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /** Re-fetches a {@link RequestSave} by ID to attach it to the current session. */
    private RequestSave reattach(Long id) {
        return requestSaveRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException(
                        "RequestSave disappeared after initial persist — id=" + id));
    }

    private void appendLog(RequestSave request, String action, int statusCode, String message) {
        Log entry = Log.builder()
                .request(request)
                .action(action)
                .statusCode(statusCode)
                .message(message)
                .build();
        logRepository.save(entry);
    }

    private static String truncate(String s) {
        return (s != null && s.length() > LOG_MSG_MAX_LEN) ? s.substring(0, LOG_MSG_MAX_LEN) : s;
    }
}
