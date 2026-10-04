package com.globalpagegenerator.persistence.repository;

import com.globalpagegenerator.persistence.entity.PageLoad;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PageLoadRepository extends JpaRepository<PageLoad, Long> {

    /**
     * Eager-fetches the page and all its ordered components in a single query
     * to avoid the N+1 problem when building the layout response DTO.
     */
    @Query("""
            SELECT p FROM PageLoad p
            LEFT JOIN FETCH p.components c
            WHERE p.service.id = :serviceId
            ORDER BY c.sortOrder ASC
            """)
    Optional<PageLoad> findByServiceIdWithComponents(@Param("serviceId") Long serviceId);
}
