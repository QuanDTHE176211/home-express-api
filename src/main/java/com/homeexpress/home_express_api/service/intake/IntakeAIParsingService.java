package com.homeexpress.home_express_api.service.intake;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeexpress.home_express_api.dto.intake.IntakeParseTextResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import io.github.resilience4j.retry.annotation.Retry;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Service chuyên trách việc phân tích danh sách đồ đạc (Intake Parsing).
 * Nhiệm vụ: Chuyển đổi văn bản khách hàng nhập vào thành danh sách vật dụng cụ thể để hệ thống tính toán.
 */
@Slf4j
@Service
public class IntakeAIParsingService {

    // === Cấu hình hệ thống ===
    @Value("${openai.api.key:#{null}}")
    private String openaiApiKey;

    @Value("${openai.api.url:https://api.openai.com/v1/chat/completions}")
    private String openaiApiUrl;

    @Value("${openai.model:gpt-5-mini}")
    private String openaiModel;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public IntakeAIParsingService(@Qualifier("intakeAiRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Hàm xử lý chính: Nhận văn bản -> Trả về danh sách đồ.
     * Quy trình: Thử dùng AI trước, nếu không được thì dùng logic thủ công.
     * @Retry: Cấu hình tự động thử lại khi gặp lỗi. Nếu thử hết số lần vẫn lỗi thì gọi hàm fallbackWithHeuristic.
     */
    @Retry(name = "intake-ai", fallbackMethod = "fallbackWithHeuristic")
    public List<IntakeParseTextResponse.ParsedItem> parseWithAI(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }

        // Bước 1: Làm sạch văn bản đầu vào (bỏ dòng trống, cắt gọt ký tự thừa)
        List<String> lines = preprocessText(text);
        if (lines.isEmpty()) {
            return List.of();
        }

        // Bước 2: Nếu không cấu hình API Key thì chuyển ngay sang xử lý thủ công
        if (!StringUtils.hasText(openaiApiKey)) {
            log.warn("Chưa cấu hình OpenAI Key. Chuyển sang chế độ xử lý thủ công.");
            return fallbackInternal(lines);
        }

        // Bước 3: Gửi yêu cầu sang OpenAI.
        int estimatedMaxItems = Math.max(20, lines.size() * 10); 
        
        String content = null;
        try {
            content = callOpenAI(buildSystemPrompt(), buildUserPrompt(lines), 1, estimatedMaxItems);
        } catch (Exception e) {
            // Cần ném ngoại lệ ra ngoài để Retry nhận biết có lỗi và kích hoạt thử lại.
            throw new RuntimeException(e);
        }

        List<ParsedItemRaw> raw = parseRaw(content);
        
        // Nếu AI trả về rỗng thì cũng coi là lỗi để thử lại.
        if (raw == null || raw.isEmpty()) {
            throw new RuntimeException("AI trả về kết quả rỗng");
        }

        log.info("AI xử lý xong: {} dòng đầu vào -> {} món đồ.", lines.size(), raw.size());
        
        // Chuyển đổi sang Domain và chạy bước Hậu kiểm (Refinement) để sửa lỗi AI
        return raw.stream()
                .map(this::toDomain)
                .map(this::refineItem) // Bước mới: Tự động sửa lỗi category/flag
                .collect(Collectors.toList());
    }

    private static final Map<String, String> VIETNAMESE_DICTIONARY = Map.ofEntries(
        Map.entry("tu lanh", "Tủ lạnh"),
        Map.entry("may giat", "Máy giặt"),
        Map.entry("may say", "Máy sấy"),
        Map.entry("ti vi", "Tivi"),
        Map.entry("tv", "Tivi"),
        Map.entry("dieu hoa", "Điều hòa"),
        Map.entry("may lanh", "Máy lạnh"),
        Map.entry("quat", "Quạt"),
        Map.entry("noi com", "Nồi cơm điện"),
        Map.entry("bep tu", "Bếp từ"),
        Map.entry("lo vi song", "Lò vi sóng"),
        Map.entry("may loc nuoc", "Máy lọc nước"),
        Map.entry("bo ban ghe", "Bộ bàn ghế"),
        Map.entry("ban an", "Bàn ăn"),
        Map.entry("ban lam viec", "Bàn làm việc"),
        Map.entry("ban hoc", "Bàn học"),
        Map.entry("ghe xoay", "Ghế xoay"),
        Map.entry("sofa", "Sofa"),
        Map.entry("giuong", "Giường ngủ"),
        Map.entry("tu quan ao", "Tủ quần áo"),
        Map.entry("ke sach", "Kệ sách"),
        Map.entry("ban trang diem", "Bàn trang điểm"),
        Map.entry("ket bia", "Két bia"),
        Map.entry("thung carton", "Thùng carton"),
        Map.entry("bo am chen", "Bộ ấm chén"),
        Map.entry("bat dia", "Bát đĩa"),
        Map.entry("ly coc", "Ly cốc"),
        Map.entry("tranh anh", "Tranh ảnh"),
        Map.entry("guong", "Gương")
    );

    // Hàm hậu kiểm: Dùng logic thủ công để "trám" các lỗ hổng của AI (ví dụ: không nhận ra tiếng Việt không dấu)
    private IntakeParseTextResponse.ParsedItem refineItem(IntakeParseTextResponse.ParsedItem item) {
        // Nếu đã có đủ thông tin quan trọng thì giữ nguyên, hoặc vẫn check lại để chắc chắn
        String nameNormalized = removeAccents(item.getName()).toLowerCase(Locale.ROOT).trim();
        
        // 0. Tự động thêm dấu tiếng Việt (Auto-correct Vietnamese)
        if (item.getName().split("\\s+").length <= 5) {
             for (Map.Entry<String, String> entry : VIETNAMESE_DICTIONARY.entrySet()) {
                 if (nameNormalized.contains(entry.getKey())) {
                     String correctedName = item.getName().replaceAll("(?i)" + Pattern.quote(entry.getKey()), entry.getValue());
                     if (!correctedName.equals(item.getName())) {
                         item.setName(capitalizeName(correctedName));
                     }
                 }
             }
        }

        // 1. Sửa Category nếu AI trả về "Khác" hoặc sai
        if ("Khác".equals(item.getCategoryName()) || item.getCategoryName() == null) {
            String detectedCat = detectCategory(nameNormalized);
            if (!"Khác".equals(detectedCat)) {
                item.setCategoryName(detectedCat);
            }
        }

        // 2. Check lại cờ Dễ vỡ (AI hay sót)
        if (!Boolean.TRUE.equals(item.getIsFragile())) {
            if (isFragile(nameNormalized)) {
                item.setIsFragile(true);
            }
        }

        // 3. Check lại cờ Tháo lắp (Logic code chuẩn hơn AI)
        double weight = item.getWeightKg() != null ? item.getWeightKg() : 0;
        boolean codeSaysDisassemble = requiresDisassembly(nameNormalized, weight);
        
        if (!Boolean.TRUE.equals(item.getRequiresDisassembly()) && codeSaysDisassemble) {
            item.setRequiresDisassembly(true);
        }
        if (Boolean.TRUE.equals(item.getRequiresDisassembly()) && !codeSaysDisassemble) {
             if (!nameNormalized.contains("thao")) {
                 item.setRequiresDisassembly(false);
             }
        }
        
        // 4. Điền Brand nếu thiếu
        if (item.getBrand() == null) {
            item.setBrand(detectBrand(nameNormalized));
        }

        // 5. Điền Model nếu thiếu (Trích xuất từ tên sau Brand)
        if (item.getModel() == null && item.getBrand() != null) {
            String inferredModel = extractModelFromName(item.getName(), item.getBrand());
            if (StringUtils.hasText(inferredModel)) {
                item.setModel(inferredModel);
            }
        }

        // 6. Tính lại độ tin cậy dựa trên độ hoàn thiện dữ liệu (Completeness Score)
        item.setConfidence(calculateCompleteness(item));

        return item;
    }

    private String extractModelFromName(String name, String brand) {
        if (!StringUtils.hasText(name) || !StringUtils.hasText(brand)) return null;
        
        // Case insensitive search for brand
        int idx = name.toLowerCase(Locale.ROOT).indexOf(brand.toLowerCase(Locale.ROOT));
        if (idx >= 0) {
            // Extract part after brand
            String after = name.substring(idx + brand.length()).trim();
            
            // Clean up leading separators
            after = after.replaceAll("^[-:,\\s]+", "");
            
            // Filter out common category words if they appear (e.g. "Samsung Tủ lạnh" -> don't want "Tủ lạnh" as model)
            String lowerAfter = after.toLowerCase(Locale.ROOT);
            if (lowerAfter.equals("tủ lạnh") || lowerAfter.equals("máy giặt") || lowerAfter.equals("điều hòa") || lowerAfter.equals("tivi")) {
                return null;
            }
            
            // If remainder is substantial, treat as model
            if (after.length() > 1 && !after.matches("^[\\d\\.,]+(kg|l|lit|inch|cm|m)$")) {
                return capitalizeName(after);
            }
        }
        return null;
    }

    private double calculateCompleteness(IntakeParseTextResponse.ParsedItem item) {
        double score = 0.0;
        
        // 1. Tên & Số lượng (Cơ bản nhất) - 40%
        if (StringUtils.hasText(item.getName())) score += 0.3;
        if (item.getQuantity() != null && item.getQuantity() > 0) score += 0.1;

        // 2. Phân loại (Quan trọng để xếp xe) - 20%
        if (StringUtils.hasText(item.getCategoryName()) && !"Khác".equalsIgnoreCase(item.getCategoryName())) {
            score += 0.2;
        }

        // 3. Kích thước / Cân nặng (Quan trọng để tính giá) - 30%
        // Có cân nặng HOẶC có kích thước chi tiết
        boolean hasWeight = item.getWeightKg() != null && item.getWeightKg() > 0;
        boolean hasDimensions = (item.getWidthCm() != null && item.getWidthCm() > 0) 
                             || (item.getHeightCm() != null && item.getHeightCm() > 0)
                             || (item.getDepthCm() != null && item.getDepthCm() > 0);
        
        if (hasWeight || hasDimensions) {
            score += 0.3;
        } else if (item.getSize() != null && !"M".equals(item.getSize())) {
            // Nếu chỉ có size ước lượng (S/L) thì cộng ít hơn một chút
            score += 0.15;
        } else {
            // Trường hợp mặc định size M không có thông số cụ thể -> không cộng điểm
        }

        // 4. Thông tin bổ sung (Brand/Model) - 10%
        if (StringUtils.hasText(item.getBrand()) || StringUtils.hasText(item.getModel())) {
            score += 0.1;
        }

        // Làm tròn 2 chữ số thập phân
        return Math.min(Math.round(score * 100.0) / 100.0, 1.0);
    }

    // Hàm dự phòng cuối cùng, chạy khi đã thử lại nhiều lần nhưng AI vẫn lỗi
    public List<IntakeParseTextResponse.ParsedItem> fallbackWithHeuristic(String text, Throwable t) {
        log.error("AI thất bại sau các lần thử lại (Retry). Chuyển sang chế độ dự phòng. Lỗi: {}", t.getMessage());
        List<String> lines = preprocessText(text);
        return fallbackInternal(lines);
    }

    // =================== GIAO TIẾP VỚI OPENAI ===================
    
    private String callOpenAI(String systemPrompt, String userPrompt, int minItems, int maxItems) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(openaiApiKey);

        // Tạo nội dung chat
        Map<String, Object> systemMsg = Map.of("role", "system", "content", systemPrompt);
        Map<String, Object> userMsg = Map.of("role", "user", "content", userPrompt);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", openaiModel);
        requestBody.put("messages", List.of(systemMsg, userMsg));
        requestBody.put("temperature", 0.1); 
        requestBody.put("max_tokens", 2000); 

        // Yêu cầu AI trả về đúng cấu trúc JSON (Structured Outputs)
        Map<String, Object> jsonSchema = buildSchema(minItems, maxItems);
        Map<String, Object> responseFormat = Map.of(
                "type", "json_schema",
                "json_schema", Map.of(
                        "name", "intake_items",
                        "schema", jsonSchema,
                        "strict", true 
                )
        );

        requestBody.put("response_format", responseFormat);

        ResponseEntity<String> resp;
        try {
            resp = restTemplate.exchange(openaiApiUrl, HttpMethod.POST,
                    new HttpEntity<>(requestBody, headers), String.class);
        } catch (HttpClientErrorException.BadRequest br) {
            log.warn("Model không hỗ trợ JSON Schema. Thử lại với định dạng json_object.");
            requestBody.put("response_format", Map.of("type", "json_object"));
            resp = restTemplate.exchange(openaiApiUrl, HttpMethod.POST,
                    new HttpEntity<>(requestBody, headers), String.class);
        }

        String body = resp.getBody();
        if (!StringUtils.hasText(body)) {
            throw new RuntimeException("OpenAI trả về nội dung rỗng.");
        }

        ChatCompletionResponse dto = objectMapper.readValue(body, ChatCompletionResponse.class);
        if (dto.choices == null || dto.choices.isEmpty() || dto.choices.get(0).message == null) {
            throw new RuntimeException("Không tìm thấy câu trả lời trong phản hồi của AI.");
        }

        String content = dto.choices.get(0).message.content;
        if (!StringUtils.hasText(content)) {
            throw new RuntimeException("Nội dung câu trả lời rỗng.");
        }
        
        return stripCodeFence(content.trim());
    }

