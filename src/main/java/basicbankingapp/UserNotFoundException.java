package basicbankingapp;

/** Thrown when an administrator names a user that does not exist. */
public class UserNotFoundException extends IllegalArgumentException {
    public UserNotFoundException() {
        super("No such user");
    }
}
