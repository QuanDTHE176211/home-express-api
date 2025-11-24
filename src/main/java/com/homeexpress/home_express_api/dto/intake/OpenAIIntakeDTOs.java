package com.homeexpress.home_express_api.dto.intake;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public class OpenAIIntakeDTOs {

    public record ChatRequest(
        String model,
        List<Message> messages,
        double temperature,
        @JsonProperty("max_tokens") int maxTokens,
        @JsonProperty("response_format") Object responseFormat
    ) {}

    public record Message(String role, String content) {}

    // Response Format có thể là map hoặc object phức tạp, ta dùng Object hoặc Map cho linh hoạt ở level này, 
    // nhưng để strong type ta định nghĩa record con.
    public record ResponseFormatJsonSchema(String type, @JsonProperty("json_schema") JsonSchema jsonSchema) {}
    public record ResponseFormatJsonObject(String type) {}

    public record JsonSchema(String name, Map<String, Object> schema, boolean strict) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatCompletionResponse(List<Choice> choices) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Choice(Message message) {}
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ParsedItemRaw(
        String name,
        String brand,
        String model,
        Integer quantity,
        @JsonProperty("category_name") String categoryName,
        String size,
        @JsonProperty("is_fragile") Boolean isFragile,
        @JsonProperty("requires_disassembly") Boolean requiresDisassembly,
        Double confidence,
        String reasoning,
        @JsonProperty("weight_kg") Double weightKg,
        @JsonProperty("width_cm") Double widthCm,
        @JsonProperty("height_cm") Double heightCm,
        @JsonProperty("depth_cm") Double depthCm
    ) {}
}
