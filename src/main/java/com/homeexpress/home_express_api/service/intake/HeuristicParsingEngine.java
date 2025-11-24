package com.homeexpress.home_express_api.service.intake;

import com.homeexpress.home_express_api.config.IntakeProperties;
import com.homeexpress.home_express_api.dto.intake.IntakeParseTextResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class HeuristicParsingEngine {

    private final IntakeProperties intakeProperties;

    private static final Pattern QTY_PREFIX = Pattern.compile("^\\s*(\\d{1,3})\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern INCH = Pattern.compile("(\\d{2,3})\\s*inch", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIM_3D = Pattern.compile("(\\d+)\\s*[x*]\\s*(\\d+)\\s*(?:[x*]\\s*(\\d+))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern WEIGHT_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(kg|cân|kgs)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIM_2D_PATTERN = Pattern.compile("(\\d+)[mM](\\d+)|(\\d+(?:\\.\\d+)?)\\s*[mM]|(\\d+)\\s*cm", Pattern.CASE_INSENSITIVE);

    public List<IntakeParseTextResponse.ParsedItem> parse(List<String> lines) {
        List<IntakeParseTextResponse.ParsedItem> out = new ArrayList<>(lines.size());
        for (String s : lines) {
            ParsedHeu h = heuristic(s);
            out.add(IntakeParseTextResponse.ParsedItem.builder()
                    .name(capitalizeName(h.name))
                    .brand(h.brand)
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

    private ParsedHeu heuristic(String raw) {
        String s = stripContextPrefix(raw.trim());
        int qty = 1;
        double weight = 0;

        Matcher m = QTY_PREFIX.matcher(s);
        if (m.find()) {
            try {
                qty = Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignore) {}
            s = m.group(2).trim();
        }

        Matcher mw = WEIGHT_PATTERN.matcher(s);
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
            Matcher md = DIM_2D_PATTERN.matcher(s);
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
    
    // === Detect Functions using Regex ===

    public String detectCategory(String n) {
        for (Map.Entry<String, Pattern> entry : intakeProperties.getCategoryPatterns().entrySet()) {
            if (entry.getValue().matcher(n).find()) {
                return entry.getKey();
            }
        }
        return "Khác";
    }

    public String detectBrand(String n) {
        Pattern p = intakeProperties.getBrandPattern();
        if (p != null) {
             Matcher m = p.matcher(n);
             if (m.find()) {
                 return capitalizeWord(m.group());
             }
        }
        return null;
    }

    public boolean isFragile(String n) {
        if (intakeProperties.getFragilePattern() != null && intakeProperties.getFragilePattern().matcher(n).find()) {
            return true;
        }
        // Fallback logic can be kept here if needed, or fully moved to regex in config
        return containsAny(n, "pc", "man hinh", "tivi", "laptop", "tablet", "loa", "amply", "tu lanh", "tu mat", "tu dong", "bep tu", "lo vi song");
    }

    public boolean requiresDisassembly(String n, double w) {
        if (intakeProperties.getDisassemblyPattern() != null && intakeProperties.getDisassemblyPattern().matcher(n).find()) {
            return true;
        }
        
        // Logic complex vẫn giữ code
        if (w > 0 && w < 50 && !n.contains("giuong")) return false;
        if (containsAny(n, "giuong", "tu quan ao", "tu ao", "ban hop", "ban giam doc", "sofa goc", "sofa l", "tu ho so", "tu tai lieu")) return true;
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

    public String detectSize(String n, double w) {
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
        return "M";
    }

    // === Utility Functions ===

    private static boolean containsAny(String s, String... kws) {
        for (String k : kws) {
            if (s.contains(k)) return true;
        }
        return false;
    }

    private String stripContextPrefix(String s) {
        String regex = "^(?i)(chuyển\\s+(?:văn\\s+phòng|nhà|kho|trọn\\s+gói)|danh\\s+sách|gói|lô|đơn|stt|mục|phòng|chuyen\\s+(?:van\\s+phong|nha|kho|tron\\s+goi))(?:[^:]*)?:\\s*";
        return s.replaceFirst(regex, "");
    }

    private static String stripLeadingArticles(String s) {
        return s.replaceFirst("^(bộ|cái|chiếc)\\s+", "").trim();
    }

    public static String removeAccents(String input) {
        if (input == null) return "";
        String nfd = java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFD);
        return nfd.replaceAll("\\p{InCombiningDiacriticalMarks}+", "").replace("đ", "d").replace("Đ", "D");
    }

    public static String capitalizeName(String s) {
        if (s == null || s.isBlank()) return s;
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }
    
    public static String capitalizeWord(String s) {
        if (s == null || s.isBlank()) return s;
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1).toLowerCase(Locale.ROOT);
    }

    private record ParsedHeu(String name, String brand, int quantity, String category, String size, boolean fragile, boolean disassembly, double weightKg, double widthCm, double heightCm, double depthCm) {
    }
}
