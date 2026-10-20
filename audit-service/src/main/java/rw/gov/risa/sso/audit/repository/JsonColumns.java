package rw.gov.risa.sso.audit.repository;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** JSONB column (de)serialisation. Floats parse as Double, as Python's json module does. */
@Component
public class JsonColumns {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {
    };

    private final ObjectMapper mapper;

    public JsonColumns(ObjectMapper mapper) {
        this.mapper = mapper.copy()
                .disable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    public Map<String, Object> read(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return mapper.readValue(json, MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored JSON is not an object", e);
        }
    }

    public String write(Map<String, ?> value) {
        try {
            return mapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("details is not serialisable", e);
        }
    }
}
