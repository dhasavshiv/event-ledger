package com.eventledger.gateway.controller;

import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.dto.EventRequest;
import com.eventledger.gateway.dto.EventResponse;
import com.eventledger.gateway.service.EventService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
public class EventController {

    private static final Logger log = LoggerFactory.getLogger(EventController.class);
    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
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
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "event-gateway"
        ));
    }

    @GetMapping("/metrics")
    public ResponseEntity<?> metrics() {
        return ResponseEntity.ok(eventService.getMetrics());
    }

}
