package com.globalpagegenerator.service;

import com.globalpagegenerator.config.CacheConfig;
import com.globalpagegenerator.dto.LayoutDtos.PageLayoutDto;
import com.globalpagegenerator.exception.ResourceNotFoundException;
import com.globalpagegenerator.persistence.repository.PageLoadRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serves the Layout Flow: fetches the {@code PageLoad} and its ordered
 * {@code Component} list for a given service ID in a single database round-trip,
 * then maps the result to a serialisable DTO tree.
 *
 * <h2>Caching</h2>
 * Successful responses are cached in Redis under the {@code "layouts"} namespace
 * (see {@link CacheConfig}) keyed by the {@code serviceId}. Cache TTL is
 * 5 minutes — long enough to absorb the vast majority of repeated
 * {@code GET /api/v1/layout/{serviceId}} calls during steady-state operation,
 * short enough that admin edits to a page layout become effective promptly
 * without requiring a manual cache flush.
 *
 * <p><b>Important:</b> the {@code @Cacheable} annotation only intercepts the
 * <em>successful</em> return path. If {@link #getLayoutForService} throws
 * {@link ResourceNotFoundException}, nothing is written to Redis — 404s are
 * never cached, so a misconfigured serviceId does not poison the cache.
 */
@Service
public class LayoutService {

    private final PageLoadRepository pageLoadRepository;

    public LayoutService(PageLoadRepository pageLoadRepository) {
        this.pageLoadRepository = pageLoadRepository;
    }

    /**
     * Returns the page layout configuration for the given {@code serviceId}.
     *
     * <p>The JOIN FETCH in {@code PageLoadRepository} guarantees that the
     * {@code components} collection is eagerly loaded within the same session,
     * preventing {@code LazyInitializationException} after the transaction closes.
     *
     * <p>Results are cached in Redis under the key {@code "layouts::{serviceId}"}.
     * A 5-minute TTL is applied via {@link CacheConfig}; subsequent calls within
     * that window skip the database entirely.
     *
     * @param serviceId the service whose layout should be loaded
     * @return a serialisable {@link PageLayoutDto}
     * @throws ResourceNotFoundException if no page layout exists for the service
     */
    @Cacheable(cacheNames = CacheConfig.LAYOUT_CACHE, key = "#serviceId")
    @Transactional(readOnly = true)
    public PageLayoutDto getLayoutForService(Long serviceId) {
        return pageLoadRepository.findByServiceIdWithComponents(serviceId)
                .map(PageLayoutDto::from)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No page layout configured for service id: " + serviceId));
    }
}