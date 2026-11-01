package basicbankingapp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Holds the banking rules only. It does no console input or output,
 * which keeps it easy to unit test.
 */
public class BankAccount {

    private final String customerName;
    private final String customerId;
    private BigDecimal balance = BigDecimal.ZERO.setScale(2);
    private final List<Transaction> history = new ArrayList<>();

    public BankAccount(String customerName, String customerId) {
        if (customerName == null || customerName.isBlank()) {
            throw new IllegalArgumentException("Customer name is required");
        }
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("Customer ID is required");
        }
        this.customerName = customerName;
        this.customerId = customerId;
    }

    public void deposit(BigDecimal amount) {
        BigDecimal value = validate(amount);
        balance = balance.add(value);
        record(Transaction.Type.DEPOSIT, value);
    }

    public void withdraw(BigDecimal amount) {
        BigDecimal value = validate(amount);
        if (value.compareTo(balance) > 0) {
            throw new InsufficientFundsException(
                "Insufficient funds: balance is " + balance + " but you tried to withdraw " + value);
        }
        balance = balance.subtract(value);
        record(Transaction.Type.WITHDRAWAL, value);
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public String getCustomerName() {
        return customerName;
    }

    public String getCustomerId() {
        return customerId;
    }

    /** All transactions, oldest first. The list cannot be modified by callers. */
    public List<Transaction> getHistory() {
        return Collections.unmodifiableList(history);
    }

    public Optional<Transaction> getPreviousTransaction() {
        return history.isEmpty() ? Optional.empty() : Optional.of(history.get(history.size() - 1));
    }

    private BigDecimal validate(BigDecimal amount) {
        if (amount == null) {
            throw new IllegalArgumentException("Amount is required");
        }
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Amount cannot have more than 2 decimal places");
        }
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    private void record(Transaction.Type type, BigDecimal amount) {
        history.add(new Transaction(type, amount, balance, LocalDateTime.now()));
    }
}
