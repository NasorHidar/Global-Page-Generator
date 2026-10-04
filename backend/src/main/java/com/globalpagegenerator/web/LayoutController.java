package com.globalpagegenerator.web;

import com.globalpagegenerator.dto.LayoutDtos.PageLayoutDto;
import com.globalpagegenerator.service.LayoutService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for the <b>Layout Flow</b>.
 *
 * <pre>GET /api/v1/layout/{serviceId}</pre>
 *
 * Returns the full page layout and component configuration for the requested
 * service so the frontend can render the dynamic page shell before the user
 * submits an NID.
 */
@RestController
@RequestMapping("/api/v1/layout")
public class LayoutController {

    private final LayoutService layoutService;

    public LayoutController(LayoutService layoutService) {
        this.layoutService = layoutService;
    }

    /**
     * Fetches the page layout and ordered component list for a service.
     *
     * @param serviceId the service whose UI layout should be loaded
     * @return {@code 200 OK} with a {@link PageLayoutDto} body,
     *         or {@code 404 Not Found} if no layout is configured
     */
    @GetMapping("/{serviceId}")
    public ResponseEntity<PageLayoutDto> getLayout(@PathVariable Long serviceId) {
        PageLayoutDto layout = layoutService.getLayoutForService(serviceId);
        return ResponseEntity.ok(layout);
    }
}
