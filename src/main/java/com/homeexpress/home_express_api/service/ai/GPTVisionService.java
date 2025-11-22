package com.homeexpress.home_express_api.service.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeexpress.home_express_api.constants.AIPrompts;
import com.homeexpress.home_express_api.dto.ai.DetectedItem;
import com.homeexpress.home_express_api.dto.ai.DetectionResult;
import com.homeexpress.home_express_api.dto.ai.EnhancedDetectedItem;
import com.homeexpress.home_express_api.exception.AIServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service cốt lõi kết nối với OpenAI Vision (GPT-4 Vision / GPT-5 mini).
 * Nhiệm vụ: Gửi ảnh lên OpenAI và nhận về danh sách đồ vật dưới dạng JSON.
 */
@Slf4j
@Service
public class GPTVisionService {

    @Value("${openai.api.key:#{null}}")
    private String openaiApiKey;

    @Value("${openai.api.url:https://api.openai.com/v1}")
    private String openaiApiUrl;

    @Value("${openai.model:gpt-5-mini}")
    private String openaiModel;

    @Value("${openai.api.timeout:30000}")
    private Integer apiTimeout;

    @Value("${ai.detection.use-enhanced-prompt:true}")
    private Boolean useEnhancedPrompt;

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public GPTVisionService(ObjectMapper objectMapper, @Qualifier("openaiRestTemplate") RestTemplate restTemplate) {
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
    }

    /**
     * Hàm chính: Gửi danh sách ảnh lên OpenAI để phân tích.
     *
     * @param imageUrls Danh sách link ảnh
     * @return Kết quả phân tích (Danh sách đồ vật + thông số chi tiết)
     */
    public DetectionResult detectItems(List<String> imageUrls) {
        log.info("🚀 Bắt đầu gọi OpenAI Vision (Model: {}): Xử lý {} ảnh", openaiModel, imageUrls.size());

        // Nếu chưa cấu hình Key thì chạy chế độ giả lập (Stub) để test
        if (openaiApiKey == null || openaiApiKey.isBlank()) {
            log.warn("⚠ Chưa có OpenAI API Key - Chạy chế độ giả lập (Stub)");
            return detectItemsStub(imageUrls);
        }

        try {
            List<EnhancedDetectedItem> enhancedItems = new ArrayList<>();

            // Duyệt từng ảnh và gửi đi phân tích
            for (int i = 0; i < imageUrls.size(); i++) {
                String imageUrl = imageUrls.get(i);
                List<EnhancedDetectedItem> items = analyzeImage(imageUrl, i);
                enhancedItems.addAll(items);
            }

            // Chuyển đổi sang dạng cơ bản để trả về
            List<DetectedItem> basicItems = toBasicItems(enhancedItems);

            // Tính độ tin cậy trung bình
            double avgConfidence = enhancedItems.stream()
                    .map(EnhancedDetectedItem::getConfidence)
                    .filter(conf -> conf != null && conf >= 0)
                    .mapToDouble(Double::doubleValue)
                    .average()
                    .orElse(0.92);

            log.info("✓ OpenAI Vision hoàn tất: Tìm thấy {} món - Độ tin cậy: {:.2f}%",
                    basicItems.size(), avgConfidence * 100);

            return DetectionResult.builder()
                    .items(basicItems)
                    .enhancedItems(enhancedItems)
                    .confidence(avgConfidence)
                    .serviceUsed("OPENAI_VISION")
                    .fallbackUsed(false)
                    .build();

        } catch (Exception e) {
            log.error("✗ Lỗi khi gọi OpenAI Vision: {}", e.getMessage(), e);
            throw new AIServiceException("OPENAI_VISION", "DETECTION_FAILED",
                    "Lỗi phân tích ảnh: " + e.getMessage());
        }
    }

