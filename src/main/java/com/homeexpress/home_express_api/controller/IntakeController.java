package com.homeexpress.home_express_api.controller;

import com.homeexpress.home_express_api.dto.intake.IntakeMergeRequest;
import com.homeexpress.home_express_api.dto.intake.IntakeMergeResponse;
import com.homeexpress.home_express_api.dto.intake.IntakeParseTextRequest;
import com.homeexpress.home_express_api.dto.intake.IntakeParseTextResponse;
import com.homeexpress.home_express_api.dto.intake.ItemCandidateDto;
import com.homeexpress.home_express_api.service.intake.IntakeSessionService;
import com.homeexpress.home_express_api.service.intake.IntakeTextParsingService;
import com.homeexpress.home_express_api.service.intake.IntakeAIParsingService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Controller for handling item intake operations
 */
@RestController
@RequestMapping("/api/v1/intake")
@RequiredArgsConstructor
public class IntakeController {

    private static final Logger logger = LoggerFactory.getLogger(IntakeController.class);

    private final IntakeTextParsingService textParsingService;
    private final IntakeAIParsingService aiParsingService;
    private final IntakeSessionService sessionService;

    private static final List<DocumentItemTemplate> DOCUMENT_TEMPLATES = List.of(
        new DocumentItemTemplate("sofa", "Sofa", "Living Room Furniture", 0.82, 1),
        new DocumentItemTemplate("bed", "Queen Bed", "Bedroom Furniture", 0.8, 1),
        new DocumentItemTemplate("table", "Dining Table", "Dining Furniture", 0.78, 1),
        new DocumentItemTemplate("chair", "Dining Chair", "Dining Furniture", 0.72, 4),
        new DocumentItemTemplate("dresser", "Dresser", "Bedroom Storage", 0.7, 1),
        new DocumentItemTemplate("box", "Moving Boxes", "Packing Supplies", 0.65, 10),
        new DocumentItemTemplate("tv", "Television", "Electronics", 0.68, 1)
    );

