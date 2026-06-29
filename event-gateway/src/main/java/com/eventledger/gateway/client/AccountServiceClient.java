package com.eventledger.gateway.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Component
public class AccountServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AccountServiceClient.class);
    private static final String TRACE_HEADER = "X-Trace-Id";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String accountServiceBaseUrl;

    public AccountServiceClient(
            @Value("${account-service.base-url}") String accountServiceBaseUrl,
            ObjectMapper objectMapper) {
        this.accountServiceBaseUrl = accountServiceBaseUrl;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    @CircuitBreaker(name = "accountService", fallbackMethod = "applyTransactionFallback")
    public void applyTransaction(String accountId, String eventId, String type,
                                  BigDecimal amount, String currency, Instant eventTimestamp) {
        String traceId = MDC.get("traceId");
        try {
            Map<String, Object> body = Map.of(
                    "eventId", eventId,
                    "type", type,
                    "amount", amount,
                    "currency", currency,
                    "eventTimestamp", eventTimestamp.toString()
            );

            String json = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(accountServiceBaseUrl + "/accounts/" + accountId + "/transactions"))
                    .header("Content-Type", "application/json")
                    .header(TRACE_HEADER, traceId != null ? traceId : "unknown")
                    .timeout(Duration.ofSeconds(3))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                log.error("Account service returned error: status={} body={}", response.statusCode(), response.body());
                throw new AccountServiceException("Account service error: " + response.statusCode());
            }

            log.info("Transaction applied successfully: accountId={} eventId={}", accountId, eventId);

        } catch (AccountServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to call account service: {}", e.getMessage());
            throw new AccountServiceException("Account service unavailable: " + e.getMessage(), e);
        }
    }

    @CircuitBreaker(name = "accountService", fallbackMethod = "getBalanceFallback")
    public BigDecimal getBalance(String accountId) {
        String traceId = MDC.get("traceId");
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(accountServiceBaseUrl + "/accounts/" + accountId + "/balance"))
                    .header(TRACE_HEADER, traceId != null ? traceId : "unknown")
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 404) {
                throw new AccountServiceException("Account not found: " + accountId);
            }
            if (response.statusCode() >= 400) {
                throw new AccountServiceException("Account service error: " + response.statusCode());
            }

            JsonNode node = objectMapper.readTree(response.body());
            log.info("Balance fetched: accountId={} traceId={}", accountId, traceId);
            return node.get("balance").decimalValue();

        } catch (AccountServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to fetch balance from account service: {}", e.getMessage());
            throw new AccountServiceException("Account service unavailable: " + e.getMessage(), e);
        }
    }

    public BigDecimal getBalanceFallback(String accountId, Throwable t) {
        log.warn("Circuit breaker open fetching balance. accountId={} cause={}", accountId, t.getMessage());
        throw new AccountServiceUnavailableException("Account service is currently unavailable. Please try again later.");
    }

    public void applyTransactionFallback(String accountId, String eventId, String type,
                                          BigDecimal amount, String currency, Instant eventTimestamp,
                                          Throwable t) {
        log.warn("Circuit breaker open for account service. accountId={} eventId={} cause={}",
                accountId, eventId, t.getMessage());
        throw new AccountServiceUnavailableException("Account service is currently unavailable. Please try again later.");
    }

    public static class AccountServiceException extends RuntimeException {
        public AccountServiceException(String message) { super(message); }
        public AccountServiceException(String message, Throwable cause) { super(message, cause); }
    }

    public static class AccountServiceUnavailableException extends RuntimeException {
        public AccountServiceUnavailableException(String message) { super(message); }
    }
}
