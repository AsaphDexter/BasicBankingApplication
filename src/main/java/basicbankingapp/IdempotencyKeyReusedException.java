package basicbankingapp;

/** Thrown when an idempotency key is reused for a different request than the one it was first used for. */
public class IdempotencyKeyReusedException extends IllegalArgumentException {
    public IdempotencyKeyReusedException() {
        super("This Idempotency-Key was already used with a different request");
    }
}
