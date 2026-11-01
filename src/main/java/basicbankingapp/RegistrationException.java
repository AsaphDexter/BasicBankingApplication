package basicbankingapp;

/** Thrown when a new user cannot be registered (bad username, weak password, duplicate). */
public class RegistrationException extends RuntimeException {
    public RegistrationException(String message) {
        super(message);
    }
}