    /**
     * Merge item candidates into an intake session
     * 
     * @param sessionId The session ID (timestamp from frontend)
     * @param request The intake merge request containing item candidates
     * @return Response with session info
     */
    @PostMapping("/merge")
    public ResponseEntity<?> mergeItems(
            @RequestParam String sessionId,
            @Valid @RequestBody IntakeMergeRequest request) {
        
        try {
            logger.info("Merging {} items into session {}", 
                request.getCandidates().size(), sessionId);
            
            sessionService.createOrGetSession(sessionId, null);
            sessionService.saveItems(sessionId, request.getCandidates(), null, null);
            
            IntakeMergeResponse response = IntakeMergeResponse.builder()
                .sessionId(sessionId)
                .itemCount(request.getCandidates().size())
                .message("Items saved successfully to database")
                .build();
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Error merging items for session {}: {}", sessionId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to save items: " + e.getMessage()));
        }
    }

    /**
     * Parse free-form text into potential item candidates.
     *
     * @param request Body containing the text to parse
     * @return Structured candidates detected in the text
     */
    @PostMapping("/parse-text")
    public ResponseEntity<?> parseText(@Valid @RequestBody IntakeParseTextRequest request) {
        try {
            // Try AI parsing first
            List<IntakeParseTextResponse.ParsedItem> aiCandidates = aiParsingService.parseWithAI(request.getText());
            
            if (!aiCandidates.isEmpty()) {
                IntakeParseTextResponse response = IntakeParseTextResponse.builder()
                    .success(true)
                    .data(IntakeParseTextResponse.ParseTextData.builder()
                        .candidates(aiCandidates)
                        .warnings(List.of())
                        .metadata(Map.of("parser", "ai", "items_detected", aiCandidates.size()))
                        .build())
                    .build();
                return ResponseEntity.ok(response);
            }
            
            // Fallback to heuristic parsing
            var result = textParsingService.parse(request.getText());
            IntakeParseTextResponse response = IntakeParseTextResponse.builder()
                .success(true)
                .data(IntakeParseTextResponse.ParseTextData.builder()
                    .candidates(result.getCandidates())
                    .warnings(result.getWarnings())
                    .metadata(Map.of("parser", "heuristic", "items_detected", result.getCandidates().size()))
                    .build())
                .build();

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error parsing intake text: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to parse text: " + e.getMessage()));
        }
    }
    
    /**
     * Parse structured documents (PDF, DOCX, XLSX) and return inferred item candidates.
     *
     * @param document Multipart file containing the document to parse
     * @return Candidates extracted from the document content or filename
     */
    @PostMapping(value = "/parse-document", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> parseDocument(@RequestParam("document") MultipartFile document) {
        if (document == null || document.isEmpty()) {
            return ResponseEntity.badRequest()
                .body(Map.of(
                    "success", false,
                    "message", "A document file is required"
                ));
        }

        long startTime = System.currentTimeMillis();

        try {
            logger.info("Parsing intake document {} ({} bytes)", document.getOriginalFilename(), document.getSize());
            DocumentParseResult parseResult = parseDocumentCandidates(document);
            List<ItemCandidateDto> candidates = parseResult.getCandidates();
            boolean fallbackUsed = parseResult.isFallbackUsed();

            double averageConfidence = candidates.stream()
                .map(ItemCandidateDto::getConfidence)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);

            // Convert ItemCandidateDto to ParsedItem for response compatibility
            List<IntakeParseTextResponse.ParsedItem> responseCandidates = candidates.stream()
                .map(c -> IntakeParseTextResponse.ParsedItem.builder()
                    .name(c.getName())
                    .quantity(c.getQuantity())
                    .categoryName(c.getCategoryName())
                    .confidence(c.getConfidence())
                    .reasoning(c.getNotes())
                    .isFragile(c.getIsFragile())
                    .requiresDisassembly(c.getRequiresDisassembly())
                    .build())
                .toList();

            IntakeParseTextResponse response = IntakeParseTextResponse.builder()
                .success(true)
                .data(IntakeParseTextResponse.ParseTextData.builder()
                    .candidates(responseCandidates)
                    .warnings(fallbackUsed ? List.of("Low confidence parsing") : List.of())
                    .metadata(Map.of(
                        "serviceUsed", "DOCUMENT_KEYWORD_STUB",
                        "confidence", averageConfidence,
                        "processingTimeMs", System.currentTimeMillis() - startTime,
                        "detectedItemCount", candidates.size()
                    ))
                    .build())
                .build();

            return ResponseEntity.ok(response);
        } catch (Exception ex) {
            logger.error("Failed to parse intake document {}: {}", document.getOriginalFilename(), ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                    "success", false,
                    "message", "Failed to parse document: " + ex.getMessage()
                ));
        }
    }
    
    private DocumentParseResult parseDocumentCandidates(MultipartFile document) {
        String sourceFile = document.getOriginalFilename();
        String documentText = readDocumentAsText(document).toLowerCase(Locale.ENGLISH);

        StringBuilder searchSpaceBuilder = new StringBuilder();
        if (sourceFile != null) {
            searchSpaceBuilder.append(sourceFile.toLowerCase(Locale.ENGLISH)).append(' ');
        }
        searchSpaceBuilder.append(documentText);
        String searchSpace = searchSpaceBuilder.toString();

        List<ItemCandidateDto> candidates = new ArrayList<>();
        for (DocumentItemTemplate template : DOCUMENT_TEMPLATES) {
            if (searchSpace.contains(template.getKeyword())) {
                candidates.add(createCandidateFromTemplate(template, sourceFile));
            }
        }

        boolean fallbackUsed = candidates.isEmpty();
        if (fallbackUsed) {
            candidates.add(buildFallbackCandidate(sourceFile));
        }

        return new DocumentParseResult(candidates, fallbackUsed);
    }

    private ItemCandidateDto createCandidateFromTemplate(DocumentItemTemplate template, String sourceFile) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("matchedKeyword", template.getKeyword());
        if (sourceFile != null && !sourceFile.isBlank()) {
            metadata.put("sourceFile", sourceFile);
        }

        return ItemCandidateDto.builder()
            .id(UUID.randomUUID().toString())
            .name(template.getName())
            .categoryName(template.getCategory())
            .quantity(template.getDefaultQuantity())
            .source("document")
            .confidence(template.getConfidence())
            .notes("Parsed from document keyword: " + template.getKeyword())
            .metadata(metadata)
            .build();
    }

    private ItemCandidateDto buildFallbackCandidate(String sourceFile) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("fallbackReason", "No matching keywords found");
        if (sourceFile != null && !sourceFile.isBlank()) {
            metadata.put("sourceFile", sourceFile);
        }

        return ItemCandidateDto.builder()
            .id(UUID.randomUUID().toString())
            .name("Document Review Needed")
            .categoryName("Unclassified")
            .quantity(1)
            .source("document")
            .confidence(0.4)
            .notes("Document parsed but no known keywords matched. Please review manually.")
            .metadata(metadata)
            .build();
    }

    private String readDocumentAsText(MultipartFile document) {
        try {
            byte[] bytes = document.getBytes();
            if (bytes.length == 0) {
                return "";
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            logger.warn("Unable to read document {} contents: {}", document.getOriginalFilename(), ex.getMessage());
            return "";
        }
    }
    
    /**
     * Get items from an intake session
     * 
     * @param sessionId The session ID
     * @return The stored items
     */
    @GetMapping("/session/{sessionId}")
    public ResponseEntity<?> getSessionItems(@PathVariable String sessionId) {
        
        var sessionOpt = sessionService.getSession(sessionId);
        
        if (sessionOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Session not found"));
        }
        
        var items = sessionService.getSessionItems(sessionId);
        
        return ResponseEntity.ok(Map.of(
            "sessionId", sessionId,
            "items", items,
            "count", items.size()
        ));
    }

    private static class DocumentParseResult {
        private final List<ItemCandidateDto> candidates;
        private final boolean fallbackUsed;

        DocumentParseResult(List<ItemCandidateDto> candidates, boolean fallbackUsed) {
            this.candidates = candidates;
            this.fallbackUsed = fallbackUsed;
        }

        List<ItemCandidateDto> getCandidates() {
            return candidates;
        }

        boolean isFallbackUsed() {
            return fallbackUsed;
        }
    }

    private static class DocumentItemTemplate {
        private final String keyword;
        private final String name;
        private final String category;
        private final double confidence;
        private final int defaultQuantity;

        DocumentItemTemplate(String keyword, String name, String category, double confidence, int defaultQuantity) {
            this.keyword = keyword;
            this.name = name;
            this.category = category;
            this.confidence = confidence;
            this.defaultQuantity = defaultQuantity;
        }

        String getKeyword() {
            return keyword;
        }

        String getName() {
            return name;
        }

        String getCategory() {
            return category;
        }

        double getConfidence() {
            return confidence;
        }

        int getDefaultQuantity() {
            return defaultQuantity;
        }
    }
}
