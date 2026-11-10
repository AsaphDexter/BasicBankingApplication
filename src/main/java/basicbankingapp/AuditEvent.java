package basicbankingapp;

import java.time.Instant;

/** One security-relevant thing that happened. Never contains passwords, hashes or tokens. */
public record AuditEvent(Instant time, Type type, String username, String detail) {

    public enum Type {
        REGISTERED, LOGIN_SUCCESS, LOGIN_FAILED, LOGIN_BLOCKED,
        ACCOUNT_LOCKED, ACCOUNT_UNLOCKED, PASSWORD_CHANGED, TRANSFER, REQUEST_REPLAYED
    }
}
