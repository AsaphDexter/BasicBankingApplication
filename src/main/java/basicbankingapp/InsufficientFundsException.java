package basicbankingapp;

/** Thrown when a withdrawal is larger than the available balance. */
public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(String message) {
        super(message);
    }
}
