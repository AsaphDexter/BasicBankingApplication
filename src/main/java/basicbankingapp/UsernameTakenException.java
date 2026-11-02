package basicbankingapp;

/** Thrown when registering a username that already exists. */
public class UsernameTakenException extends RegistrationException {
    public UsernameTakenException() {
        super("That username is already taken");
    }
}
