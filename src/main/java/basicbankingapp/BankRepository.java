package basicbankingapp;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Where users, accounts and transactions are stored. {@link Bank} holds the rules and
 * talks to storage only through this interface, so the storage can be swapped
 * (in memory for quick runs, H2 database for real use).
 *
 * Objects returned by a repository are snapshots: after changing money, call
 * {@link #applyChange}; after changing login state, call {@link #saveLoginState}.
 */
public interface BankRepository extends AutoCloseable {

    /** Runs the work as one all-or-nothing unit. If it throws, every write inside it is undone. */
    <T> T inTransaction(Supplier<T> work);

    default void runInTransaction(Runnable work) {
        inTransaction(() -> {
            work.run();
            return null;
        });
    }

    boolean usernameExists(String username);

    /** Stores a new user together with their account. Throws {@link RegistrationException} if the username is taken. */
    void insertUser(User user);

    Optional<User> findUser(String username);

    Optional<BankAccount> findAccount(String accountNumber);

    /** Saves the user's failed-login count and lock expiry. */
    void saveLoginState(User user);

    void updatePasswordHash(String username, String newPasswordHash);

    void appendAudit(AuditEvent event);

    /** Newest first. {@code username} may be null for everyone. */
    List<AuditEvent> recentAudit(String username, int limit);

    /** Saves one money movement: the new balance (taken from the transaction) and the transaction itself. */
    void applyChange(String accountNumber, Transaction transaction);

    Optional<IdempotencyRecord> findIdempotencyRecord(String username, String key);

    void saveIdempotencyRecord(IdempotencyRecord record);

    /** Removes records created at or before the cutoff. */
    void deleteIdempotencyRecordsBefore(Instant cutoff);

    String nextAccountNumber();

    @Override
    void close();
}
