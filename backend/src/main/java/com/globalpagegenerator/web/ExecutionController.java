package com.globalpagegenerator.web;

import com.globalpagegenerator.dto.ExecutionDtos.ExecutionRequest;
import com.globalpagegenerator.dto.ExecutionDtos.ExecutionResponse;
import com.globalpagegenerator.security.AppUserPrincipal;
import com.globalpagegenerator.service.ExecutionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for the <b>Execution Flow</b>.
 *
 * <pre>POST /api/v1/execute</pre>
 *
 * <p>The authenticated user identity is bound through Spring Security's
 * {@link AuthenticationPrincipal} annotation, which resolves the
 * {@link AppUserPrincipal} from the current request's
 * {@link org.springframework.security.core.context.SecurityContextHolder}.
 * The client payload ({@link ExecutionRequest}) carries <em>only</em> the
 * business inputs: {@code serviceId} and {@code nid}.
 *
 * <p>This design eliminates the privilege-escalation risk of accepting
 * {@code userId} from the client.
 */
@RestController
@RequestMapping("/api/v1/execute")
public class ExecutionController {

    private final ExecutionService executionService;

    public ExecutionController(ExecutionService executionService) {
        this.executionService = executionService;
    }

    /**
     * Triggers the NID lookup against the configured upstream service.
     *
     * @param principal Spring Security principal resolved from the Bearer token
     * @param request   validated request body — serviceId + nid only
     * @return {@code 200 OK} with raw upstream JSON and the audit record ID
     */
    @PostMapping
    public ResponseEntity<ExecutionResponse> execute(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody ExecutionRequest request) {

        ExecutionResponse response = executionService.execute(principal, request);
        return ResponseEntity.ok(response);
    }
}
