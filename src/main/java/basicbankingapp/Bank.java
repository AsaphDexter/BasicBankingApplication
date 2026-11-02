package basicbankingapp;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * The rules for users, sign-in, deposits, withdrawals and transfers.
 * Storage is behind {@link BankRepository}, and every money movement runs as one
 * all-or-nothing transaction. Every public method is synchronized, so one Bank can be shared
 * by several threads (for example the web server's request threads).
 */
public class Bank {

    public static final int MAX_FAILED_LOGINS = 3;
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{3,20}");
    private static final String BAD_LOGIN = "Invalid username or password";

    private final PasswordHasher hasher;
    private final BankRepository repository;
    private final String dummyHash;

    /** In-memory bank with the default (strong) password hashing. */
    public Bank() {
        this(new PasswordHasher(), new InMemoryBankRepository());
    }

    public Bank(PasswordHasher hasher, BankRepository repository) {
        this.hasher = hasher;
        this.repository = repository;
        // Checked against when the username does not exist, so a login for an unknown user
        // takes about as long as a real one and gives nothing away.
        this.dummyHash = hasher.hash("not-a-real-password".toCharArray());
    }

    /** Registers a user and opens their account. The password array is wiped before returning. */
    public synchronized User register(String username, String fullName, char[] password) {
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
            String name = fullName.trim();
            String passwordHash = hasher.hash(password);

            return repository.inTransaction(() -> {
                if (repository.usernameExists(key)) {
                    throw new UsernameTakenException();
                }
                BankAccount account = new BankAccount(name, repository.nextAccountNumber());
                User user = new User(key, name, passwordHash, account);
                repository.insertUser(user);
                return user;
            });
        } finally {
            wipe(password);
        }
    }

    /**
     * Signs a user in. The same message is used for an unknown user and a wrong password.
     * After {@value #MAX_FAILED_LOGINS} failures in a row the user is locked until {@link #unlock}.
     * The password array is wiped before returning.
     */
    public synchronized User login(String username, char[] password) {
        try {
            String key = normalize(username);
            User user = key == null ? null : repository.findUser(key).orElse(null);
            if (user == null) {
                hasher.verify(password, dummyHash);
                throw new AuthenticationException(BAD_LOGIN);
            }
            if (user.isLocked()) {
                throw new AccountLockedException("This account is locked after too many failed attempts");
            }
            if (hasher.verify(password, user.getPasswordHash())) {
                if (user.getFailedLogins() > 0) {
                    user.resetFailedLogins();
                    repository.saveLoginState(user);
                }
                return user;
            }
            user.recordFailedLogin();
            boolean nowLocked = user.getFailedLogins() >= MAX_FAILED_LOGINS;
            if (nowLocked) {
                user.lock();
            }
            repository.saveLoginState(user);
            if (nowLocked) {
                throw new AccountLockedException("Too many failed attempts. This account is now locked");
            }
            throw new AuthenticationException(BAD_LOGIN);
        } finally {
            wipe(password);
        }
    }

    /** For a future admin feature: lets a locked user sign in again. */
    public synchronized void unlock(String username) {
        String key = normalize(username);
        User user = key == null ? null : repository.findUser(key).orElse(null);
        if (user == null) {
            throw new IllegalArgumentException("Unknown user");
        }
        user.unlock();
        repository.saveLoginState(user);
    }

    /** A snapshot of the user as stored right now, or null. */
    public synchronized User getUser(String username) {
        String key = normalize(username);
        return key == null ? null : repository.findUser(key).orElse(null);
    }

    /** A snapshot of the account as stored right now, or null. */
    public synchronized BankAccount findAccount(String accountNumber) {
        return repository.findAccount(accountNumber).orElse(null);
    }

    public synchronized void deposit(String accountNumber, BigDecimal amount) {
        repository.runInTransaction(() -> {
            BankAccount account = requireAccount(accountNumber);
            account.deposit(amount);
            save(account);
        });
    }

    public synchronized void withdraw(String accountNumber, BigDecimal amount) {
        repository.runInTransaction(() -> {
            BankAccount account = requireAccount(accountNumber);
            account.withdraw(amount);
            save(account);
        });
    }

    /**
     * Moves money between two accounts as one all-or-nothing transaction:
     * if anything fails, neither balance changes.
     */
    public synchronized void transfer(String fromAccountNumber, String toAccountNumber, BigDecimal amount) {
        repository.runInTransaction(() -> {
            BankAccount from = requireAccount(fromAccountNumber);
            BankAccount to = requireAccount(toAccountNumber);
            if (from.getCustomerId().equals(to.getCustomerId())) {
                throw new IllegalArgumentException("You cannot transfer to the same account");
            }
            from.sendTransfer(amount, to.getCustomerId()); // validates amount and funds first
            to.receiveTransfer(amount, from.getCustomerId());
            save(from);
            save(to);
        });
    }

    /** Stores the newest transaction of an account that was just changed. */
    private void save(BankAccount account) {
        Transaction latest = account.getPreviousTransaction().orElseThrow();
        repository.applyChange(account.getCustomerId(), latest);
    }

    private BankAccount requireAccount(String accountNumber) {
        return repository.findAccount(accountNumber).orElseThrow(
            () -> new AccountNotFoundException(accountNumber));
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

    private static String normalize(String username) {
        return username == null ? null : username.trim().toLowerCase();
    }

    private static void wipe(char[] password) {
        if (password != null) {
            Arrays.fill(password, '\0');
        }
    }
}
