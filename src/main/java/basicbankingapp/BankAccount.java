package basicbankingapp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Holds the banking rules for one account. It does no console input or output,
 * which keeps it easy to unit test. Transfers between accounts go through {@link Bank}.
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

    /** Rebuilds an account from saved data. Used by the storage layer. */
    static BankAccount restore(String customerName, String customerId, BigDecimal balance,
                               List<Transaction> history) {
        BankAccount account = new BankAccount(customerName, customerId);
        account.balance = balance.setScale(2, RoundingMode.UNNECESSARY);
        account.history.addAll(history);
        return account;
    }

    public void deposit(BigDecimal amount) {
        credit(amount, Transaction.Type.DEPOSIT, null);
    }

    public void withdraw(BigDecimal amount) {
        debit(amount, Transaction.Type.WITHDRAWAL, null);
    }

    /** Money leaving this account to another one. Package-private: use {@link Bank#transfer}. */
    void sendTransfer(BigDecimal amount, String toAccountId) {
        debit(amount, Transaction.Type.TRANSFER_OUT, toAccountId);
    }

    /** Money arriving from another account. Package-private: use {@link Bank#transfer}. */
    void receiveTransfer(BigDecimal amount, String fromAccountId) {
        credit(amount, Transaction.Type.TRANSFER_IN, fromAccountId);
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

    private void credit(BigDecimal amount, Transaction.Type type, String counterparty) {
        BigDecimal value = validate(amount);
        balance = balance.add(value);
        record(type, value, counterparty);
    }

    private void debit(BigDecimal amount, Transaction.Type type, String counterparty) {
        BigDecimal value = validate(amount);
        if (value.compareTo(balance) > 0) {
            throw new InsufficientFundsException(
                "Insufficient funds: balance is " + balance + " but you tried to withdraw " + value);
        }
        balance = balance.subtract(value);
        record(type, value, counterparty);
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

    private void record(Transaction.Type type, BigDecimal amount, String counterparty) {
        history.add(new Transaction(type, amount, balance, LocalDateTime.now().truncatedTo(ChronoUnit.MICROS), counterparty));
    }
}
