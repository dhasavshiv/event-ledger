package com.eventledger.gateway;

import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.dto.EventRequest;
import com.eventledger.gateway.dto.EventResponse;
import com.eventledger.gateway.repository.EventRepository;
import com.eventledger.gateway.service.EventService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class EventGatewayIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EventRepository eventRepository;

    @MockBean
    private AccountServiceClient accountServiceClient;

    @BeforeEach
    void setUp() {
        eventRepository.deleteAll();
        // Default: account service succeeds
        doNothing().when(accountServiceClient).applyTransaction(
                anyString(), anyString(), anyString(), any(), anyString(), any());
    }

    private EventRequest buildRequest(String eventId, String accountId, String type, double amount) {
        EventRequest req = new EventRequest();
        req.setEventId(eventId);
        req.setAccountId(accountId);
        req.setType(type);
        req.setAmount(BigDecimal.valueOf(amount));
        req.setCurrency("USD");
        req.setEventTimestamp(Instant.now());
        return req;
    }

    // --- Core Functionality Tests ---

    @Test
    void testSubmitEvent_Success() throws Exception {
        EventRequest req = buildRequest("evt-001", "acct-123", "CREDIT", 150.00);

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value("evt-001"))
                .andExpect(jsonPath("$.accountId").value("acct-123"))
                .andExpect(jsonPath("$.type").value("CREDIT"));
    }

    @Test
    void testIdempotency_DuplicateEventReturnsOriginal() throws Exception {
        EventRequest req = buildRequest("evt-dup-001", "acct-123", "CREDIT", 100.00);
        String json = objectMapper.writeValueAsString(req);

        // First submission
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated());

        // Second submission - same eventId, must return 201 with same data
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value("evt-dup-001"));

        // Account service must only have been called ONCE
        verify(accountServiceClient, times(1)).applyTransaction(
                anyString(), eq("evt-dup-001"), anyString(), any(), anyString(), any());
    }

    @Test
    void testOutOfOrderEvents_ListOrderedByEventTimestamp() throws Exception {
        // Submit event with LATER timestamp first
        EventRequest later = buildRequest("evt-later", "acct-order", "CREDIT", 50.00);
        later.setEventTimestamp(Instant.parse("2026-05-15T15:00:00Z"));

        EventRequest earlier = buildRequest("evt-earlier", "acct-order", "CREDIT", 30.00);
        earlier.setEventTimestamp(Instant.parse("2026-05-15T10:00:00Z"));

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(later)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(earlier)))
                .andExpect(status().isCreated());

        // GET events - must be in chronological order
        mockMvc.perform(get("/events").param("account", "acct-order"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventId").value("evt-earlier"))
                .andExpect(jsonPath("$[1].eventId").value("evt-later"));
    }

    @Test
    void testValidation_MissingRequiredFields() throws Exception {
        // Missing eventId and type
        String badJson = "{\"accountId\":\"acct-123\",\"amount\":100,\"currency\":\"USD\",\"eventTimestamp\":\"2026-05-15T10:00:00Z\"}";

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"));
    }

    @Test
    void testValidation_NegativeAmount() throws Exception {
        EventRequest req = buildRequest("evt-neg", "acct-123", "CREDIT", -50.00);
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testValidation_InvalidType() throws Exception {
        EventRequest req = buildRequest("evt-bad-type", "acct-123", "TRANSFER", 50.00);
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testGetEvent_NotFound() throws Exception {
        mockMvc.perform(get("/events/nonexistent-id"))
                .andExpect(status().isNotFound());
    }

    // --- Resiliency Tests ---

    @Test
    void testCircuitBreaker_AccountServiceDown_Returns503() throws Exception {
        // Simulate account service failure
        doThrow(new AccountServiceClient.AccountServiceUnavailableException("Circuit breaker open"))
                .when(accountServiceClient).applyTransaction(
                        anyString(), anyString(), anyString(), any(), anyString(), any());

        EventRequest req = buildRequest("evt-cb-001", "acct-cb", "CREDIT", 100.00);

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("Account service unavailable"));
    }

    @Test
    void testGracefulDegradation_GetEvents_WorksWhenAccountServiceDown() throws Exception {
        // First save an event while account service is up
        EventRequest req = buildRequest("evt-degrade-001", "acct-degrade", "CREDIT", 100.00);
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        // Now GET works even if account service would be down (it's not called for GETs)
        mockMvc.perform(get("/events/evt-degrade-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt-degrade-001"));

        mockMvc.perform(get("/events").param("account", "acct-degrade"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventId").value("evt-degrade-001"));
    }

    // --- Trace Propagation Tests ---

    @Test
    void testTracePropagation_TraceIdInResponse() throws Exception {
        EventRequest req = buildRequest("evt-trace-001", "acct-trace", "CREDIT", 100.00);
        String customTraceId = "test-trace-id-12345";

        MvcResult result = mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Trace-Id", customTraceId)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        // Trace ID must be echoed back in response header
        String returnedTraceId = result.getResponse().getHeader("X-Trace-Id");
        assertEquals(customTraceId, returnedTraceId);

        // Verify trace ID was propagated to account service call
        verify(accountServiceClient).applyTransaction(
                eq("acct-trace"), eq("evt-trace-001"), anyString(), any(), anyString(), any());
    }

    @Test
    void testTracePropagation_AutoGeneratedWhenNotProvided() throws Exception {
        EventRequest req = buildRequest("evt-trace-auto", "acct-trace", "CREDIT", 50.00);

        MvcResult result = mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        String traceId = result.getResponse().getHeader("X-Trace-Id");
        assertNotNull(traceId, "Trace ID should be auto-generated");
        assertFalse(traceId.isBlank());
    }

    // --- Balance Proxy ---

    @Test
    void testGetBalance_Success() throws Exception {
        when(accountServiceClient.getBalance("acct-bal")).thenReturn(BigDecimal.valueOf(250.00));

        mockMvc.perform(get("/accounts/acct-bal/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("acct-bal"))
                .andExpect(jsonPath("$.balance").value(250.00));
    }

    @Test
    void testGetBalance_AccountServiceDown_Returns503() throws Exception {
        when(accountServiceClient.getBalance("acct-down"))
                .thenThrow(new AccountServiceClient.AccountServiceUnavailableException("Circuit breaker open"));

        mockMvc.perform(get("/accounts/acct-down/balance"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("Account service is unreachable. Balance unavailable."));
    }

    // --- Health & Metrics ---

    @Test
    void testHealthEndpoint() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void testMetricsEndpoint() throws Exception {
        mockMvc.perform(get("/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_events_received").exists());
    }
}
