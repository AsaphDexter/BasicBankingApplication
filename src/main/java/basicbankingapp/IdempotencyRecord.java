package basicbankingapp;

import java.time.Instant;

/** What was answered the first time a client used an idempotency key. */
public record IdempotencyRecord(String username, String key, String fingerprint, String result, Instant createdAt) { }
