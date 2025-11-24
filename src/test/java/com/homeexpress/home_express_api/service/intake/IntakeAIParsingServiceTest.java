package com.homeexpress.home_express_api.service.intake;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.homeexpress.home_express_api.dto.intake.IntakeParseTextResponse;
import com.homeexpress.home_express_api.dto.intake.OpenAIIntakeDTOs.ParsedItemRaw;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
class IntakeAIParsingServiceTest {

    @Mock
    private OpenAIClient openAIClient;

    @Mock
    private HeuristicParsingEngine heuristicEngine;

    @Mock
    private IntakeRefinementService refinementService;

    private IntakeAIParsingService service;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new IntakeAIParsingService(openAIClient, heuristicEngine, refinementService, meterRegistry);
    }

    @Test
    void parseWithAI_Success() throws Exception {
        // Arrange
        String inputText = "1 sofa";
        String aiResponse = "json_content";
        ParsedItemRaw rawItem = new ParsedItemRaw(
            "sofa", null, null, 1, "Nội thất", "L", false, false, 0.9, "test reasoning", 
            20.0, 200.0, 100.0, 80.0
        );
        
        IntakeParseTextResponse.ParsedItem refinedItem = new IntakeParseTextResponse.ParsedItem();
        refinedItem.setName("Sofa Refined");

        when(openAIClient.isConfigured()).thenReturn(true);
        when(openAIClient.fetchAnalysis(anyList())).thenReturn(aiResponse);
        when(openAIClient.parseRawJson(aiResponse)).thenReturn(List.of(rawItem));
        when(refinementService.toDomainAndRefine(rawItem)).thenReturn(refinedItem);

        // Act
        List<IntakeParseTextResponse.ParsedItem> result = service.parseWithAI(inputText);

        // Assert
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("Sofa Refined", result.get(0).getName());
        verify(openAIClient).fetchAnalysis(anyList());
        verify(heuristicEngine, never()).parse(anyList());
    }

    @Test
    void parseWithAI_NoApiKey_ShouldFallback() throws Exception {
        // Arrange
        String inputText = "1 sofa";
        IntakeParseTextResponse.ParsedItem fallbackItem = new IntakeParseTextResponse.ParsedItem();
        fallbackItem.setName("Sofa Fallback");

        when(openAIClient.isConfigured()).thenReturn(false);
        when(heuristicEngine.parse(anyList())).thenReturn(List.of(fallbackItem));

        // Act
        List<IntakeParseTextResponse.ParsedItem> result = service.parseWithAI(inputText);

        // Assert
        assertEquals(1, result.size());
        assertEquals("Sofa Fallback", result.get(0).getName());
        verify(heuristicEngine).parse(anyList());
    }

    @Test
    void fallbackWithHeuristic_ShouldCallEngine() {
        // Arrange
        String inputText = "1 table";
        RuntimeException ex = new RuntimeException("AI Error");
        
        IntakeParseTextResponse.ParsedItem item = new IntakeParseTextResponse.ParsedItem();
        item.setName("Table");

        when(heuristicEngine.parse(anyList())).thenReturn(List.of(item));

        // Act
        List<IntakeParseTextResponse.ParsedItem> result = service.fallbackWithHeuristic(inputText, ex);

        // Assert
        assertEquals(1, result.size());
        assertEquals("Table", result.get(0).getName());
    }
    
    @Test
    void parseWithAI_EmptyInput_ReturnsEmpty() {
        assertTrue(service.parseWithAI("").isEmpty());
        assertTrue(service.parseWithAI("   ").isEmpty());
    }

    @Test
    void fallbackWithHeuristic_WhenJsonParsingFails_ShouldReturnHeuristic() {
        // Arrange
        String inputText = "1 sofa";
        RuntimeException parsingError = new RuntimeException("AI JSON parse error");

        IntakeParseTextResponse.ParsedItem fallbackItem = new IntakeParseTextResponse.ParsedItem();
        fallbackItem.setName("Sofa Heuristic");

        when(heuristicEngine.parse(anyList())).thenReturn(List.of(fallbackItem));

        // Act
        List<IntakeParseTextResponse.ParsedItem> result = service.fallbackWithHeuristic(inputText, parsingError);

        // Assert
        assertEquals(1, result.size());
        assertEquals("Sofa Heuristic", result.get(0).getName());
        verify(heuristicEngine).parse(anyList());
    }

    @Test
    void fallbackWithHeuristic_WhenTimeout_ShouldReturnHeuristic() {
        // Arrange
        String inputText = "1 fridge";
        RuntimeException timeout = new org.springframework.web.client.ResourceAccessException("Read timed out");

        IntakeParseTextResponse.ParsedItem fallbackItem = new IntakeParseTextResponse.ParsedItem();
        fallbackItem.setName("Fridge Heuristic");

        when(heuristicEngine.parse(anyList())).thenReturn(List.of(fallbackItem));

        // Act
        List<IntakeParseTextResponse.ParsedItem> result = service.fallbackWithHeuristic(inputText, timeout);

        // Assert
        assertEquals(1, result.size());
        assertEquals("Fridge Heuristic", result.get(0).getName());
        verify(heuristicEngine).parse(anyList());
    }
}
