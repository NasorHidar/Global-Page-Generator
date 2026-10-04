package com.globalpagegenerator.persistence.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * JPA {@link AttributeConverter} that transparently serialises/deserialises
 * a Jackson {@link JsonNode} to/from a PostgreSQL {@code jsonb} column.
 *
 * <p>Annotated with {@code autoApply = true} so that every entity field
 * typed {@code JsonNode} is converted automatically without explicit
 * {@code @Convert} annotations on each field.
 *
 * <p><b>Thread-safety:</b> {@link ObjectMapper} is thread-safe and is
 * injected as a singleton Spring bean (see {@code JacksonConfig}).
 */
@Converter(autoApply = true)
@Component
public class JsonNodeConverter implements AttributeConverter<JsonNode, String> {

    private final ObjectMapper objectMapper;

    @Autowired
    public JsonNodeConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String convertToDatabaseColumn(JsonNode attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(attribute);
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "Failed to serialise JsonNode to JSONB string", ex);
        }
    }

    @Override
    public JsonNode convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(dbData);
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "Failed to deserialise JSONB string to JsonNode: " + dbData, ex);
        }
    }
}
