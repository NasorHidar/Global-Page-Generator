package com.globalpagegenerator.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.globalpagegenerator.persistence.entity.Component;
import com.globalpagegenerator.persistence.entity.PageLoad;

import java.util.List;

/**
 * Data Transfer Objects are Java 21 Records — immutable, concise, and
 * serialisable by Jackson without any additional configuration.
 *
 * <p>The nested record hierarchy mirrors the Page → Component relationship
 * so the frontend receives a single, self-describing JSON tree.
 */
public final class LayoutDtos {

    private LayoutDtos() { /* utility class */ }

    // ── Component DTO ──────────────────────────────────────────────────────────

    /**
     * Carries the component type and its full {@code properties} JSONB payload.
     * The frontend inspects {@code properties.columns[*].jsonPath} to know
     * which fields to extract from the execution response.
     *
     * @param id            database primary key
     * @param componentType discriminator string (e.g., {@code "DATA_TABLE"})
     * @param properties    raw JSONB node — forwarded as-is to avoid double-mapping
     * @param sortOrder     determines render order on the page
     */
    public record ComponentDto(
            Long id,
            String componentType,
            JsonNode properties,
            int sortOrder
    ) {
        public static ComponentDto from(Component c) {
            return new ComponentDto(
                    c.getId(),
                    c.getComponentType(),
                    c.getProperties(),
                    c.getSortOrder()
            );
        }
    }

    // ── Page Layout DTO ────────────────────────────────────────────────────────

    /**
     * Top-level layout response sent to the frontend when it requests the
     * layout for a given service.
     *
     * @param pageId       identifier used by the frontend for cache keying
     * @param pageTitle    displayed in the browser tab / page heading
     * @param layoutConfig arbitrary JSON hints for the page shell
     * @param components   ordered list of component descriptors
     */
    public record PageLayoutDto(
            Long pageId,
            String pageTitle,
            JsonNode layoutConfig,
            List<ComponentDto> components
    ) {
        public static PageLayoutDto from(PageLoad page) {
            List<ComponentDto> componentDtos = page.getComponents().stream()
                    .map(ComponentDto::from)
                    .toList();   // Java 16+ immutable list

            return new PageLayoutDto(
                    page.getId(),
                    page.getPageTitle(),
                    page.getLayoutConfig(),
                    componentDtos
            );
        }
    }
}
