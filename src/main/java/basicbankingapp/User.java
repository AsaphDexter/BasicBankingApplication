package basicbankingapp;

import java.time.Instant;

/** A registered user and their account. Passwords are never stored, only a salted hash. */
public final class User {

    private final String username;
    private final String fullName;
    private final String passwordHash;
    private final BankAccount account;
    private volatile int failedLogins;
    private volatile Instant lockedUntil;

    User(String username, String fullName, String passwordHash, BankAccount account) {
        this(username, fullName, passwordHash, account, 0, null);
    }

    /** Used by the storage layer to rebuild a saved user. */
    User(String username, String fullName, String passwordHash, BankAccount account,
         int failedLogins, Instant lockedUntil) {
        this.username = username;
        this.fullName = fullName;
        this.passwordHash = passwordHash;
        this.account = account;
        this.failedLogins = failedLogins;
        this.lockedUntil = lockedUntil;
    }

    public String getUsername() {
        return username;
    }

    public String getFullName() {
        return fullName;
    }

    public BankAccount getAccount() {
        return account;
    }

    public int getFailedLogins() {
        return failedLogins;
    }

    /** True if a lock was set, even if it has already run out. */
    public boolean isLocked() {
        return lockedUntil != null;
    }

    /** True only while the lock is still in force at the given moment. */
    public boolean isLockedAt(Instant now) {
        Instant until = lockedUntil;
        return until != null && now.isBefore(until);
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    String getPasswordHash() {
        return passwordHash;
    }

    void recordFailedLogin() {
        failedLogins++;
    }

    void resetFailedLogins() {
        failedLogins = 0;
    }

    void lock(Instant until) {
        lockedUntil = until;
    }

    void unlock() {
        lockedUntil = null;
        failedLogins = 0;
    }
}
