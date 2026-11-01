package basicbankingapp;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Users, sign-in and transfers. Everything is held in memory for now;
 * a database comes in the next stage. Not thread-safe.
 */
public class Bank {

    public static final int MAX_FAILED_LOGINS = 3;
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{3,20}");
    private static final String BAD_LOGIN = "Invalid username or password";

    private final PasswordHasher hasher;
    private final String dummyHash;
    private final Map<String, User> usersByUsername = new HashMap<>();
    private final Map<String, BankAccount> accountsById = new HashMap<>();
    private long nextAccountNumber = 1_000_000_001L;

    public Bank() {
        this(new PasswordHasher());
    }

    public Bank(PasswordHasher hasher) {
        this.hasher = hasher;
        // Checked against when the username does not exist, so a login for an unknown user
        // takes about as long as a real one and gives nothing away.
        this.dummyHash = hasher.hash("not-a-real-password".toCharArray());
    }

    /** Registers a user and opens their account. The password array is wiped before returning. */
    public User register(String username, String fullName, char[] password) {
        try {
            String key = normalize(username);
            if (key == null || !USERNAME.matcher(key).matches()) {
                throw new RegistrationException(
                    "Username must be 3-20 characters: letters, numbers and underscores only");
            }
            if (fullName == null || fullName.isBlank()) {
                throw new RegistrationException("Full name is required");
            }
            checkPasswordStrength(key, password);
            if (usersByUsername.containsKey(key)) {
                throw new RegistrationException("That username is already taken");
            }
            BankAccount account = new BankAccount(fullName.trim(), newAccountNumber());
            User user = new User(key, fullName.trim(), hasher.hash(password), account);
            usersByUsername.put(key, user);
            accountsById.put(account.getCustomerId(), account);
            return user;
        } finally {
            wipe(password);
        }
    }

    /**
     * Signs a user in. The same message is used for an unknown user and a wrong password.
     * After {@value #MAX_FAILED_LOGINS} failures in a row the user is locked until {@link #unlock}.
     * The password array is wiped before returning.
     */
    public User login(String username, char[] password) {
        try {
            User user = usersByUsername.get(normalize(username));
            if (user == null) {
                hasher.verify(password, dummyHash);
                throw new AuthenticationException(BAD_LOGIN);
            }
            if (user.isLocked()) {
                throw new AccountLockedException("This account is locked after too many failed attempts");
            }
            if (hasher.verify(password, user.getPasswordHash())) {
                user.resetFailedLogins();
                return user;
            }
            user.recordFailedLogin();
            if (user.getFailedLogins() >= MAX_FAILED_LOGINS) {
                user.lock();
                throw new AccountLockedException("Too many failed attempts. This account is now locked");
            }
            throw new AuthenticationException(BAD_LOGIN);
        } finally {
            wipe(password);
        }
    }

    /** For a future admin feature: lets a locked user sign in again. */
    public void unlock(String username) {
        User user = usersByUsername.get(normalize(username));
        if (user == null) {
            throw new IllegalArgumentException("Unknown user");
        }
        user.unlock();
    }

    public User getUser(String username) {
        return usersByUsername.get(normalize(username));
    }

    public BankAccount findAccount(String accountNumber) {
        return accountsById.get(accountNumber);
    }

    /**
     * Moves money between two accounts. Everything is checked before anything changes,
     * so a failed transfer leaves both balances as they were.
     */
    public void transfer(String fromAccountNumber, String toAccountNumber, BigDecimal amount) {
        BankAccount from = requireAccount(fromAccountNumber);
        BankAccount to = requireAccount(toAccountNumber);
        if (from == to) {
            throw new IllegalArgumentException("You cannot transfer to the same account");
        }
        from.sendTransfer(amount, to.getCustomerId()); // validates amount and funds first
        to.receiveTransfer(amount, from.getCustomerId());
    }

    private BankAccount requireAccount(String accountNumber) {
        BankAccount account = accountsById.get(accountNumber);
        if (account == null) {
            throw new IllegalArgumentException("No account found with number " + accountNumber);
        }
        return account;
    }

    private void checkPasswordStrength(String username, char[] password) {
        if (password == null || password.length < 8) {
            throw new RegistrationException("Password must be at least 8 characters");
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (char c : password) {
            hasLetter |= Character.isLetter(c);
            hasDigit |= Character.isDigit(c);
        }
        if (!hasLetter || !hasDigit) {
            throw new RegistrationException("Password must contain at least one letter and one number");
        }
        if (new String(password).equalsIgnoreCase(username)) {
            throw new RegistrationException("Password cannot be the same as the username");
        }
    }

    private String newAccountNumber() {
        return String.valueOf(nextAccountNumber++);
    }

    private static String normalize(String username) {
        return username == null ? null : username.trim().toLowerCase();
    }

    private static void wipe(char[] password) {
        if (password != null) {
            Arrays.fill(password, '\0');
        }
    }
}
