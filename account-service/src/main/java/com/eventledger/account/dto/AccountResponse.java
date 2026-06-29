package com.eventledger.account.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Data
@Builder
public class AccountResponse {
    private String accountId;
    private BigDecimal balance;
    private List<TransactionDto> recentTransactions;
}