    // Phân tích một bức ảnh cụ thể
    private List<EnhancedDetectedItem> analyzeImage(String imageUrl, int imageIndex) {
        try {

            String prompt = useEnhancedPrompt
                    ? AIPrompts.ENHANCED_DETECTION_PROMPT
                    : AIPrompts.DETECTION_PROMPT;

            // Xử lý ảnh đầu vào (URL hoặc Base64)
            String base64Image;
            String imageMimeType;

            if (imageUrl != null && imageUrl.startsWith("data:image/")) {
                // Nếu là Data URI (ảnh upload trực tiếp)
                int commaIndex = imageUrl.indexOf(',');
                if (commaIndex != -1) {
                    String mimePart = imageUrl.substring(5, commaIndex);
                    int semicolonIndex = mimePart.indexOf(';');
                    imageMimeType = semicolonIndex != -1 ? mimePart.substring(0, semicolonIndex) : mimePart;
                    base64Image = imageUrl.substring(commaIndex + 1);
                } else {
                    throw new RuntimeException("Định dạng ảnh không hợp lệ: " + imageUrl);
                }
            } else {
                // Nếu là URL thường -> Tải về và chuyển sang Base64
                base64Image = fetchImageAsBase64(imageUrl);
                imageMimeType = "image/jpeg"; // Mặc định là JPEG
            }

            // Chuẩn bị payload gửi đi (Tuân thủ format của OpenAI Vision API)
            Map<String, Object> textContent = new HashMap<>();
            textContent.put("type", "text");
            textContent.put("text", prompt);

            Map<String, Object> imageUrlObj = new HashMap<>();
            imageUrlObj.put("url", "data:" + imageMimeType + ";base64," + base64Image);

            Map<String, Object> imageContent = new HashMap<>();
            imageContent.put("type", "image_url");
            imageContent.put("image_url", imageUrlObj);

            List<Map<String, Object>> content = List.of(textContent, imageContent);

            Map<String, Object> message = new HashMap<>();
            message.put("role", "user");
            message.put("content", content);

            List<Map<String, Object>> messages = List.of(message);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", openaiModel);
            requestBody.put("messages", messages);
            requestBody.put("temperature", 0.4); // Độ sáng tạo vừa phải
            requestBody.put("max_tokens", useEnhancedPrompt ? 4096 : 1024);
            requestBody.put("response_format", Map.of("type", "json_object")); // Bắt buộc trả về JSON

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(openaiApiKey);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            @SuppressWarnings("rawtypes")
            ResponseEntity<Map> response = restTemplate.postForEntity(getChatCompletionsUrl(), entity, Map.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("Lỗi HTTP từ OpenAI {} với ảnh {}: {}", response.getStatusCode(), imageIndex, response.getBody());
                return Collections.emptyList();
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> responseBody = response.getBody();
            if (responseBody.containsKey("error")) {
                log.error("API trả về lỗi với ảnh {}: {}", imageIndex, responseBody.get("error"));
                return Collections.emptyList();
            }

            return parseOpenAIResponse(responseBody, imageIndex);

        } catch (Exception e) {
            log.error("Không thể phân tích ảnh {}: {}", imageIndex, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    private List<EnhancedDetectedItem> parseOpenAIResponse(Map<String, Object> responseBody, int imageIndex) {
        try {
            List<Map<String, Object>> choices = (List<Map<String, Object>>) responseBody.get("choices");
            if (choices == null || choices.isEmpty()) {
                return Collections.emptyList();
            }

            Map<String, Object> firstChoice = choices.get(0);
            Map<String, Object> message = (Map<String, Object>) firstChoice.get("message");
            if (message == null) {
                return Collections.emptyList();
            }

            String content = (String) message.get("content");
            if (content == null || content.isBlank()) {
            return Collections.emptyList();
            }

            String cleaned = cleanResponseText(content);
            log.debug("Kết quả thô từ GPT cho ảnh {}: {}", imageIndex, cleaned);
            return parseJsonItems(cleaned, imageIndex);

        } catch (Exception e) {
            log.error("Lỗi khi đọc phản hồi từ OpenAI: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    // Phân tích chuỗi JSON trả về thành danh sách Object
    private List<EnhancedDetectedItem> parseJsonItems(String jsonText, int imageIndex) {
        if (jsonText == null || jsonText.isBlank()) {
            return Collections.emptyList();
        }

        try {
            // Thử parse theo dạng Object có chứa mảng items: { "items": [...] }
            @SuppressWarnings("unchecked")
            Map<String, Object> jsonObject = objectMapper.readValue(jsonText, Map.class);
            Object itemsObj = jsonObject.get("items");
            if (itemsObj != null && itemsObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> rawItems = (List<Map<String, Object>>) itemsObj;
                List<EnhancedDetectedItem> items = new ArrayList<>();
                for (Map<String, Object> raw : rawItems) {
                    EnhancedDetectedItem item = parseEnhancedItem(raw, imageIndex);
                    if (item != null) {
                        items.add(item);
                    }
                }
                enrichEnhancedItems(items, imageIndex);
                return items;
            }

            // Thử parse theo dạng mảng trực tiếp: [...]
            List<Map<String, Object>> rawItems = objectMapper.readValue(
                    jsonText,
                    new TypeReference<List<Map<String, Object>>>() {
                    });
            List<EnhancedDetectedItem> items = new ArrayList<>();
            for (Map<String, Object> raw : rawItems) {
                EnhancedDetectedItem item = parseEnhancedItem(raw, imageIndex);
                if (item != null) {
                    items.add(item);
                }
            }
            enrichEnhancedItems(items, imageIndex);
            return items;

        } catch (Exception e) {
            log.warn("Lỗi parse JSON chuẩn: {}. Thử dùng format cũ.", e.getMessage());
            return parseLegacyItems(jsonText, imageIndex);
        }
    }

    // Chuyển đổi từng item thô sang Object Java
    @SuppressWarnings("unchecked")
    private EnhancedDetectedItem parseEnhancedItem(Map<String, Object> raw, int imageIndex) {
        try {
            EnhancedDetectedItem.EnhancedDetectedItemBuilder builder = EnhancedDetectedItem.builder();

            builder.id(raw.get("id") instanceof String s ? s : null);
            builder.name(raw.get("name") instanceof String s ? s : "Unknown Item");
            builder.category(raw.get("category") instanceof String s ? s : "other");
            builder.subcategory(raw.get("subcategory") instanceof String s ? s : null);
            builder.quantity(toInteger(raw.get("quantity"), 1));
            builder.confidence(toDouble(raw.get("confidence"), 0.85));
            builder.imageIndex(imageIndex);

            // Tọa độ khung hình (Bounding box)
            if (raw.get("bbox_norm") instanceof Map) {
                Map<String, Object> bbox = (Map<String, Object>) raw.get("bbox_norm");
                EnhancedDetectedItem.BoundingBox boundingBox = EnhancedDetectedItem.BoundingBox.builder()
                        .xMin(toDouble(bbox.get("x_min")))
                        .yMin(toDouble(bbox.get("y_min")))
                        .xMax(toDouble(bbox.get("x_max")))
                        .yMax(toDouble(bbox.get("y_max")))
                        .build();
                builder.bboxNorm(boundingBox);
            }

            // Kích thước 3 chiều
            if (raw.get("dims_cm") instanceof Map) {
                Map<String, Object> dims = (Map<String, Object>) raw.get("dims_cm");
                EnhancedDetectedItem.Dimensions dimensions = EnhancedDetectedItem.Dimensions.builder()
                        .length(toInteger(dims.get("length")))
                        .width(toInteger(dims.get("width")))
                        .height(toInteger(dims.get("height")))
                        .build();
                builder.dimsCm(dimensions);
            }
            builder.dimsConfidence(toDouble(raw.get("dims_confidence")));
            builder.dimensionsBasis(raw.get("dimensions_basis") instanceof String s ? s : null);
            builder.volumeM3(toDouble(raw.get("volume_m3")));

            // Cân nặng
            builder.weightModel(raw.get("weight_model") instanceof String s ? s : "house-move-v1");
            builder.weightKg(toDouble(raw.get("weight_kg")));
            builder.weightConfidence(toDouble(raw.get("weight_confidence")));
            builder.weightBasis(raw.get("weight_basis") instanceof String s ? s : null);

            // Các thuộc tính vận chuyển (Dễ vỡ, cần tháo...)
            builder.fragile(toBoolean(raw.get("fragile")));
            builder.twoPersonLift(toBoolean(raw.get("two_person_lift")));
            builder.stackable(toBoolean(raw.get("stackable")));
            builder.disassemblyRequired(toBoolean(raw.get("disassembly_required")));
            builder.notes(raw.get("notes") instanceof String s ? s : "");

            // Thuộc tính hình ảnh (Màu sắc, vật liệu...)
            builder.occludedFraction(toDouble(raw.get("occluded_fraction")));
            builder.orientation(raw.get("orientation") instanceof String s ? s : null);
            builder.color(raw.get("color") instanceof String s ? s : null);
            builder.roomHint(raw.get("room_hint") instanceof String s ? s : null);

            if (raw.get("material") instanceof List) {
                List<String> materials = (List<String>) raw.get("material");
                builder.material(materials);
            }

            // Thương hiệu
            builder.brand(raw.get("brand") instanceof String s && !s.isBlank() ? s : null);
            builder.model(raw.get("model") instanceof String s && !s.isBlank() ? s : null);

            EnhancedDetectedItem item = builder.build();

            // Tự tính thể tích nếu có kích thước mà thiếu thể tích
            if (item.getDimsCm() != null && item.getVolumeM3() == null) {
                item.setVolumeM3(item.calculateVolume());
            }

            return item;

        } catch (Exception e) {
            log.warn("Lỗi parse chi tiết item: {}", e.getMessage());
            return null;
        }
    }

    private List<EnhancedDetectedItem> parseLegacyItems(String jsonText, int imageIndex) {
        try {
            List<Map<String, Object>> rawItems = objectMapper.readValue(
                    jsonText,
                    new TypeReference<List<Map<String, Object>>>() {
                    });
            if (rawItems == null || rawItems.isEmpty()) {
                return Collections.emptyList();
            }

            List<EnhancedDetectedItem> items = new ArrayList<>();
            int counter = 0;
            for (Map<String, Object> raw : rawItems) {
                counter++;
                String name = raw.get("name") instanceof String s ? s : "Unknown Item";
                String category = raw.get("category") instanceof String s ? s : "other";
                Double confidence = toDouble(raw.get("confidence"), 0.85);
                Integer quantity = toInteger(raw.get("quantity"), 1);

                EnhancedDetectedItem item = EnhancedDetectedItem.builder()
                        .id(String.format("legacy-%d-%d", imageIndex + 1, counter))
                        .name(name)
                        .category(category)
                        .confidence(confidence)
                        .quantity(quantity)
                        .imageIndex(imageIndex)
                        .build();
                items.add(item);
            }
            return items;
        } catch (Exception e) {
            log.error("Lỗi parse format cũ: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // Bổ sung thông tin mặc định nếu thiếu
    private void enrichEnhancedItems(List<EnhancedDetectedItem> items, int imageIndex) {
        for (int idx = 0; idx < items.size(); idx++) {
            EnhancedDetectedItem item = items.get(idx);
            if (item.getId() == null || item.getId().isBlank()) {
                item.setId(String.format("item-%d-%d", imageIndex + 1, idx + 1));
            }
            if (item.getImageIndex() == null) {
                item.setImageIndex(imageIndex);
            }
            if (item.getConfidence() == null) {
                item.setConfidence(0.85);
            }
            if (item.getQuantity() == null || item.getQuantity() < 1) {
                item.setQuantity(1);
            }
        }
    }

    private List<DetectedItem> toBasicItems(List<EnhancedDetectedItem> enhancedItems) {
        if (enhancedItems == null || enhancedItems.isEmpty()) {
            return List.of();
        }
        return enhancedItems.stream()
                .map(EnhancedDetectedItem::toBasicDetectedItem)
                .toList();
    }

    private Double toDouble(Object value) {
        return toDouble(value, null);
    }

    private Double toDouble(Object value, Double defaultValue) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String str) {
            try {
                return Double.parseDouble(str);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private Integer toInteger(Object value) {
        return toInteger(value, null);
    }

    private Integer toInteger(Object value, Integer defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String str) {
            try {
                return Integer.parseInt(str);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private Boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String str) {
            return Boolean.parseBoolean(str);
        }
        return null;
    }

    // Làm sạch chuỗi JSON (đôi khi AI thêm ```json ở đầu)
    private String cleanResponseText(String text) {
        String cleaned = text.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        return cleaned.trim();
    }

    // Tải ảnh từ URL và chuyển sang Base64
    private String fetchImageAsBase64(String imageUrl) {
        try {
            byte[] imageBytes = restTemplate.getForObject(imageUrl, byte[].class);
            if (imageBytes == null) {
                throw new RuntimeException("Không tải được ảnh: " + imageUrl);
            }
            return java.util.Base64.getEncoder().encodeToString(imageBytes);
        } catch (Exception e) {
            log.error("Lỗi tải ảnh {}: {}", imageUrl, e.getMessage());
            throw new RuntimeException("Không thể tải ảnh: " + imageUrl, e);
        }
    }

    /**
     * Xây dựng URL endpoint chuẩn.
     */
    private String getChatCompletionsUrl() {
        String base = (openaiApiUrl != null && !openaiApiUrl.isBlank())
                ? openaiApiUrl.trim()
                : "https://api.openai.com/v1";
        String lower = base.toLowerCase();
        if (lower.contains("/chat/completions")) {
            return base;
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/chat/completions";
    }

    /**
     * Chế độ giả lập (Stub) - Dùng khi không có API Key.
     * Trả về dữ liệu mẫu để test giao diện.
     */
    private DetectionResult detectItemsStub(List<String> imageUrls) {
        log.warn("⚠ Đang dùng STUB - Chưa có cấu hình OpenAI API");

        List<EnhancedDetectedItem> enhancedItems = new ArrayList<>();

        // Giả lập kết quả trả về
        for (int i = 0; i < imageUrls.size(); i++) {
            switch (i % 4) {
                case 0 -> {
                    enhancedItems.add(createStubEnhancedItem("stub-sofa", "Three-Seat Sofa", "furniture", 0.94, i));
                    enhancedItems.add(createStubEnhancedItem("stub-table", "Coffee Table", "furniture", 0.91, i));
                }
                case 1 -> enhancedItems.add(createStubEnhancedItem("stub-fridge", "Samsung Refrigerator", "appliance", 0.96, i));
                case 2 -> {
                    enhancedItems.add(createStubEnhancedItem("stub-laptop", "Dell Laptop", "electronics", 0.93, i));
                    enhancedItems.add(createStubEnhancedItem("stub-mouse", "Wireless Mouse", "electronics", 0.89, i));
                }
                default -> enhancedItems.add(createStubEnhancedItem("stub-box", "Cardboard Box", "box", 0.87, i));
            }
        }

        List<DetectedItem> basicItems = toBasicItems(enhancedItems);

        double avgConfidence = enhancedItems.stream()
                .map(EnhancedDetectedItem::getConfidence)
                .filter(conf -> conf != null && conf >= 0)
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.92);

        return DetectionResult.builder()
                .items(basicItems)
                .enhancedItems(enhancedItems)
                .confidence(avgConfidence)
                .serviceUsed("OPENAI_VISION_STUB")
                .fallbackUsed(true)
                .build();
    }

    private EnhancedDetectedItem createStubEnhancedItem(String idPrefix,
                                                         String name,
                                                         String category,
                                                         double confidence,
                                                         int imageIndex) {
        return EnhancedDetectedItem.builder()
                .id(String.format("%s-%d", idPrefix, imageIndex + 1))
                .name(name)
                .category(category)
                .confidence(confidence)
                .quantity(1)
                .imageIndex(imageIndex)
                .build();
    }

    public boolean isConfigured() {
        return openaiApiKey != null && !openaiApiKey.isBlank();
    }
}
