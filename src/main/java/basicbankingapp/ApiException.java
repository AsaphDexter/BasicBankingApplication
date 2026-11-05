package basicbankingapp;

/** An error that becomes an HTTP response with this status and a JSON {"error": "..."} body. */
final class ApiException extends RuntimeException {

    private final int status;
    private final Long retryAfterSeconds;

    ApiException(int status, String message) {
        this(status, message, null);
    }

    /** {@code retryAfterSeconds} becomes a Retry-After header (used for 423 and 429). */
    ApiException(int status, String message, Long retryAfterSeconds) {
        super(message);
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    int status() {
        return status;
    }

    Long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
