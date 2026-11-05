package basicbankingapp;

/** Thrown when a user is temporarily locked after too many failed logins. */
public class AccountLockedException extends AuthenticationException {

    private final long retryAfterSeconds;

    public AccountLockedException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** How long until the lock ends, rounded up, at least 1. */
    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
