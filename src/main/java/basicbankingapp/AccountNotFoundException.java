package basicbankingapp;

/** Thrown when an account number does not exist. */
public class AccountNotFoundException extends IllegalArgumentException {
    public AccountNotFoundException(String accountNumber) {
        super("No account found with number " + accountNumber);
    }
}
