package com.eventledger.account.controller;

import com.eventledger.account.dto.AccountResponse;
import com.eventledger.account.dto.TransactionRequest;
import com.eventledger.account.repository.AccountRepository;
import com.eventledger.account.service.AccountService;
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
import java.util.stream.Collectors;

@RestController
public class AccountController {

    private static final Logger log = LoggerFactory.getLogger(AccountController.class);
    private final AccountService accountService;
    private final AccountRepository accountRepository;

    public AccountController(AccountService accountService, AccountRepository accountRepository) {
        this.accountService = accountService;
        this.accountRepository = accountRepository;
    }

    @PostMapping("/accounts/{accountId}/transactions")
    public ResponseEntity<?> applyTransaction(
            @PathVariable String accountId,
            @Valid @RequestBody TransactionRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {

        if (traceId != null) MDC.put("traceId", traceId);
        try {
            log.info("Received transaction request: accountId={} eventId={} type={} traceId={}",
                    accountId, request.getEventId(), request.getType(), traceId);
            accountService.applyTransaction(accountId, request);
            return ResponseEntity.ok(Map.of("status", "applied", "accountId", accountId));
        } finally {
            MDC.clear();
        }
    }

    @GetMapping("/accounts/{accountId}/balance")
    public ResponseEntity<?> getBalance(
            @PathVariable String accountId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {

        if (traceId != null) MDC.put("traceId", traceId);
        try {
            BigDecimal balance = accountService.getBalance(accountId);
            return ResponseEntity.ok(Map.of("accountId", accountId, "balance", balance));
        } catch (AccountService.AccountNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        } finally {
            MDC.clear();
        }
    }

    @GetMapping("/accounts/{accountId}")
    public ResponseEntity<?> getAccount(
            @PathVariable String accountId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {

        if (traceId != null) MDC.put("traceId", traceId);
        try {
            AccountResponse response = accountService.getAccount(accountId);
            return ResponseEntity.ok(response);
        } catch (AccountService.AccountNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        } finally {
            MDC.clear();
        }
    }

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        try {
            accountRepository.count();
            return ResponseEntity.ok(Map.of(
                    "status", "UP",
                    "service", "account-service",
                    "database", "UP"
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "status", "DOWN",
                    "service", "account-service",
                    "database", "DOWN"
            ));
        }
    }

}
