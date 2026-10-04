package com.globalpagegenerator.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.globalpagegenerator.validation.ValidNid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * DTOs for the Execution flow.
 */
public final class ExecutionDtos {

    private ExecutionDtos() { /* utility class */ }

    // ── Inbound request ────────────────────────────────────────────────────────

    /**
     * Payload submitted by the frontend to trigger an NID lookup.
     *
     * <p><b>Security note:</b> {@code userId} is intentionally <em>absent</em>
     * from this record. The authenticated user identity is resolved exclusively
     * from the {@link com.globalpagegenerator.security.AppUserPrincipal} stored
     * in {@link org.springframework.security.core.context.SecurityContextHolder}
     * by {@code BearerTokenAuthenticationFilter}. Accepting userId from the
     * client payload would be a privilege-escalation vector.
     *
     * @param serviceId target service to call
     * @param nid       National ID entered by the operator
     */
    public record ExecutionRequest(
            @NotNull @Positive Long serviceId,
            @NotBlank @ValidNid String nid
    ) { }

    // ── Outbound response ──────────────────────────────────────────────────────

    /**
     * Returned to the frontend after a successful execution.
     *
     * @param requestId    database ID of the persisted {@code RequestSave} row
     * @param status       terminal status string ({@code SUCCESS}, {@code API_ERROR},
     *                     or {@code TIMEOUT})
     * @param responseData raw JSON returned by the upstream service — the frontend
     *                     applies JSONPath expressions from the component config
     *                     to extract display values
     */
    public record ExecutionResponse(
            Long requestId,
            String status,
            JsonNode responseData
    ) { }
}
