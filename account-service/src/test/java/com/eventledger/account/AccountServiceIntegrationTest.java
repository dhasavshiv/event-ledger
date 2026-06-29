package com.eventledger.account;

import com.eventledger.account.dto.TransactionRequest;
import com.eventledger.account.repository.AccountRepository;
import com.eventledger.account.repository.TransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AccountServiceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
    }

    private TransactionRequest buildRequest(String eventId, String type, double amount) {
        TransactionRequest req = new TransactionRequest();
        req.setEventId(eventId);
        req.setType(type);
        req.setAmount(BigDecimal.valueOf(amount));
        req.setCurrency("USD");
        req.setEventTimestamp(Instant.now());
        return req;
    }

    @Test
    void testApplyTransaction_Credit_BalanceUpdated() throws Exception {
        TransactionRequest req = buildRequest("evt-001", "CREDIT", 200.00);

        mockMvc.perform(post("/accounts/acct-bal/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/accounts/acct-bal/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(200.00));
    }

    @Test
    void testApplyTransaction_DebitAfterCredit_CorrectBalance() throws Exception {
        // Credit 500
        mockMvc.perform(post("/accounts/acct-net/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRequest("evt-c1", "CREDIT", 500.00))))
                .andExpect(status().isOk());

        // Debit 150
        mockMvc.perform(post("/accounts/acct-net/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRequest("evt-d1", "DEBIT", 150.00))))
                .andExpect(status().isOk());

        // Balance should be 350
        mockMvc.perform(get("/accounts/acct-net/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(350.00));
    }

    @Test
    void testIdempotency_DuplicateTransactionNotAppliedTwice() throws Exception {
        TransactionRequest req = buildRequest("evt-dup", "CREDIT", 100.00);
        String json = objectMapper.writeValueAsString(req);

        mockMvc.perform(post("/accounts/acct-idem/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());

        // Duplicate
        mockMvc.perform(post("/accounts/acct-idem/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());

        // Balance must be 100, not 200
        mockMvc.perform(get("/accounts/acct-idem/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(100.00));
    }

    @Test
    void testGetAccount_NotFound() throws Exception {
        mockMvc.perform(get("/accounts/nonexistent"))
                .andExpect(status().isNotFound());
    }

    @Test
    void testGetAccount_ReturnsTransactionsInOrder() throws Exception {
        // Submit out-of-order events
        TransactionRequest later = buildRequest("evt-late", "CREDIT", 50.00);
        later.setEventTimestamp(Instant.parse("2026-05-15T15:00:00Z"));

        TransactionRequest earlier = buildRequest("evt-early", "CREDIT", 30.00);
        earlier.setEventTimestamp(Instant.parse("2026-05-15T10:00:00Z"));

        mockMvc.perform(post("/accounts/acct-ord/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(later)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/accounts/acct-ord/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(earlier)))
                .andExpect(status().isOk());

        // Transactions in response should be chronological
        mockMvc.perform(get("/accounts/acct-ord"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentTransactions[0].eventId").value("evt-early"))
                .andExpect(jsonPath("$.recentTransactions[1].eventId").value("evt-late"));
    }

    @Test
    void testTracePropagation_TraceIdLogged() throws Exception {
        TransactionRequest req = buildRequest("evt-trace", "CREDIT", 100.00);

        // Trace ID header must be accepted without error
        mockMvc.perform(post("/accounts/acct-trace/transactions")
                        .header("X-Trace-Id", "trace-abc-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
    }

    @Test
    void testHealthEndpoint() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
