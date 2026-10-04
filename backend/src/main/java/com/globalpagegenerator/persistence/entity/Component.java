package com.globalpagegenerator.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.ColumnTransformer;

/**
 * A single UI component belonging to a {@link PageLoad}.
 *
 * <p>The {@code properties} JSONB column drives the frontend rendering engine.
 * It contains a list of column descriptors, each carrying:
 * <ul>
 *   <li>{@code label}    – column header shown in the table</li>
 *   <li>{@code jsonPath} – Jayway JsonPath expression evaluated against the raw response</li>
 *   <li>{@code type}     – optional hint for frontend formatting (e.g., {@code "date"}, {@code "currency"})</li>
 * </ul>
 *
 * <p>Example {@code properties} value stored in the database:
 * <pre>{@code
 * {
 *   "columns": [
 *     { "label": "Full Name",   "jsonPath": "$.person.fullName",    "type": "text" },
 *     { "label": "Date of Birth","jsonPath": "$.person.dob",        "type": "date" },
 *     { "label": "Address",     "jsonPath": "$.person.address.line1","type": "text" }
 *   ]
 * }
 * }</pre>
 */
@Entity
@Table(name = "component",
        indexes = @Index(name = "idx_component_page_sort", columnList = "page_id, sort_order"))
public class Component {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "page_id", nullable = false)
    private PageLoad page;

    /**
     * Discriminator for the frontend renderer:
     * {@code "DATA_TABLE"}, {@code "CARD"}, {@code "BADGE"}, etc.
     */
    @Column(name = "component_type", nullable = false, length = 64)
    private String componentType;

    @Column(name = "properties", columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private JsonNode properties;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    protected Component() { /* JPA */ }

    public Long getId()              { return id; }
    public PageLoad getPage()        { return page; }
    public String getComponentType() { return componentType; }
    public JsonNode getProperties()  { return properties; }
    public Integer getSortOrder()    { return sortOrder; }
}