    private Map<String, Object> buildSchema(int minItems, int maxItems) {
        Map<String, Object> item = Map.ofEntries(
                Map.entry("type", "object"),
                Map.entry("required", List.of("name", "brand", "model", "quantity", "category_name", "size", "is_fragile", "requires_disassembly", "confidence")),
                Map.entry("properties", Map.ofEntries(
                        Map.entry("name", Map.of("type", "string", "minLength", 1)),
                        Map.entry("brand", Map.of("type", Arrays.asList("string", "null"))),
                        Map.entry("model", Map.of("type", Arrays.asList("string", "null"))),
                        Map.entry("quantity", Map.of("type", "integer", "minimum", 1)),
                        Map.entry("category_name", Map.of("type", "string", "enum", List.of("Điện tử", "Nội thất", "Đồ gia dụng", "Quần áo", "Khác"))),
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
                "required", List.of("items"),
                "properties", Map.of("items", arrayItems)
        );
    }

    private String buildSystemPrompt() {
        return """
            Bạn là chuyên gia AI Logistics, chuyên phân tích danh sách đồ đạc vận chuyển.
            Nhiệm vụ: Phân tích văn bản user nhập và trả về JSON object { "items": [...] }.
            
            CÁC QUY TẮC PHÂN TÍCH QUAN TRỌNG:
            
            1. BỎ QUA CÁC TIÊU ĐỀ/NGỮ CẢNH (RẤT QUAN TRỌNG):
               - Nếu dòng bắt đầu bằng ngữ cảnh như "Chuyển văn phòng trọn gói:", "Gói combo:", "Danh sách đồ:", "Phòng khách:"... -> BỎ phần tiêu đề này, chỉ phân tích phần đồ đạc phía sau.
               - Ví dụ: "Chuyển văn phòng: 10 dàn PC" -> Chỉ lấy [10 Dàn PC].
               - Ví dụ: "Gói 1: 1 Giường ngủ" -> Chỉ lấy [1 Giường ngủ].
            
            2. TÁCH NHỎ CÁC MÓN ĐỒ (QUAN TRỌNG):
               - Nếu tên chứa "và", "với", "kèm", "cùng" -> Phải tách thành các món riêng biệt.
               - SAI: { "name": "3 bộ máy tính và 1 máy in" }
               - ĐÚNG: [ { "name": "Bộ máy tính", "qty": 3 }, { "name": "Máy in", "qty": 1 } ]
               - Ví dụ: "3 bộ máy tính" => [3 Case PC, 3 Màn hình, 3 Phím chuột] (Tách chi tiết để tính giá chính xác).
               - Ví dụ: "1 bộ bàn ăn 6 ghế" => [1 Bàn ăn, 6 Ghế ăn].
            
            3. NHÂN BẢN SỐ LƯỢNG:
               - "5 phòng ngủ, mỗi phòng 1 giường, 1 tủ" => [5 Giường ngủ, 5 Tủ quần áo]. (Tự động nhân lên).
               - "1 phòng học 30 bộ bàn ghế" => [30 Bàn học, 30 Ghế học].
            
            4. TRÍCH XUẤT THÔNG SỐ (CÂN NẶNG & KÍCH THƯỚC):
               - Nếu tên có chứa thông tin cân nặng/kích thước -> TÁCH RA khỏi tên và điền vào trường tương ứng.
               - VD: "Tủ lạnh Samsung 20kg" -> name: "Tủ lạnh Samsung", weight_kg: 20.
               - VD: "Tủ 1m2" -> width_cm: 120.
               - VD: "Sofa 2m4" -> width_cm: 240.
               - Tự điền thông số chuẩn VN nếu không có: Tủ lạnh side-by-side (~100kg, 90x180x70cm), Máy giặt (~70kg).
            
            5. XÁC ĐỊNH ĐỒ DỄ VỠ & CẦN THÁO LẮP:
               - is_fragile (Dễ vỡ) = TRUE nếu:
                 + Đồ điện tử có màn hình (Tivi, Monitor, iMac, Laptop).
                 + Đồ điện lạnh có gas/kính (Tủ lạnh, Tủ mát, Bếp từ).
                 + Đồ có kính/gương (Bàn trang điểm, Tủ kính, Bể cá, Gương soi).
                 + Đồ gốm sứ, Bát đĩa, Bình hoa, Đèn chùm.
                 + Máy lọc nước, Máy in.
               - requires_disassembly (Cần tháo lắp) = TRUE nếu:
                 + Giường ngủ (Giường đôi, Giường tầng).
                 + Tủ quần áo lớn (>2 cánh), Tủ đông/mát công nghiệp, Tủ lạnh Side-by-side (cửa đôi lớn).
                 + Bàn họp lớn, Bàn giám đốc.
                 + Sofa chữ L, Sofa giường.
                 + Máy chạy bộ, Giàn tạ đa năng, Cục nóng/lạnh điều hòa.
                 + LƯU Ý: Tủ lạnh thường/nhỏ (< 200L, < 50kg) -> KHÔNG cần tháo lắp.
               - TRƯỜNG HỢP ĐẶC BIỆT (RỦI RO KÉP):
                 + Một món đồ có thể VỪA DỄ VỠ VỪA CẦN THÁO LẮP.
                 + VD: "Tủ kính trưng bày" (Có kính dễ vỡ + Khung to cần tháo), "Đèn chùm pha lê", "Bàn trang điểm gương lớn".
            
            6. TỪ ĐIỂN TỪ VIẾT TẮT & THÔNG DỤNG:
               - "bộ pc", "dàn pc", "dàn máy", "case" => Hiểu là: Máy tính để bàn. (Lưu ý: Dễ vỡ).
                 + Nếu ghi "Bộ PC đầy đủ" -> Tách thành [Case PC, Màn hình].
               - "con lap", "máy lap" => Laptop.
               - "cục nóng", "cục lạnh" => Bộ phận Máy lạnh/Điều hòa (Cần tháo lắp, Nặng).
               - "giường m6", "giường 1m6" => width_cm: 160.
               - "giường m8", "giường 1m8" => width_cm: 180.
               - "bếp từ", "bếp hồng ngoại" => is_fragile=TRUE (Mặt kính).
            
            7. DANH MỤC CHUẨN:
               - category_name: "Điện tử", "Nội thất", "Đồ gia dụng", "Quần áo".
            
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

    private List<ParsedItemRaw> parseRaw(String content) {
        try {
            if (content.trim().startsWith("{")) {
                var root = objectMapper.readTree(content);
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

    private List<String> preprocessText(String text) {
        String normalized = text.replace("\r\n", "\n").trim();
        if (normalized.isEmpty()) {
            return List.of();
        }
        String[] parts = normalized.split("[,，;\\n]+");
        List<String> lines = new ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) {
                lines.add(t);
            }
        }
        if (lines.isEmpty()) {
            lines.add(normalized);
        }
        return lines;
    }

    private List<IntakeParseTextResponse.ParsedItem> fallbackInternal(List<String> lines) {
        List<IntakeParseTextResponse.ParsedItem> out = new ArrayList<>(lines.size());
        for (String s : lines) {
            ParsedHeu h = heuristic(s);
            out.add(IntakeParseTextResponse.ParsedItem.builder()
                    .name(capitalizeName(h.name))
                    .brand(h.brand)
                    .model(null)
                    .quantity(h.quantity)
                    .categoryName(h.category)
                    .size(h.size)
                    .weightKg(h.weightKg > 0 ? h.weightKg : null)
                    .widthCm(h.widthCm > 0 ? h.widthCm : null)
                    .heightCm(h.heightCm > 0 ? h.heightCm : null)
                    .depthCm(h.depthCm > 0 ? h.depthCm : null)
                    .isFragile(h.fragile)
                    .requiresDisassembly(h.disassembly)
                    .confidence(0.5)
                    .reasoning("Fallback Heuristic: Phân tích thủ công do AI gặp sự cố.")
                    .build());
        }
        return out;
    }

    private static final Pattern QTY_PREFIX = Pattern.compile("^\\s*(\\d{1,3})\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern INCH = Pattern.compile("(\\d{2,3})\\s*inch", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIM_3D = Pattern.compile("(\\d+)\\s*[x*]\\s*(\\d+)\\s*(?:[x*]\\s*(\\d+))?", Pattern.CASE_INSENSITIVE);
    
    private static final Set<String> BRANDS = Set.of("samsung", "lg", "ikea", "sony", "xiaomi", "panasonic", "tcl", "sharp", "toshiba", "electrolux", "bosch", "whirlpool", "casper", "hitachi", "funiki", "daikin", "mitsubishi");

    private ParsedHeu heuristic(String raw) {
        String s = stripContextPrefix(raw.trim());
        int qty = 1;
        double weight = 0;
        
        Matcher m = QTY_PREFIX.matcher(s);
        if (m.find()) {
            try {
                qty = Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignore) {
            }
            s = m.group(2).trim();
        }

        Matcher mw = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(kg|cân|kgs)", Pattern.CASE_INSENSITIVE).matcher(s);
        if (mw.find()) {
            try {
                weight = Double.parseDouble(mw.group(1));
                s = s.replace(mw.group(0), "").trim();
            } catch (NumberFormatException ignore) {}
        }

        double width = 0;
        double height = 0;
        double depth = 0;

        Matcher m3d = DIM_3D.matcher(s);
        if (m3d.find()) {
            try {
                double d1 = Double.parseDouble(m3d.group(1));
                double d2 = Double.parseDouble(m3d.group(2));
                
                width = Math.max(d1, d2);
                depth = Math.min(d1, d2);
                
                if (m3d.group(3) != null) {
                    height = Double.parseDouble(m3d.group(3));
                }
                s = s.replace(m3d.group(0), "").trim();
            } catch (NumberFormatException ignore) {}
        } else {
            Matcher md = Pattern.compile("(\\d+)[mM](\\d+)|(\\d+(?:\\.\\d+)?)\\s*[mM]|(\\d+)\\s*cm", Pattern.CASE_INSENSITIVE).matcher(s);
            if (md.find()) {
                try {
                    if (md.group(1) != null) { 
                         width = Double.parseDouble(md.group(1)) * 100 + Double.parseDouble(md.group(2)) * 10;
                    } else if (md.group(3) != null) { 
                         width = Double.parseDouble(md.group(3)) * 100;
                    } else if (md.group(4) != null) { 
                         width = Double.parseDouble(md.group(4));
                    }
                    s = s.replace(md.group(0), "").trim();
                } catch (NumberFormatException ignore) {}
            }
        }
        
        s = stripLeadingArticles(s); 
        
        String sNormalized = removeAccents(s).toLowerCase(Locale.ROOT);
        
        String brand = detectBrand(sNormalized); 
        String category = detectCategory(sNormalized);
        String size = detectSize(sNormalized, weight);
        boolean fragile = isFragile(sNormalized);
        boolean dis = requiresDisassembly(sNormalized, weight);
        
        s = s.replaceAll("\\s{2,}", " ").trim();
        
        return new ParsedHeu(s, brand, qty, category, size, fragile, dis, weight, width, height, depth);
    }

    private record ParsedHeu(String name, String brand, int quantity, String category, String size, boolean fragile, boolean disassembly, double weightKg, double widthCm, double heightCm, double depthCm) {
    }

    private static String removeAccents(String input) {
        if (input == null) return "";
        String nfd = java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFD);
        return nfd.replaceAll("\\p{InCombiningDiacriticalMarks}+", "").replace("đ", "d").replace("Đ", "D");
    }

    private String stripContextPrefix(String s) {
        String regex = "^(?i)(chuyển\\s+(?:văn\\s+phòng|nhà|kho|trọn\\s+gói)|danh\\s+sách|gói|lô|đơn|stt|mục|phòng|chuyen\\s+(?:van\\s+phong|nha|kho|tron\\s+goi))(?:[^:]*)?:\\s*";
        String res = s.replaceFirst(regex, "");
        return res;
    }

    private static String detectBrand(String normalizedName) {
        for (String b : BRANDS) {
            if (normalizedName.contains(b)) {
                return capitalizeWord(b);
            }
        }
        return null;
    }

    private static String detectCategory(String n) {
        String s = n.toLowerCase(Locale.ROOT);
        
        // 1. Hàng nặng & Đặc biệt (Ưu tiên check trước vì đặc thù)
        if (containsAny(s, "ket sat", "piano", "dan organ", "dan guitar", "be ca", "ho ca", "may chay bo", "ghe massage", "gian ta", "xe may", "xe dap", "xe dien", "tuong", "hon non bo", "cay canh", "chau cay", "may phat dien")) {
            return "Hàng nặng & Đặc biệt";
        }

        // 2. Chăn ga gối đệm
        if (containsAny(s, "chan", "ga", "goi", "dem", "nem", "man", "mung", "drap", "chieu truc", "chieu coi") && !s.contains("man hinh")) {
            return "Chăn ga gối đệm";
        }

        // 3. Sách vở & Tài liệu
        if (containsAny(s, "sach", "vo", "tai lieu", "ho so", "giay", "truyen", "tap chi", "van phong pham", "but", "cap sach", "balo")) {
            return "Sách vở & Tài liệu";
        }

        // 4. Quần áo & Phụ kiện
        if (containsAny(s, "quan", "ao", "vay", "dam", "giay", "dep", "tui xach", "vali", "mu", "non", "khan", "tat", "vo", "ca vat", "that lung", "dong ho", "trang suc", "thoi trang")) {
            return "Quần áo & Phụ kiện";
        }

        // 5. Điện lạnh (Máy lớn)
        if (containsAny(s, "tu lanh", "tu dong", "tu mat", "may giat", "may say", "dieu hoa", "may lanh", "cuc nong", "cuc lanh", "binh nong lanh", "may nuoc nong", "quat", "hut mui", "may rua bat", "may loc nuoc", "may hut am", "may loc khong khi")) {
            return "Điện lạnh";
        }

        // 6. Thiết bị điện tử
        if (containsAny(s, "tivi", "tv", "man hinh", "monitor", "loa", "amply", "dan am thanh", "sub", "mic", "karaoke", "camera", "pc", "computer", "may tinh", "laptop", "macbook", "may in", "may photo", "may scan", "tablet", "ipad", "wifi", "modem", "router", "may chieu", "ps4", "ps5", "xbox", "ban phim", "chuot")) {
            return "Thiết bị điện tử";
        }

        // 7. Đồ bếp, Hàng dễ vỡ & Đồ sinh hoạt nhỏ
        if (containsAny(s, 
            // Đồ bếp
            "xoong", "noi", "chao", "bat", "dia", "chen", "ly", "coc", "tach", "binh", "lo", "am", "gio", "sot", "mam", "khay", "dua", "thia", "muong", "dao", "thot",
            // Thiết bị bếp nhỏ
            "bep tu", "bep hong ngoai", "bep ga", "lo vi song", "lo nuong", "noi com", "noi chien", "may xay", "may ep", "may lam sua", 
            // Đồ dễ vỡ khác
            "guong", "kinh", "tranh", "anh", "den", "lo hoa", "binh hoa", "my pham", "nuoc hoa", "thuy tinh", "pha le", "gom", "su", 
            // Đồ sinh hoạt chung (TRỪ thùng carton)
            "moc", "cay lau nha", "choi", "hot rac", "xo", "chau", "thau", "gia dung")) {
            
            // EXCEPTION: "Ghe xoay" contains "xo" -> misclassified as kitchenware
            // Check specifically for furniture items that might contain these syllables
            if (!containsAny(s, "ghe", "ban", "tu")) {
                return "Đồ bếp & Dễ vỡ";
            }
        }
        
        // 8. Đóng gói (Mới thêm)
        if (containsAny(s, "thung", "hop", "carton", "xop", "bang dinh", "mang boc", "bao tai")) {
            return "Khác"; // Hoặc có thể tạo Category riêng "Vật tư đóng gói" nếu cần
        }

        // 9. Nội thất (Bàn ghế, giường tủ...)
        if (containsAny(s, "ban", "ghe", "sofa", "salon", "divan", "giuong", "phan", "sap", "tu", "ke", "gia", "vo", "man rem", "rem cua", "tham", "vach ngan", "ban tho")) {
            return "Nội thất";
        }

        return "Khác";
    }

    private static String detectSize(String n0, double w) {
        String n = n0.toLowerCase(Locale.ROOT);
        
        if (w > 0) {
            if (w < 10) return "S";
            if (w <= 30) return "M";
            return "L";
        }
        
        Matcher m = INCH.matcher(n);
        if (n.contains("tivi") || n.contains("tv") || n.contains("man hinh")) {
            if (m.find()) {
                return Integer.parseInt(m.group(1)) >= 50 ? "L" : "M";
            }
            return "M";
        }
        if (containsAny(n, "tu lanh", "may giat", "giuong", "bo sofa", "bo ban ghe", "sofa lon", "tu dong", "tu mat")) {
            return "L";
        }
        if (containsAny(n, "binh", "lo", "chen", "bat", "dia", "am", "bo am chen", "ao", "quan", "giay", "dep")) {
            return "S";
        }
        if (containsAny(n, "ban", "ghe", "tu", "ke")) {
            return "M";
        }
        return "M";
    }

    private static boolean isFragile(String n0) {
        String n = n0.toLowerCase(Locale.ROOT);
        if (containsAny(n, "de vo", "fragile", "can than")) return true;
        if (containsAny(n, "pc", "tivi", "tv", "man hinh", "laptop", "may tinh", "may tinh bang", "tablet", "loa", "amply")) return true;
        if (containsAny(n, "tu lanh", "tu mat", "tu dong", "bep tu", "bep hong ngoai", "may hut mui")) return true;
        return containsAny(n, "kinh", "guong", "gom", "su", "chen", "bat", "dia", "am", "den", "tranh", "be ca");
    }

    private static boolean requiresDisassembly(String n0, double w) {
        String n = n0.toLowerCase(Locale.ROOT);
        if (containsAny(n, "thao", "thao lap", "thao roi", "thao chan")) return true;
        
        if (w > 0 && w < 50 && !n.contains("giuong")) return false;
        
        if (containsAny(n, "giuong", "tu quan ao", "tu ao", "ban hop", "ban giam doc", "sofa goc", "sofa l")) return true;
        
        if (containsAny(n, "tu lanh", "tu mat", "tu dong")) {
            return containsAny(n, "side by side", "sbs", "2 canh", "4 canh", "cong nghiep") || w > 100;
        }
        
        if (containsAny(n, "cuc nong", "cuc lanh", "dieu hoa", "may lanh")) return true;

        if (containsAny(n, "tivi", "tv")) {
            Matcher m = INCH.matcher(n);
            if (m.find()) {
                return Integer.parseInt(m.group(1)) >= 55;
            }
            return false;
        }
        return false;
    }

    private static boolean containsAny(String s, String... kws) {
        for (String k : kws) {
            if (s.contains(k)) return true;
        }
        return false;
    }

    private static String stripLeadingArticles(String s) {
        return s.replaceFirst("^(bộ|cái|chiếc)\\s+", "").trim();
    }

    private static String capitalizeWord(String s) {
        if (s == null || s.isBlank()) return s;
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1).toLowerCase(Locale.ROOT);
    }

    private static String capitalizeName(String s) {
        if (s == null || s.isBlank()) return s;
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    private IntakeParseTextResponse.ParsedItem toDomain(ParsedItemRaw r) {
        return IntakeParseTextResponse.ParsedItem.builder()
                .name(Objects.requireNonNullElse(r.name, ""))
                .brand(emptyToNull(r.brand))
                .model(emptyToNull(r.model))
                .quantity(r.quantity != null && r.quantity > 0 ? r.quantity : 1)
                .categoryName(Objects.requireNonNullElse(r.categoryName, "Khác"))
                .size(Objects.requireNonNullElse(r.size, "M"))
                .isFragile(Boolean.TRUE.equals(r.isFragile))
                .requiresDisassembly(Boolean.TRUE.equals(r.requiresDisassembly))
                .confidence(r.confidence != null ? clamp(r.confidence.doubleValue(), 0.0, 1.0) : 0.5)
                .reasoning(r.reasoning)
                .weightKg(r.weightKg)
                .widthCm(r.widthCm)
                .heightCm(r.heightCm)
                .depthCm(r.depthCm)
                .build();
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String stripCodeFence(String s) {
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ChatCompletionResponse {
        public List<Choice> choices;

        @JsonIgnoreProperties(ignoreUnknown = true)
        static class Choice {
            public Message message;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        static class Message {
            public String role;
            public String content;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ParsedItemRaw {
        public String name;
        public String brand;
        public String model;
        public Integer quantity;

        @JsonProperty("category_name")
        public String categoryName;

        public String size;

        @JsonProperty("is_fragile")
        public Boolean isFragile;

        @JsonProperty("requires_disassembly")
        public Boolean requiresDisassembly;

        public Double confidence;

        public String reasoning;
        
        @JsonProperty("weight_kg")
        public Double weightKg;
        
        @JsonProperty("width_cm")
        public Double widthCm;
        
        @JsonProperty("height_cm")
        public Double heightCm;
        
        @JsonProperty("depth_cm")
        public Double depthCm;
    }
}
