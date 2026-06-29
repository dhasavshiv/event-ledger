package com.eventledger.gateway.controller;

import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.dto.EventRequest;
import com.eventledger.gateway.dto.EventResponse;
import com.eventledger.gateway.repository.EventRepository;
import com.eventledger.gateway.service.EventService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
public class EventController {

    private static final Logger log = LoggerFactory.getLogger(EventController.class);
    private final EventService eventService;
    private final AccountServiceClient accountServiceClient;
    private final EventRepository eventRepository;

    public EventController(EventService eventService, AccountServiceClient accountServiceClient, EventRepository eventRepository) {
        this.eventService = eventService;
        this.accountServiceClient = accountServiceClient;
        this.eventRepository = eventRepository;
    }

    @PostMapping("/events")
    public ResponseEntity<?> submitEvent(@Valid @RequestBody EventRequest request,
                                          @RequestHeader(value = "X-Trace-Id", required = false) String incomingTraceId) {
        String traceId = incomingTraceId != null ? incomingTraceId : UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        try {
            EventResponse response = eventService.submitEvent(request);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .header("X-Trace-Id", traceId)
                    .body(response);
        } catch (EventService.DuplicateEventException e) {
            // Duplicate: return original event with 200 (not 201 — nothing was created)
            return ResponseEntity.ok()
                    .header("X-Trace-Id", traceId)
                    .body(e.getExisting());
        } finally {
            MDC.clear();
        }
    }

    @GetMapping("/events/{id}")
    public ResponseEntity<?> getEvent(@PathVariable String id,
                                       @RequestHeader(value = "X-Trace-Id", required = false) String incomingTraceId) {
        String traceId = incomingTraceId != null ? incomingTraceId : UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        try {
            EventResponse response = eventService.getEvent(id);
            return ResponseEntity.ok()
                    .header("X-Trace-Id", traceId)
                    .body(response);
        } finally {
            MDC.clear();
        }
    }

    @GetMapping("/events")
    public ResponseEntity<?> getEventsByAccount(@RequestParam String account,
                                                 @RequestHeader(value = "X-Trace-Id", required = false) String incomingTraceId) {
        String traceId = incomingTraceId != null ? incomingTraceId : UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        try {
            List<EventResponse> events = eventService.getEventsByAccount(account);
            return ResponseEntity.ok()
                    .header("X-Trace-Id", traceId)
                    .body(events);
        } finally {
            MDC.clear();
        }
    }

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        try {
            eventRepository.count();
            return ResponseEntity.ok(Map.of(
                    "status", "UP",
                    "service", "event-gateway",
                    "database", "UP"
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "status", "DOWN",
                    "service", "event-gateway",
                    "database", "DOWN"
            ));
        }
    }

    @GetMapping("/accounts/{accountId}/balance")
    public ResponseEntity<?> getBalance(@PathVariable String accountId,
                                         @RequestHeader(value = "X-Trace-Id", required = false) String incomingTraceId) {
        String traceId = incomingTraceId != null ? incomingTraceId : UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        try {
            BigDecimal balance = accountServiceClient.getBalance(accountId);
            return ResponseEntity.ok()
                    .header("X-Trace-Id", traceId)
                    .body(Map.of("accountId", accountId, "balance", balance));
        } catch (AccountServiceClient.AccountServiceUnavailableException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("X-Trace-Id", traceId)
                    .body(Map.of("error", "Account service is unreachable. Balance unavailable."));
        } catch (AccountServiceClient.AccountServiceException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .header("X-Trace-Id", traceId)
                    .body(Map.of("error", e.getMessage()));
        } finally {
            MDC.clear();
        }
    }

    @GetMapping("/metrics")
    public ResponseEntity<?> metrics() {
        return ResponseEntity.ok(eventService.getMetrics());
    }

}
