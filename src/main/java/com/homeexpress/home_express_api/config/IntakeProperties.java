package com.homeexpress.home_express_api.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Data
@Configuration
@ConfigurationProperties(prefix = "app.intake.parsing")
public class IntakeProperties {
    
    private Map<String, String> dictionary = new HashMap<>();
    private Set<String> brands = new HashSet<>();
    private Map<String, Set<String>> categoryKeywords = new LinkedHashMap<>();
    private Set<String> fragileKeywords = new HashSet<>();
    private Set<String> disassemblyKeywords = new HashSet<>();

    // Compiled Patterns (Non-configured properties, initialized post-construct)
    private Map<String, Pattern> categoryPatterns = new LinkedHashMap<>();
    private Pattern fragilePattern;
    private Pattern disassemblyPattern;
    private Pattern brandPattern;

    @PostConstruct
    public void initPatterns() {
        // Compile Category Patterns
        categoryKeywords.forEach((category, keywords) -> {
            categoryPatterns.put(category, compileRegex(keywords));
        });

        // Compile Fragile Pattern
        fragilePattern = compileRegex(fragileKeywords);

        // Compile Disassembly Pattern
        disassemblyPattern = compileRegex(disassemblyKeywords);

        // Compile Brand Pattern
        brandPattern = compileRegex(brands);
    }

    private Pattern compileRegex(Set<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return Pattern.compile("a^"); // Matches nothing
        }
        // Tạo regex: (?U)\b(kw1|kw2|kw3)\b
        // (?U) bật chế độ Unicode character class để \b hiểu được ký tự tiếng Việt
        String regex = keywords.stream()
                .map(Pattern::quote)
                .collect(Collectors.joining("|", "(?U)\\b(", ")\\b"));
        
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    public IntakeProperties() {
        // Default values... (Giữ nguyên phần khởi tạo cũ)
        
        // 1. Vietnamese Dictionary
        dictionary.put("tu lanh", "Tủ lạnh");
        dictionary.put("may giat", "Máy giặt");
        dictionary.put("may say", "Máy sấy");
        dictionary.put("ti vi", "Tivi");
        dictionary.put("tv", "Tivi");
        dictionary.put("dieu hoa", "Điều hòa");
        dictionary.put("may lanh", "Máy lạnh");
        dictionary.put("quat", "Quạt");
        dictionary.put("noi com", "Nồi cơm điện");
        dictionary.put("bep tu", "Bếp từ");
        dictionary.put("lo vi song", "Lò vi sóng");
        dictionary.put("may loc nuoc", "Máy lọc nước");
        dictionary.put("bo ban ghe", "Bộ bàn ghế");
        dictionary.put("ban an", "Bàn ăn");
        dictionary.put("ban lam viec", "Bàn làm việc");
        dictionary.put("ban hoc", "Bàn học");
        dictionary.put("ghe xoay", "Ghế xoay");
        dictionary.put("sofa", "Sofa");
        dictionary.put("giuong", "Giường ngủ");
        dictionary.put("tu quan ao", "Tủ quần áo");
        dictionary.put("ke sach", "Kệ sách");
        dictionary.put("ban trang diem", "Bàn trang điểm");
        dictionary.put("ket bia", "Két bia");
        dictionary.put("thung carton", "Thùng carton");
        dictionary.put("bo am chen", "Bộ ấm chén");
        dictionary.put("bat dia", "Bát đĩa");
        dictionary.put("ly coc", "Ly cốc");
        dictionary.put("tranh anh", "Tranh ảnh");
        dictionary.put("guong", "Gương");

        // 2. Brands
        brands.addAll(Set.of("samsung", "lg", "ikea", "sony", "xiaomi", "panasonic", "tcl", "sharp", "toshiba", "electrolux", "bosch", "whirlpool", "casper", "hitachi", "funiki", "daikin", "mitsubishi"));

        // 3. Category Keywords
        categoryKeywords.put("Hàng nặng & Đặc biệt", Set.of("ket sat", "piano", "dan organ", "dan guitar", "be ca", "ho ca", "may chay bo", "ghe massage", "gian ta", "xe may", "xe dap", "xe dien", "tuong", "hon non bo", "cay canh", "chau cay", "may phat dien"));
        categoryKeywords.put("Điện lạnh", Set.of("tu lanh", "tu dong", "tu mat", "may giat", "may say", "dieu hoa", "may lanh", "cuc nong", "cuc lanh", "binh nong lanh", "may nuoc nong", "quat", "hut mui", "may rua bat", "may loc nuoc", "may hut am", "may loc khong khi", "cay nuoc", "cay nong lanh"));
        categoryKeywords.put("Thiết bị điện tử", Set.of("tivi", "tv", "man hinh", "monitor", "loa", "amply", "dan am thanh", "sub", "mic", "karaoke", "camera", "pc", "computer", "may tinh", "laptop", "macbook", "may in", "may photo", "may scan", "may huy", "tablet", "ipad", "wifi", "modem", "router", "may chieu", "ps4", "ps5", "xbox", "ban phim", "chuot"));
        categoryKeywords.put("Nội thất", Set.of("ban", "ghe", "sofa", "salon", "divan", "giuong", "phan", "sap", "tu", "ke", "gia", "vo", "man rem", "rem cua", "tham", "vach ngan", "ban tho"));
        categoryKeywords.put("Đồ bếp & Dễ vỡ", Set.of("xoong", "noi", "chao", "bat", "dia", "chen", "ly", "coc", "tach", "binh", "lo", "am", "gio", "sot", "mam", "khay", "dua", "thia", "muong", "dao", "thot", "bep tu", "bep hong ngoai", "bep ga", "lo vi song", "lo nuong", "noi com", "noi chien", "may xay", "may ep", "may lam sua", "ban la", "ban ui", "guong", "kinh", "tranh", "anh", "den", "lo hoa", "binh hoa", "my pham", "nuoc hoa", "thuy tinh", "pha le", "gom", "su", "moc", "cay lau nha", "choi", "hot rac", "xo", "chau", "thau", "gia dung"));
        categoryKeywords.put("Chăn ga gối đệm", Set.of("chan", "ga", "goi", "dem", "nem", "man", "mung", "drap", "chieu truc", "chieu coi"));
        categoryKeywords.put("Quần áo & Phụ kiện", Set.of("quan", "ao", "vay", "dam", "giay", "dep", "tui xach", "vali", "mu", "non la", "non bao hiem", "khan", "tat", "vo", "ca vat", "that lung", "dong ho", "trang suc", "thoi trang"));
        categoryKeywords.put("Sách vở & Tài liệu", Set.of("sach", "vo", "tai lieu", "ho so", "giay", "truyen", "tap chi", "van phong pham", "but", "cap sach", "balo"));
        categoryKeywords.put("Khác", Set.of("thung", "hop", "carton", "xop", "bang dinh", "mang boc", "bao tai"));

        // 4. Fragile Keywords
        fragileKeywords.addAll(Set.of("de vo", "fragile", "can than", "kinh", "guong", "gom su", "do su", "bat dia", "chen", "ly", "coc", "am chen", "am tra", "am sieu toc", "den chum", "den ngu", "den ban", "tranh anh", "khung anh", "be ca", "lo hoa", "binh hoa"));
    }
}
