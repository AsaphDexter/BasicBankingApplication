package basicbankingapp;

/** Thrown when a login fails. The message never says whether the username or the password was wrong. */
public class AuthenticationException extends RuntimeException {
    public AuthenticationException(String message) {
        super(message);
    }
}
