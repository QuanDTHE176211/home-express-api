package com.homeexpress.home_express_api.service.intake;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeexpress.home_express_api.dto.intake.OpenAIIntakeDTOs.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class OpenAIClient {

    @Value("${openai.api.key:#{null}}")
    private String openaiApiKey;

    @Value("${openai.api.url:https://api.openai.com/v1/chat/completions}")
    private String openaiApiUrl;

    @Value("${openai.model:gpt-5-mini}")
    private String openaiModel;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenAIClient(@Qualifier("intakeAiRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public boolean isConfigured() {
        return StringUtils.hasText(openaiApiKey);
    }

    public String fetchAnalysis(List<String> lines) throws Exception {
        int estimatedMaxItems = Math.max(20, lines.size() * 10);
        return callOpenAI(buildSystemPrompt(), buildUserPrompt(lines), 1, estimatedMaxItems);
    }

    private String callOpenAI(String systemPrompt, String userPrompt, int minItems, int maxItems) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(openaiApiKey);

        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", systemPrompt));
        
        // Add Few-Shot Examples (NEW)
        messages.addAll(buildFewShotExamples());
        
        messages.add(new Message("user", userPrompt));

        // Build Response Format (Schema)
        Map<String, Object> jsonSchema = buildSchema(minItems, maxItems);
        Object responseFormat = new ResponseFormatJsonSchema(
            "json_schema", 
            new JsonSchema("intake_items", jsonSchema, true)
        );

        ChatRequest requestBody = new ChatRequest(openaiModel, messages, 0.1, 2000, responseFormat);

        ResponseEntity<String> resp;
        try {
            resp = restTemplate.exchange(openaiApiUrl, HttpMethod.POST,
                    new HttpEntity<>(requestBody, headers), String.class);
        } catch (HttpClientErrorException.BadRequest br) {
            log.warn("Model không hỗ trợ JSON Schema. Thử lại với định dạng json_object.");
            ChatRequest fallbackRequest = new ChatRequest(openaiModel, messages, 0.1, 2000, new ResponseFormatJsonObject("json_object"));
            resp = restTemplate.exchange(openaiApiUrl, HttpMethod.POST,
                    new HttpEntity<>(fallbackRequest, headers), String.class);
        }

        String body = resp.getBody();
        if (!StringUtils.hasText(body)) {
            throw new RuntimeException("OpenAI trả về nội dung rỗng.");
        }

        ChatCompletionResponse dto = objectMapper.readValue(body, ChatCompletionResponse.class);
        if (dto.choices() == null || dto.choices().isEmpty() || dto.choices().get(0).message() == null) {
            throw new RuntimeException("Không tìm thấy câu trả lời trong phản hồi của AI.");
        }

        String content = dto.choices().get(0).message().content();
        if (!StringUtils.hasText(content)) {
            throw new RuntimeException("Nội dung câu trả lời rỗng.");
        }
        
        return stripCodeFence(content.trim());
    }

    private List<Message> buildFewShotExamples() {
        String input1 = """
            Danh sách cần phân tích:
            1. Chuyển nhà: 1 bộ bàn ăn 6 ghế và 2 dàn PC (mỗi dàn 1 case 1 màn)
            """;
            
        String output1 = """
            {
              "thought_process": "Input '1 bộ bàn ăn 6 ghế' cần tách thành 1 Bàn ăn và 6 Ghế ăn. Input '2 dàn PC...' cần tách và nhân lên thành 2 Case PC và 2 Màn hình.",
              "items": [
                {"name": "Bàn ăn", "quantity": 1, "category_name": "Nội thất", "is_fragile": false, "requires_disassembly": true, "confidence": 0.9},
                {"name": "Ghế ăn", "quantity": 6, "category_name": "Nội thất", "is_fragile": false, "requires_disassembly": false, "confidence": 0.9},
                {"name": "Case PC", "quantity": 2, "category_name": "Thiết bị điện tử", "is_fragile": true, "requires_disassembly": false, "confidence": 0.95},
                {"name": "Màn hình", "quantity": 2, "category_name": "Thiết bị điện tử", "is_fragile": true, "requires_disassembly": false, "confidence": 0.95}
              ]
            }
            """;
            
        return List.of(
            new Message("user", input1),
            new Message("assistant", output1)
        );
    }

    public List<ParsedItemRaw> parseRawJson(String content) {
        try {
            if (content.trim().startsWith("{")) {
                var root = objectMapper.readTree(content);
                if (root.has("thought_process")) {
                    log.info("AI Thought Process: {}", root.get("thought_process").asText());
                }
                if (root.has("items")) {
                    return objectMapper.readValue(
                            root.get("items").toString(),
                            objectMapper.getTypeFactory().constructCollectionType(List.class, ParsedItemRaw.class)
                    );
                }
            }
            if (content.trim().startsWith("[")) {
                return objectMapper.readValue(
                        content,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, ParsedItemRaw.class)
                );
            }
            ParsedItemRaw single = objectMapper.readValue(content, ParsedItemRaw.class);
            return List.of(single);
        } catch (Exception e) {
            log.error("Không đọc được JSON từ AI. Nội dung: {}", content, e);
            return List.of();
        }
    }

    private String stripCodeFence(String s) {
        String t = s.trim();
        if (t.startsWith("```")) {
            int idx = t.indexOf('\n');
            if (idx > -1) {
                t = t.substring(idx + 1);
            }
            int end = t.lastIndexOf("```");
            if (end > -1) {
                t = t.substring(0, end);
            }
        }
        return t.trim();
    }

    private Map<String, Object> buildSchema(int minItems, int maxItems) {
        List<String> STANDARD_CATEGORIES = List.of(
            "Điện lạnh", "Thiết bị điện tử", "Nội thất", "Đồ bếp & Dễ vỡ",
            "Chăn ga gối đệm", "Quần áo & Phụ kiện", "Sách vở & Tài liệu",
            "Hàng nặng & Đặc biệt", "Khác"
        );

        Map<String, Object> item = Map.ofEntries(
                Map.entry("type", "object"),
                Map.entry("required", List.of("name", "brand", "model", "quantity", "category_name", "size", "is_fragile", "requires_disassembly", "confidence")),
                Map.entry("properties", Map.ofEntries(
                        Map.entry("name", Map.of("type", "string", "minLength", 1)),
                        Map.entry("brand", Map.of("type", Arrays.asList("string", "null"))),
                        Map.entry("model", Map.of("type", Arrays.asList("string", "null"))),
                        Map.entry("quantity", Map.of("type", "integer", "minimum", 1)),
                        Map.entry("category_name", Map.of("type", "string", "enum", STANDARD_CATEGORIES)),
                        Map.entry("size", Map.of("type", "string", "enum", List.of("S", "M", "L"))),
                        Map.entry("is_fragile", Map.of("type", "boolean")),
                        Map.entry("requires_disassembly", Map.of("type", "boolean")),
                        Map.entry("confidence", Map.of("type", "number", "minimum", 0.0, "maximum", 1.0)),
                        Map.entry("reasoning", Map.of("type", "string", "description", "Giải thích ngắn gọn logic phân tách hoặc ước lượng.")),
                        Map.entry("weight_kg", Map.of("type", "number", "minimum", 0)),
                        Map.entry("width_cm", Map.of("type", "number", "minimum", 0)),
                        Map.entry("height_cm", Map.of("type", "number", "minimum", 0)),
                        Map.entry("depth_cm", Map.of("type", "number", "minimum", 0))
                ))
        );

        Map<String, Object> arrayItems = Map.of(
                "type", "array",
                "minItems", minItems,
                "maxItems", maxItems,
                "items", item
        );

        return Map.of(
                "type", "object",
                "required", List.of("thought_process", "items"),
                "properties", Map.of(
                        "thought_process", Map.of(
                                "type", "string",
                                "description", "Phân tích từng bước logic xử lý toàn bộ danh sách đầu vào, giải thích cách tách/gộp/nhân bản số lượng."
                        ),
                        "items", arrayItems
                )
        );
    }

    private String buildSystemPrompt() {
        return """
            Bạn là chuyên gia AI Logistics, chuyên phân tích danh sách đồ đạc vận chuyển.
            Nhiệm vụ: Phân tích văn bản user nhập và trả về JSON object chứa quy trình suy luận (thought_process) và danh sách items.
            
            QUY TRÌNH LÀM VIỆC (Chain-of-Thought):
            1. Phân tích Input: Đọc kỹ toàn bộ danh sách.
            2. Xác định trường hợp đặc biệt: Tìm các mục cần tách nhỏ ("bộ PC", "combo"), nhân số lượng ("5 phòng...").
            3. Trình bày suy nghĩ: Ghi lại quá trình phân tích vào trường `thought_process`. Giải thích rõ ràng tại sao bạn lại tách/nhân như vậy.
            4. Xuất JSON: Tạo mảng `items` dựa trên phân tích trên.
            
            CÁC QUY TẮC PHÂN TÍCH QUAN TRỌNG:
            
            1. BỎ QUA CÁC TIÊU ĐỀ/NGỮ CẢNH (RẤT QUAN TRỌNG):
               - Nếu dòng bắt đầu bằng ngữ cảnh như "Chuyển văn phòng trọn gói:", "Gói combo:", "Danh sách đồ:", "Phòng khách:"... -> BỎ phần tiêu đề này, chỉ phân tích phần đồ đạc phía sau.
            
            2. TÁCH NHỎ CÁC MÓN ĐỒ (QUAN TRỌNG):
               - Nếu tên chứa "và", "với", "kèm", "cùng" -> Phải tách thành các món riêng biệt.
            
            3. NHÂN BẢN SỐ LƯỢNG:
               - "5 phòng ngủ, mỗi phòng 1 giường, 1 tủ" => [5 Giường ngủ, 5 Tủ quần áo].
            
            4. TRÍCH XUẤT THÔNG SỐ (CÂN NẶNG & KÍCH THƯỚC):
               - Nếu tên có chứa thông tin cân nặng/kích thước -> TÁCH RA khỏi tên và điền vào trường tương ứng.
            
            5. XÁC ĐỊNH ĐỒ DỄ VỠ & CẦN THÁO LẮP:
               - is_fragile (Dễ vỡ) = TRUE nếu: Đồ điện tử, điện lạnh có gas/kính, gương kính, gốm sứ.
               - requires_disassembly (Cần tháo lắp) = TRUE nếu: Giường, Tủ lớn, Bàn họp, Sofa L, Điều hòa.
            
            6. TỪ ĐIỂN TỪ VIẾT TẮT & THÔNG DỤNG:
               - "bộ pc" => [Case PC, Màn hình].
               - "giường m6" => width_cm: 160.
            
            7. DANH MỤC CHUẨN (BẮT BUỘC CHỌN TRONG LIST SAU):
               - "Điện lạnh", "Thiết bị điện tử", "Nội thất", "Đồ bếp & Dễ vỡ",
               - "Chăn ga gối đệm", "Quần áo & Phụ kiện", "Sách vở & Tài liệu",
               - "Hàng nặng & Đặc biệt", "Khác"
            
            Chỉ trả về JSON.
            """;
    }

    private String buildUserPrompt(List<String> lines) {
        StringBuilder sb = new StringBuilder("Danh sách cần phân tích (mỗi dòng = 1 item):\n");
        for (int i = 0; i < lines.size(); i++) {
            sb.append(i + 1).append(". ").append(lines.get(i)).append("\n");
        }
        return sb.toString();
    }
}
