package basicbankingapp;

/** An error that becomes an HTTP response with this status and a JSON {"error": "..."} body. */
final class ApiException extends RuntimeException {

    private final int status;

    ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    int status() {
        return status;
    }
}
