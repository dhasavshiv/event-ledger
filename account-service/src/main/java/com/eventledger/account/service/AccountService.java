package com.eventledger.account.service;

import com.eventledger.account.dto.AccountResponse;
import com.eventledger.account.dto.TransactionDto;
import com.eventledger.account.dto.TransactionRequest;
import com.eventledger.account.model.Account;
import com.eventledger.account.model.Transaction;
import com.eventledger.account.repository.AccountRepository;
import com.eventledger.account.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public AccountService(AccountRepository accountRepository, TransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    @Transactional
    public void applyTransaction(String accountId, TransactionRequest request) {
        String traceId = MDC.get("traceId");

        // Idempotency — if we've already processed this eventId, skip silently
        if (transactionRepository.existsById(request.getEventId())) {
            log.info("Transaction already applied, skipping: eventId={} traceId={}", request.getEventId(), traceId);
            return;
        }

        // Get or create account
        Account account = accountRepository.findById(accountId)
                .orElseGet(() -> {
                    log.info("Creating new account: accountId={}", accountId);
                    return accountRepository.save(Account.builder()
                            .accountId(accountId)
                            .balance(BigDecimal.ZERO)
                            .build());
                });

        // Apply transaction
        BigDecimal newBalance;
        if ("CREDIT".equals(request.getType())) {
            newBalance = account.getBalance().add(request.getAmount());
        } else {
            newBalance = account.getBalance().subtract(request.getAmount());
        }
        account.setBalance(newBalance);
        accountRepository.save(account);

        // Record transaction
        Transaction transaction = Transaction.builder()
                .eventId(request.getEventId())
                .accountId(accountId)
                .type(request.getType())
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .eventTimestamp(request.getEventTimestamp())
                .processedAt(Instant.now())
                .build();
        transactionRepository.save(transaction);

        log.info("Transaction applied: accountId={} eventId={} type={} amount={} newBalance={} traceId={}",
                accountId, request.getEventId(), request.getType(), request.getAmount(), newBalance, traceId);
    }

    public BigDecimal getBalance(String accountId) {
        return accountRepository.findById(accountId)
                .map(Account::getBalance)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountId));
    }

    public AccountResponse getAccount(String accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountId));

        List<TransactionDto> transactions = transactionRepository
                .findByAccountIdOrderByEventTimestampAsc(accountId)
                .stream()
                .map(t -> TransactionDto.builder()
                        .eventId(t.getEventId())
                        .accountId(t.getAccountId())
                        .type(t.getType())
                        .amount(t.getAmount())
                        .currency(t.getCurrency())
                        .eventTimestamp(t.getEventTimestamp())
                        .processedAt(t.getProcessedAt())
                        .build())
                .collect(Collectors.toList());

        return AccountResponse.builder()
                .accountId(account.getAccountId())
                .balance(account.getBalance())
                .recentTransactions(transactions)
                .build();
    }

    public static class AccountNotFoundException extends RuntimeException {
        public AccountNotFoundException(String message) { super(message); }
    }
}
