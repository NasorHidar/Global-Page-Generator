package com.globalpagegenerator.dto;

/**
 * Lightweight projection of a {@link com.globalpagegenerator.persistence.entity.Service}
 * returned by {@code GET /api/v1/services} for the frontend service-selector dropdown.
 *
 * @param id          service primary key — used as the selection value
 * @param serviceName human-readable label displayed in the dropdown
 */
public record ServiceDto(Long id, String serviceName) { }
