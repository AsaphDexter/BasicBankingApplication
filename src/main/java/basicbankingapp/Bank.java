package basicbankingapp;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The rules for users, sign-in, passwords, deposits, withdrawals and transfers.
 * Storage is behind {@link BankRepository}, and every money movement runs as one
 * all-or-nothing transaction.
 *
 * Thread-safe. Password hashing is slow on purpose, so it always runs OUTSIDE the lock:
 * a sign-in never makes other customers wait. Only the short read-change-save steps hold the lock.
 */
public class Bank {

    public static final int MAX_FAILED_LOGINS = 3;
    public static final Duration DEFAULT_LOCKOUT = Duration.ofMinutes(15);
    /** How long a client may safely retry a request with the same idempotency key. */
    public static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9_-]{8,64}");
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{3,20}");
    private static final String BAD_LOGIN = "Invalid username or password";
    private static final String BAD_OLD_PASSWORD = "Current password is incorrect";

    private final PasswordHasher hasher;
    private final BankRepository repository;
    private final Clock clock;
    private final Duration lockout;
    private final String dummyHash;
    private final Object lock = new Object();

    /** In-memory bank with the default (strong) password hashing. */
    public Bank() {
        this(new PasswordHasher(), new InMemoryBankRepository());
    }

    public Bank(PasswordHasher hasher, BankRepository repository) {
        this(hasher, repository, Clock.systemUTC(), DEFAULT_LOCKOUT);
    }

    public Bank(PasswordHasher hasher, BankRepository repository, Clock clock, Duration lockout) {
        if (lockout == null || lockout.isZero() || lockout.isNegative()) {
            throw new IllegalArgumentException("Lockout must be positive");
        }
        this.hasher = hasher;
        this.repository = repository;
        this.clock = clock;
        this.lockout = lockout;
        // Checked against when the username does not exist, so a login for an unknown user
        // takes about as long as a real one and gives nothing away.
        this.dummyHash = hasher.hash("not-a-real-password".toCharArray());
    }

    // ---------- registration ----------

    /** Registers a user and opens their account. The password array is wiped before returning. */
    public User register(String username, String fullName, char[] password) {
        try {
            String key = normalize(username);
            if (key == null || !USERNAME.matcher(key).matches()) {
                throw new RegistrationException(
                    "Username must be 3-20 characters: letters, numbers and underscores only");
            }
            if (fullName == null || fullName.isBlank()) {
                throw new RegistrationException("Full name is required");
            }
            checkPasswordStrength(key, password);
            String name = fullName.trim();
            String passwordHash = hasher.hash(password); // slow: done before taking the lock

            synchronized (lock) {
                return repository.inTransaction(() -> {
                    if (repository.usernameExists(key)) {
                        throw new UsernameTakenException();
                    }
                    BankAccount account = new BankAccount(name, repository.nextAccountNumber());
                    User user = new User(key, name, passwordHash, account);
                    repository.insertUser(user);
                    audit(AuditEvent.Type.REGISTERED, key, "account " + account.getCustomerId());
                    return user;
                });
            }
        } finally {
            wipe(password);
        }
    }

    // ---------- login ----------

    public User login(String username, char[] password) {
        return login(username, password, null);
    }

    /**
     * Signs a user in. The same message is used for an unknown user and a wrong password.
     * After {@value #MAX_FAILED_LOGINS} failures in a row the user is locked for the lockout period
     * (or until {@link #unlock}). {@code clientAddress} is only recorded in the audit log.
     * The password array is wiped before returning.
     */
    public User login(String username, char[] password, String clientAddress) {
        try {
            String key = normalize(username);
            User seen = key == null ? null : repository.findUser(key).orElse(null);
            if (seen == null) {
                hasher.verify(password, dummyHash);
                audit(AuditEvent.Type.LOGIN_FAILED, key, "unknown user" + client(clientAddress));
                throw new AuthenticationException(BAD_LOGIN);
            }
            Instant now = clock.instant();
            if (seen.isLockedAt(now)) {
                throw blocked(seen, now, clientAddress);
            }
            boolean correct = hasher.verify(password, seen.getPasswordHash()); // slow: outside the lock

            Outcome outcome;
            synchronized (lock) {
                outcome = repository.inTransaction(() -> {
                    User user = repository.findUser(key).orElse(null);
                    if (user == null) {
                        return Outcome.failed(new AuthenticationException(BAD_LOGIN));
                    }
                    Instant at = clock.instant();
                    if (user.isLockedAt(at)) {
                        return Outcome.failed(blocked(user, at, clientAddress));
                    }
                    if (user.isLocked()) { // the lock has run out: start again with a clean count
                        user.unlock();
                    }
                    boolean stillValid = correct && user.getPasswordHash().equals(seen.getPasswordHash());
                    if (stillValid) {
                        if (user.getFailedLogins() > 0 || seen.isLocked()) {
                            user.resetFailedLogins();
                            repository.saveLoginState(user);
                        }
                        audit(AuditEvent.Type.LOGIN_SUCCESS, key, client(clientAddress).trim());
                        return Outcome.ok(user);
                    }
                    return Outcome.failed(failedAttempt(user, at, "wrong password", BAD_LOGIN, clientAddress));
                });
            }
            // The exception is thrown only now, after the transaction committed, so the failure
            // count and audit entries are kept rather than rolled back with it.
            return outcome.userOrThrow();
        } finally {
            wipe(password);
        }
    }

    /** For administrators: lets a locked user sign in again right away. */
    public void unlock(String username) {
        String key = normalize(username);
        synchronized (lock) {
            repository.runInTransaction(() -> {
                User user = key == null ? null : repository.findUser(key).orElse(null);
                if (user == null) {
                    throw new UserNotFoundException();
                }
                user.unlock();
                repository.saveLoginState(user);
                audit(AuditEvent.Type.ACCOUNT_UNLOCKED, key, "by administrator");
            });
        }
    }

    // ---------- password change ----------

    /**
     * Changes a password after checking the current one. A wrong current password counts as a failed
     * login (so a stolen session cannot be used to guess the password). Both arrays are wiped.
     */
    public void changePassword(String username, char[] oldPassword, char[] newPassword, String clientAddress) {
        try {
            String key = normalize(username);
            User seen = key == null ? null : repository.findUser(key).orElse(null);
            if (seen == null) {
                throw new AuthenticationException(BAD_OLD_PASSWORD);
            }
            Instant now = clock.instant();
            if (seen.isLockedAt(now)) {
                throw blocked(seen, now, clientAddress);
            }
            boolean correct = hasher.verify(oldPassword, seen.getPasswordHash()); // slow: outside the lock
            String newHash = null;
            if (correct) {
                checkPasswordStrength(key, newPassword);
                if (hasher.verify(newPassword, seen.getPasswordHash())) {
                    throw new RegistrationException("New password must be different from the current one");
                }
                newHash = hasher.hash(newPassword);
            }
            String hashToStore = newHash;

            Outcome outcome;
            synchronized (lock) {
                outcome = repository.inTransaction(() -> {
                    User user = repository.findUser(key).orElse(null);
                    if (user == null) {
                        return Outcome.failed(new AuthenticationException(BAD_OLD_PASSWORD));
                    }
                    Instant at = clock.instant();
                    if (user.isLockedAt(at)) {
                        return Outcome.failed(blocked(user, at, clientAddress));
                    }
                    if (user.isLocked()) {
                        user.unlock();
                    }
                    if (!correct || !user.getPasswordHash().equals(seen.getPasswordHash())) {
                        return Outcome.failed(failedAttempt(user, at, "wrong current password",
                            BAD_OLD_PASSWORD, clientAddress));
                    }
                    repository.updatePasswordHash(key, hashToStore);
                    user.resetFailedLogins();
                    repository.saveLoginState(user);
                    audit(AuditEvent.Type.PASSWORD_CHANGED, key, client(clientAddress).trim());
                    return Outcome.ok(user);
                });
            }
            outcome.userOrThrow();
        } finally {
            wipe(oldPassword);
            wipe(newPassword);
        }
    }

    // ---------- reading ----------

    /** A snapshot of the user as stored right now, or null. */
    public User getUser(String username) {
        String key = normalize(username);
        return key == null ? null : repository.findUser(key).orElse(null);
    }

    /** A snapshot of the account as stored right now, or null. */
    public BankAccount findAccount(String accountNumber) {
        return repository.findAccount(accountNumber).orElse(null);
    }

    /** Newest first. {@code username} may be null for all users. */
    public List<AuditEvent> auditLog(String username, int limit) {
        return repository.recentAudit(normalize(username), limit);
    }

    // ---------- money ----------

    public void deposit(String accountNumber, BigDecimal amount) {
        synchronized (lock) {
            repository.runInTransaction(() -> {
                BankAccount account = requireAccount(accountNumber);
                account.deposit(amount);
                save(account);
            });
        }
    }

    public void withdraw(String accountNumber, BigDecimal amount) {
        synchronized (lock) {
            repository.runInTransaction(() -> {
                BankAccount account = requireAccount(accountNumber);
                account.withdraw(amount);
                save(account);
            });
        }
    }

    /**
     * Moves money between two accounts as one all-or-nothing transaction:
     * if anything fails, neither balance changes.
     */
    public void transfer(String fromAccountNumber, String toAccountNumber, BigDecimal amount) {
        transfer(fromAccountNumber, toAccountNumber, amount, null);
    }

    /** Same as above; {@code actingUsername} (may be null) is recorded in the audit log. */
    public void transfer(String fromAccountNumber, String toAccountNumber, BigDecimal amount, String actingUsername) {
        synchronized (lock) {
            repository.runInTransaction(() -> {
                BankAccount from = requireAccount(fromAccountNumber);
                BankAccount to = requireAccount(toAccountNumber);
                if (from.getCustomerId().equals(to.getCustomerId())) {
                    throw new IllegalArgumentException("You cannot transfer to the same account");
                }
                from.sendTransfer(amount, to.getCustomerId()); // validates amount and funds first
                to.receiveTransfer(amount, from.getCustomerId());
                save(from);
                save(to);
                audit(AuditEvent.Type.TRANSFER, actingUsername == null ? null : normalize(actingUsername), "from " + from.getCustomerId() + " to "
                    + to.getCustomerId() + " amount " + amount.setScale(2, java.math.RoundingMode.UNNECESSARY));
            });
        }
    }

    // ---------- safe retries ----------

    /** The answer to a request, and whether it is a repeat of an earlier answer. */
    public record OnceResult(String result, boolean replayed) { }

    /**
     * Runs {@code action} at most once per (user, key). If the same user sends the same key and the same
     * request again, the stored answer is returned and nothing is repeated. If the key comes back with a
     * different request, {@link IdempotencyKeyReusedException} is thrown. A repeat that arrives while the
     * first is still running waits for it (everything is serialized by one lock) and then gets the stored answer.
     *
     * The action and the stored answer are saved in ONE transaction, so a crash can never leave money moved
     * without a record of it. If the action fails nothing is stored and the key can be used again.
     * Records expire after {@link #IDEMPOTENCY_TTL}.
     *
     * @param fingerprint a fixed-size summary of the request (for example a SHA-256 hex string)
     */
    public OnceResult executeOnce(String username, String key, String fingerprint, Supplier<String> action) {
        if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("The idempotency key must be 8 to 64 letters, numbers, hyphens or underscores");
        }
        if (fingerprint == null || fingerprint.isEmpty() || fingerprint.length() > 64) {
            throw new IllegalArgumentException("The request fingerprint must be 1 to 64 characters");
        }
        String owner = normalize(username);
        synchronized (lock) {
            return repository.inTransaction(() -> {
                Instant now = clock.instant();
                Optional<IdempotencyRecord> earlier = repository.findIdempotencyRecord(owner, key)
                    .filter(record -> record.createdAt().plus(IDEMPOTENCY_TTL).isAfter(now));
                if (earlier.isPresent()) {
                    if (!earlier.get().fingerprint().equals(fingerprint)) {
                        throw new IdempotencyKeyReusedException();
                    }
                    audit(AuditEvent.Type.REQUEST_REPLAYED, owner, "key " + key);
                    return new OnceResult(earlier.get().result(), true);
                }
                String result = action.get();
                repository.deleteIdempotencyRecordsBefore(now.minus(IDEMPOTENCY_TTL)); // also clears an expired copy of this key
                repository.saveIdempotencyRecord(new IdempotencyRecord(owner, key, fingerprint, result, now));
                return new OnceResult(result, false);
            });
        }
    }

    // ---------- helpers ----------

    /** Result of a login-style transaction: either a user, or the failure to throw once it has committed. */
    private record Outcome(User user, RuntimeException failure) {
        static Outcome ok(User user) {
            return new Outcome(user, null);
        }

        static Outcome failed(RuntimeException failure) {
            return new Outcome(null, failure);
        }

        User userOrThrow() {
            if (failure != null) {
                throw failure;
            }
            return user;
        }
    }

    /** Counts a failed attempt, locks the user at the limit, and returns the exception to throw. */
    private AuthenticationException failedAttempt(User user, Instant at, String reason, String message,
                                                  String clientAddress) {
        user.recordFailedLogin();
        boolean lockNow = user.getFailedLogins() >= MAX_FAILED_LOGINS;
        if (lockNow) {
            user.lock(at.plus(lockout));
        }
        repository.saveLoginState(user);
        audit(AuditEvent.Type.LOGIN_FAILED, user.getUsername(), reason + client(clientAddress));
        if (lockNow) {
            audit(AuditEvent.Type.ACCOUNT_LOCKED, user.getUsername(), "locked for " + lockout.toMinutes()
                + " min after " + MAX_FAILED_LOGINS + " failed attempts");
            return new AccountLockedException("Too many failed attempts. This account is now locked",
                secondsUntil(user.getLockedUntil(), at));
        }
        return new AuthenticationException(message);
    }

    private AccountLockedException blocked(User user, Instant now, String clientAddress) {
        audit(AuditEvent.Type.LOGIN_BLOCKED, user.getUsername(), "account is locked" + client(clientAddress));
        return new AccountLockedException("This account is locked after too many failed attempts",
            secondsUntil(user.getLockedUntil(), now));
    }

    private static long secondsUntil(Instant until, Instant now) {
        long millis = Duration.between(now, until).toMillis();
        return Math.max(1, (millis + 999) / 1000);
    }

    private void audit(AuditEvent.Type type, String username, String detail) {
        String safeName = username == null ? null : cleanForLog(username, 50);
        repository.appendAudit(new AuditEvent(clock.instant(), type, safeName, cleanForLog(detail, 200)));
    }

    /** Keeps attacker-supplied text from forging log lines: control characters become '?' and length is capped. */
    private static String cleanForLog(String text, int maxLength) {
        StringBuilder cleaned = new StringBuilder();
        for (int i = 0; i < text.length() && cleaned.length() < maxLength; i++) {
            char c = text.charAt(i);
            cleaned.append(Character.isISOControl(c) ? '?' : c);
        }
        return cleaned.toString();
    }

    private static String client(String clientAddress) {
        return clientAddress == null ? "" : " client=" + clientAddress;
    }

    /** Stores the newest transaction of an account that was just changed. */
    private void save(BankAccount account) {
        Transaction latest = account.getPreviousTransaction().orElseThrow();
        repository.applyChange(account.getCustomerId(), latest);
    }

    private BankAccount requireAccount(String accountNumber) {
        return repository.findAccount(accountNumber).orElseThrow(
            () -> new AccountNotFoundException(accountNumber));
    }

    private void checkPasswordStrength(String username, char[] password) {
        if (password == null || password.length < 8) {
            throw new RegistrationException("Password must be at least 8 characters");
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (char c : password) {
            hasLetter |= Character.isLetter(c);
            hasDigit |= Character.isDigit(c);
        }
        if (!hasLetter || !hasDigit) {
            throw new RegistrationException("Password must contain at least one letter and one number");
        }
        if (new String(password).equalsIgnoreCase(username)) {
            throw new RegistrationException("Password cannot be the same as the username");
        }
    }

    private static String normalize(String username) {
        return username == null ? null : username.trim().toLowerCase();
    }

    private static void wipe(char[] password) {
        if (password != null) {
            Arrays.fill(password, '\0');
        }
    }
}
