package org.atlas.plugin.orchestrator;

import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import jakarta.json.spi.JsonProvider;

import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts between JSON text and the JSON-like Java values of the plugin contract, using the
 * Jakarta JSON Processing implementation provided by the server.
 */
final class Json {

    private final JsonProvider provider = JsonProvider.provider();

    Object parse(String text) {
        try (JsonReader reader = provider.createReader(new StringReader(text))) {
            return toJava(reader.readValue());
        }
    }

    String write(Object value) {
        return toJson(value).toString();
    }

    private Object toJava(JsonValue value) {
        return switch (value.getValueType()) {
            case OBJECT -> {
                Map<String, Object> map = new LinkedHashMap<>();
                ((JsonObject) value).forEach((key, item) -> map.put(key, toJava(item)));
                yield map;
            }
            case ARRAY -> {
                List<Object> list = new ArrayList<>();
                ((JsonArray) value).forEach(item -> list.add(toJava(item)));
                yield list;
            }
            case STRING -> ((JsonString) value).getString();
            case NUMBER -> {
                JsonNumber number = (JsonNumber) value;
                yield number.isIntegral() ? (Object) number.longValueExact() : number.bigDecimalValue();
            }
            case TRUE -> Boolean.TRUE;
            case FALSE -> Boolean.FALSE;
            case NULL -> null;
        };
    }

    private JsonValue toJson(Object value) {
        if (value == null) {
            return JsonValue.NULL;
        }
        if (value instanceof Map<?, ?> map) {
            JsonObjectBuilder builder = provider.createObjectBuilder();
            map.forEach((key, item) -> builder.add(String.valueOf(key), toJson(item)));
            return builder.build();
        }
        if (value instanceof List<?> list) {
            JsonArrayBuilder builder = provider.createArrayBuilder();
            list.forEach(item -> builder.add(toJson(item)));
            return builder.build();
        }
        if (value instanceof Boolean bool) {
            return bool ? JsonValue.TRUE : JsonValue.FALSE;
        }
        if (value instanceof Number number) {
            return provider.createValue(new BigDecimal(number.toString()));
        }
        return provider.createValue(value.toString());
    }
}
