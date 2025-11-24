package com.homeexpress.home_express_api.service.intake;

import com.homeexpress.home_express_api.config.IntakeProperties;
import com.homeexpress.home_express_api.dto.intake.IntakeParseTextResponse;
import com.homeexpress.home_express_api.dto.intake.OpenAIIntakeDTOs.ParsedItemRaw;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class IntakeRefinementService {

    private final IntakeProperties intakeProperties;
    private final HeuristicParsingEngine heuristicEngine;

    public IntakeParseTextResponse.ParsedItem toDomainAndRefine(ParsedItemRaw r) {
        IntakeParseTextResponse.ParsedItem item = toDomain(r);
        return refineItem(item);
    }

    private IntakeParseTextResponse.ParsedItem refineItem(IntakeParseTextResponse.ParsedItem item) {
        String nameNormalized = HeuristicParsingEngine.removeAccents(item.getName()).toLowerCase(Locale.ROOT).trim();

        // 0. Tự động thêm dấu tiếng Việt
        if (item.getName().split("\\s+").length <= 5) {
            for (Map.Entry<String, String> entry : intakeProperties.getDictionary().entrySet()) {
                if (nameNormalized.contains(entry.getKey())) {
                    String correctedName = item.getName().replaceAll("(?i)" + Pattern.quote(entry.getKey()), entry.getValue());
                    if (!correctedName.equals(item.getName())) {
                        item.setName(HeuristicParsingEngine.capitalizeName(correctedName));
                    }
                }
            }
        }

        // 1. Sửa Category
        if ("Khác".equals(item.getCategoryName()) || item.getCategoryName() == null) {
            String detectedCat = heuristicEngine.detectCategory(nameNormalized);
            if (!"Khác".equals(detectedCat)) {
                item.setCategoryName(detectedCat);
            }
        }

        // 2. Check lại cờ Dễ vỡ
        if (!Boolean.TRUE.equals(item.getIsFragile())) {
            if (heuristicEngine.isFragile(nameNormalized)) {
                item.setIsFragile(true);
            }
        }

        // 3. Check lại cờ Tháo lắp
        double weight = item.getWeightKg() != null ? item.getWeightKg() : 0;
        boolean codeSaysDisassemble = heuristicEngine.requiresDisassembly(nameNormalized, weight);

        if (!Boolean.TRUE.equals(item.getRequiresDisassembly()) && codeSaysDisassemble) {
            item.setRequiresDisassembly(true);
        }
        if (Boolean.TRUE.equals(item.getRequiresDisassembly()) && !codeSaysDisassemble) {
            if (!nameNormalized.contains("thao")) {
                item.setRequiresDisassembly(false);
            }
        }

        // 4. Điền Brand
        if (item.getBrand() == null) {
            item.setBrand(heuristicEngine.detectBrand(nameNormalized));
        }

        // 5. Điền Model
        if (item.getModel() == null && item.getBrand() != null) {
            String inferredModel = extractModelFromName(item.getName(), item.getBrand());
            if (StringUtils.hasText(inferredModel)) {
                item.setModel(inferredModel);
            }
        }

        // 6. Tính điểm tin cậy
        item.setConfidence(calculateCompleteness(item));

        return item;
    }

    private IntakeParseTextResponse.ParsedItem toDomain(ParsedItemRaw r) {
        return IntakeParseTextResponse.ParsedItem.builder()
                .name(Objects.requireNonNullElse(r.name(), ""))
                .brand(emptyToNull(r.brand()))
                .model(emptyToNull(r.model()))
                .quantity(r.quantity() != null && r.quantity() > 0 ? r.quantity() : 1)
                .categoryName(Objects.requireNonNullElse(r.categoryName(), "Khác"))
                .size(Objects.requireNonNullElse(r.size(), "M"))
                .isFragile(Boolean.TRUE.equals(r.isFragile()))
                .requiresDisassembly(Boolean.TRUE.equals(r.requiresDisassembly()))
                .confidence(r.confidence() != null ? clamp(r.confidence().doubleValue(), 0.0, 1.0) : 0.5)
                .reasoning(r.reasoning())
                .weightKg(r.weightKg())
                .widthCm(r.widthCm())
                .heightCm(r.heightCm())
                .depthCm(r.depthCm())
                .build();
    }

    private String extractModelFromName(String name, String brand) {
        if (!StringUtils.hasText(name) || !StringUtils.hasText(brand)) return null;
        int idx = name.toLowerCase(Locale.ROOT).indexOf(brand.toLowerCase(Locale.ROOT));
        if (idx >= 0) {
            String after = name.substring(idx + brand.length()).trim();
            after = after.replaceAll("^[-:,\\s]+", "");
            String lowerAfter = after.toLowerCase(Locale.ROOT);
            if (lowerAfter.equals("tủ lạnh") || lowerAfter.equals("máy giặt") || lowerAfter.equals("điều hòa") || lowerAfter.equals("tivi")) {
                return null;
            }
            if (after.length() > 1 && !after.matches("^[\\d\\.,]+(kg|l|lit|inch|cm|m)$")) {
                return HeuristicParsingEngine.capitalizeName(after);
            }
        }
        return null;
    }

    private double calculateCompleteness(IntakeParseTextResponse.ParsedItem item) {
        double score = 0.0;
        if (StringUtils.hasText(item.getName())) score += 0.3;
        if (item.getQuantity() != null && item.getQuantity() > 0) score += 0.1;
        if (StringUtils.hasText(item.getCategoryName()) && !"Khác".equalsIgnoreCase(item.getCategoryName())) {
            score += 0.2;
        }
        boolean hasWeight = item.getWeightKg() != null && item.getWeightKg() > 0;
        boolean hasDimensions = (item.getWidthCm() != null && item.getWidthCm() > 0)
                || (item.getHeightCm() != null && item.getHeightCm() > 0)
                || (item.getDepthCm() != null && item.getDepthCm() > 0);

        if (hasWeight || hasDimensions) {
            score += 0.3;
        } else if (item.getSize() != null && !"M".equals(item.getSize())) {
            score += 0.15;
        }
        if (StringUtils.hasText(item.getBrand()) || StringUtils.hasText(item.getModel())) {
            score += 0.1;
        }
        return Math.min(Math.round(score * 100.0) / 100.0, 1.0);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
