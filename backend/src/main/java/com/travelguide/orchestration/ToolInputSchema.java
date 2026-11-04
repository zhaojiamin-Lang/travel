package com.travelguide.orchestration;

import java.util.Map;
import java.util.Set;

/**
 * MCP 风格的工具输入描述。
 *
 * <p>记录字段直接映射为 MCP 工具描述中的 JSON Schema。</p>
 */
public record ToolInputSchema(
        String type,
        Map<String, Property> properties,
        Set<String> required
) {

    public ToolInputSchema {
        type = type == null || type.isBlank() ? "object" : type;
        properties = Map.copyOf(properties == null ? Map.of() : properties);
        required = Set.copyOf(required == null ? Set.of() : required);
    }

    public static ToolInputSchema object(
            Map<String, Property> properties,
            String... required
    ) {
        return new ToolInputSchema(
                "object",
                properties,
                Set.of(required)
        );
    }

    public record Property(String type, String description) {

        public Property {
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException("工具参数类型不能为空");
            }
        }

        public static Property string(String description) {
            return new Property("string", description);
        }

        public static Property integer(String description) {
            return new Property("integer", description);
        }

        public static Property object(String description) {
            return new Property("object", description);
        }
    }
}
