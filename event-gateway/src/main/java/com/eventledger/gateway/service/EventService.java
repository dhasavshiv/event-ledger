package com.eventledger.gateway.service;

import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.dto.EventRequest;
import com.eventledger.gateway.dto.EventResponse;
import com.eventledger.gateway.model.Event;
import com.eventledger.gateway.repository.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventRepository eventRepository;
    private final AccountServiceClient accountServiceClient;

    // Simple in-memory metrics
    private final AtomicLong totalEventsReceived = new AtomicLong(0);
    private final AtomicLong duplicateEventsReceived = new AtomicLong(0);
    private final AtomicLong accountServiceErrors = new AtomicLong(0);

    public EventService(EventRepository eventRepository, AccountServiceClient accountServiceClient) {
        this.eventRepository = eventRepository;
        this.accountServiceClient = accountServiceClient;
    }

    @Transactional
    public EventResponse submitEvent(EventRequest request) {
        String traceId = MDC.get("traceId");
        totalEventsReceived.incrementAndGet();

        log.info("Processing event: eventId={} accountId={} type={} amount={} traceId={}",
                request.getEventId(), request.getAccountId(), request.getType(),
                request.getAmount(), traceId);

        // Idempotency check
        Optional<Event> existing = eventRepository.findById(request.getEventId());
        if (existing.isPresent()) {
            duplicateEventsReceived.incrementAndGet();
            log.info("Duplicate event detected, returning original: eventId={}", request.getEventId());
            return toResponse(existing.get());
        }

        // Save event to gateway DB first
        Event event = Event.builder()
                .eventId(request.getEventId())
                .accountId(request.getAccountId())
                .type(request.getType())
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .eventTimestamp(request.getEventTimestamp())
                .receivedAt(Instant.now())
                .metadataSource(request.getMetadata() != null ? request.getMetadata().get("source") : null)
                .metadataBatchId(request.getMetadata() != null ? request.getMetadata().get("batchId") : null)
                .build();

        eventRepository.save(event);
        log.info("Event saved to gateway DB: eventId={}", request.getEventId());

        // Call account service to apply transaction
        try {
            accountServiceClient.applyTransaction(
                    request.getAccountId(),
                    request.getEventId(),
                    request.getType(),
                    request.getAmount(),
                    request.getCurrency(),
                    request.getEventTimestamp()
            );
        } catch (AccountServiceClient.AccountServiceUnavailableException e) {
            accountServiceErrors.incrementAndGet();
            throw e;
        } catch (AccountServiceClient.AccountServiceException e) {
            accountServiceErrors.incrementAndGet();
            throw e;
        }

        return toResponse(event);
    }

    public EventResponse getEvent(String eventId) {
        return eventRepository.findById(eventId)
                .map(this::toResponse)
                .orElseThrow(() -> new EventNotFoundException("Event not found: " + eventId));
    }

    public List<EventResponse> getEventsByAccount(String accountId) {
        List<Event> events = eventRepository.findByAccountIdOrderByEventTimestampAsc(accountId);
        log.info("Fetched {} events for accountId={}", events.size(), accountId);
        return events.stream().map(this::toResponse).collect(Collectors.toList());
    }

    public Map<String, Long> getMetrics() {
        Map<String, Long> metrics = new HashMap<>();
        metrics.put("total_events_received", totalEventsReceived.get());
        metrics.put("duplicate_events_received", duplicateEventsReceived.get());
        metrics.put("account_service_errors", accountServiceErrors.get());
        return metrics;
    }

    private EventResponse toResponse(Event event) {
        Map<String, String> metadata = new HashMap<>();
        if (event.getMetadataSource() != null) metadata.put("source", event.getMetadataSource());
        if (event.getMetadataBatchId() != null) metadata.put("batchId", event.getMetadataBatchId());

        return EventResponse.builder()
                .eventId(event.getEventId())
                .accountId(event.getAccountId())
                .type(event.getType())
                .amount(event.getAmount())
                .currency(event.getCurrency())
                .eventTimestamp(event.getEventTimestamp())
                .receivedAt(event.getReceivedAt())
                .metadata(metadata.isEmpty() ? null : metadata)
                .build();
    }

    public static class EventNotFoundException extends RuntimeException {
        public EventNotFoundException(String message) { super(message); }
    }
}
