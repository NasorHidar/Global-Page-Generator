package com.globalpagegenerator.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.ColumnTransformer;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents the top-level page layout associated with a {@link Service}.
 * The {@code layoutConfig} JSONB column can carry arbitrary presentation hints
 * (theme, grid columns, pagination settings, etc.) without requiring schema changes.
 */
@Entity
@Table(name = "page_load")
public class PageLoad {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", nullable = false)
    private Service service;

    @Column(name = "page_title", nullable = false, length = 256)
    private String pageTitle;

    @Column(name = "layout_config", columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private JsonNode layoutConfig;

    @OneToMany(mappedBy = "page", fetch = FetchType.LAZY,
               cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    private List<Component> components = new ArrayList<>();

    protected PageLoad() { /* JPA */ }

    public Long getId()              { return id; }
    public Service getService()      { return service; }
    public String getPageTitle()     { return pageTitle; }
    public JsonNode getLayoutConfig(){ return layoutConfig; }
    public List<Component> getComponents() { return components; }
}
