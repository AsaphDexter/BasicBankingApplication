package basicbankingapp;

/** A registered user and their account. Passwords are never stored, only a salted hash. */
public final class User {

    private final String username;
    private final String fullName;
    private final String passwordHash;
    private final BankAccount account;
    private int failedLogins;
    private boolean locked;

    User(String username, String fullName, String passwordHash, BankAccount account) {
        this.username = username;
        this.fullName = fullName;
        this.passwordHash = passwordHash;
        this.account = account;
    }

    public String getUsername() {
        return username;
    }

    public String getFullName() {
        return fullName;
    }

    public BankAccount getAccount() {
        return account;
    }

    public boolean isLocked() {
        return locked;
    }

    public int getFailedLogins() {
        return failedLogins;
    }

    String getPasswordHash() {
        return passwordHash;
    }

    void recordFailedLogin() {
        failedLogins++;
    }

    void resetFailedLogins() {
        failedLogins = 0;
    }

    void lock() {
        locked = true;
    }

    void unlock() {
        locked = false;
        failedLogins = 0;
    }
}
