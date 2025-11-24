package com.homeexpress.home_express_api.service.intake;

import com.homeexpress.home_express_api.dto.intake.IntakeParseTextResponse;
import com.homeexpress.home_express_api.dto.intake.OpenAIIntakeDTOs.ParsedItemRaw;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service chuyên trách việc phân tích danh sách đồ đạc (Intake Parsing).
 * Nhiệm vụ: Orchestrator điều phối giữa AI Client, Heuristic Engine và Refinement Service.
 */
@Slf4j
@Service
public class IntakeAIParsingService {

    private final OpenAIClient openAIClient;
    private final HeuristicParsingEngine heuristicEngine;
    private final IntakeRefinementService refinementService;
    
    // Metrics
    private final Timer parsingTimer;
    private final Counter successCounter;
    private final Counter failureCounter;
    private final Counter fallbackCounter;

    public IntakeAIParsingService(OpenAIClient openAIClient,
                                  HeuristicParsingEngine heuristicEngine,
                                  IntakeRefinementService refinementService,
                                  MeterRegistry meterRegistry) {
        this.openAIClient = openAIClient;
        this.heuristicEngine = heuristicEngine;
        this.refinementService = refinementService;
        
        // Initialize Metrics
        this.parsingTimer = meterRegistry.timer("intake.parsing.latency");
        this.successCounter = meterRegistry.counter("intake.parsing.ai.success");
        this.failureCounter = meterRegistry.counter("intake.parsing.ai.failure");
        this.fallbackCounter = meterRegistry.counter("intake.parsing.fallback.count");
    }

    /**
     * Hàm xử lý chính: Nhận văn bản -> Trả về danh sách đồ.
     * Quy trình: Thử dùng AI trước, nếu không được thì dùng logic thủ công.
     */
    @Cacheable(value = "intake-parsing", key = "#text.trim().toLowerCase().hashCode()")
    @Retry(name = "intake-ai", fallbackMethod = "fallbackWithHeuristic")
    @CircuitBreaker(name = "intake-ai")
    public List<IntakeParseTextResponse.ParsedItem> parseWithAI(String text) {
        return parsingTimer.record(() -> {
            if (!StringUtils.hasText(text)) {
                return List.of();
            }

            List<String> lines = preprocessText(text);
            if (lines.isEmpty()) {
                return List.of();
            }

            // Nếu không cấu hình API Key thì chuyển ngay sang xử lý thủ công
            if (!openAIClient.isConfigured()) {
                log.warn("Chưa cấu hình OpenAI Key. Chuyển sang chế độ xử lý thủ công.");
                return fallbackInternal(lines);
            }

            try {
                String content = openAIClient.fetchAnalysis(lines);
                List<ParsedItemRaw> raw = openAIClient.parseRawJson(content);

                if (raw == null || raw.isEmpty()) {
                    throw new RuntimeException("AI trả về kết quả rỗng");
                }

                log.info("AI xử lý xong: {} dòng đầu vào -> {} món đồ.", lines.size(), raw.size());
                successCounter.increment();

                return raw.stream()
                        .map(refinementService::toDomainAndRefine)
                        .collect(Collectors.toList());

            } catch (RuntimeException e) {
                failureCounter.increment();
                // Giữ nguyên loại RuntimeException để Retry/CircuitBreaker nhận diện đúng
                throw e;
            } catch (Exception e) {
                failureCounter.increment();
                // N��m ngo��?i l��� �`��� kích ho���t Retry/CircuitBreaker
                throw new RuntimeException(e);
            }
        });
    }

    // Hàm dự phòng, chạy khi AI thất bại hoặc Circuit Breaker mở
    public List<IntakeParseTextResponse.ParsedItem> fallbackWithHeuristic(String text, Throwable t) {
        log.error("AI thất bại hoặc Circuit Breaker mở. Chuyển sang chế độ dự phòng. Lỗi: {}", t.getMessage());
        fallbackCounter.increment();
        List<String> lines = preprocessText(text);
        return fallbackInternal(lines);
    }

    private List<IntakeParseTextResponse.ParsedItem> fallbackInternal(List<String> lines) {
        return heuristicEngine.parse(lines);
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
}
