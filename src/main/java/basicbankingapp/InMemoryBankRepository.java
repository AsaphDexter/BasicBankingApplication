package basicbankingapp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Keeps everything in memory. Fast and simple, but all data is lost when the program ends.
 * Like the database version it hands out snapshots, never the stored objects themselves,
 * so a caller can never change stored data (or race with another thread) by accident.
 */
public class InMemoryBankRepository implements BankRepository {

    private static final class StoredUser {
        final String username;
        final String fullName;
        final String accountNumber;
        String passwordHash;
        int failedLogins;
        Instant lockedUntil;

        StoredUser(String username, String fullName, String passwordHash, String accountNumber) {
            this.username = username;
            this.fullName = fullName;
            this.passwordHash = passwordHash;
            this.accountNumber = accountNumber;
        }
    }

    private static final class StoredAccount {
        final String number;
        final String ownerName;
        BigDecimal balance;
        final List<Transaction> history = new ArrayList<>();

        StoredAccount(String number, String ownerName, BigDecimal balance) {
            this.number = number;
            this.ownerName = ownerName;
            this.balance = balance;
        }
    }

    private final Map<String, StoredUser> users = new HashMap<>();
    private final Map<String, StoredAccount> accounts = new HashMap<>();
    private final List<AuditEvent> audit = new ArrayList<>();
    private long nextAccountNumber = 1_000_000_001L;

    @Override
    public synchronized <T> T inTransaction(Supplier<T> work) {
        // Every change goes through applyChange / saveLoginState, and the rules check everything
        // before changing anything, so there is nothing to roll back here.
        return work.get();
    }

    @Override
    public synchronized boolean usernameExists(String username) {
        return users.containsKey(username);
    }

    @Override
    public synchronized void insertUser(User user) {
        if (users.containsKey(user.getUsername())) {
            throw new UsernameTakenException();
        }
        BankAccount account = user.getAccount();
        users.put(user.getUsername(), new StoredUser(user.getUsername(), user.getFullName(),
            user.getPasswordHash(), account.getCustomerId()));
        accounts.put(account.getCustomerId(),
            new StoredAccount(account.getCustomerId(), account.getCustomerName(), account.getBalance()));
    }

    @Override
    public synchronized Optional<User> findUser(String username) {
        StoredUser stored = users.get(username);
        if (stored == null) {
            return Optional.empty();
        }
        BankAccount account = snapshot(accounts.get(stored.accountNumber));
        return Optional.of(new User(stored.username, stored.fullName, stored.passwordHash, account,
            stored.failedLogins, stored.lockedUntil));
    }

    @Override
    public synchronized Optional<BankAccount> findAccount(String accountNumber) {
        StoredAccount stored = accounts.get(accountNumber);
        return stored == null ? Optional.empty() : Optional.of(snapshot(stored));
    }

    @Override
    public synchronized void saveLoginState(User user) {
        StoredUser stored = users.get(user.getUsername());
        if (stored != null) {
            stored.failedLogins = user.getFailedLogins();
            stored.lockedUntil = user.getLockedUntil();
        }
    }

    @Override
    public synchronized void updatePasswordHash(String username, String newPasswordHash) {
        StoredUser stored = users.get(username);
        if (stored == null) {
            throw new RepositoryException("User not found: " + username);
        }
        stored.passwordHash = newPasswordHash;
    }

    @Override
    public synchronized void applyChange(String accountNumber, Transaction transaction) {
        StoredAccount stored = accounts.get(accountNumber);
        if (stored == null) {
            throw new RepositoryException("Account not found: " + accountNumber);
        }
        stored.balance = transaction.balanceAfter();
        stored.history.add(transaction);
    }

    @Override
    public synchronized void appendAudit(AuditEvent event) {
        audit.add(event);
    }

    @Override
    public synchronized List<AuditEvent> recentAudit(String username, int limit) {
        List<AuditEvent> result = new ArrayList<>();
        for (int i = audit.size() - 1; i >= 0 && result.size() < limit; i--) {
            AuditEvent event = audit.get(i);
            if (username == null || username.equals(event.username())) {
                result.add(event);
            }
        }
        return result;
    }

    @Override
    public synchronized String nextAccountNumber() {
        return String.valueOf(nextAccountNumber++);
    }

    @Override
    public void close() {
        // Nothing to release.
    }

    private static BankAccount snapshot(StoredAccount stored) {
        return BankAccount.restore(stored.ownerName, stored.number, stored.balance, stored.history);
    }
}
