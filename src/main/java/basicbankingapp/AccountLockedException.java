package basicbankingapp;

/** Thrown when a user is locked after too many failed logins. */
public class AccountLockedException extends AuthenticationException {
    public AccountLockedException(String message) {
        super(message);
    }
}
