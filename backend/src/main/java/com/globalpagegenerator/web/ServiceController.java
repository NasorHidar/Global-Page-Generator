package com.globalpagegenerator.web;

import com.globalpagegenerator.dto.ServiceDto;
import com.globalpagegenerator.persistence.repository.ServiceRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller that exposes the list of available services to the frontend.
 *
 * <pre>GET /api/v1/services</pre>
 *
 * <p>This endpoint is intentionally <b>unauthenticated</b> (see {@code SecurityConfig})
 * because the frontend needs to populate the service selector <em>before</em>
 * the user submits a token. The response contains only display metadata — no
 * sensitive configuration is exposed.
 */
@RestController
@RequestMapping("/api/v1/services")
public class ServiceController {

    private final ServiceRepository serviceRepository;

    public ServiceController(ServiceRepository serviceRepository) {
        this.serviceRepository = serviceRepository;
    }

    /**
     * Returns all available services as lightweight DTOs for the UI dropdown.
     *
     * @return {@code 200 OK} with a list of {@link ServiceDto}
     */
    @GetMapping
    public ResponseEntity<List<ServiceDto>> listServices() {
        List<ServiceDto> services = serviceRepository.findAll().stream()
                .map(s -> new ServiceDto(s.getId(), s.getServiceName()))
                .toList();
        return ResponseEntity.ok(services);
    }
}
