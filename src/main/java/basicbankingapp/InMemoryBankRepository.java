package basicbankingapp;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** Keeps everything in memory. Fast and simple, but all data is lost when the program ends. */
public class InMemoryBankRepository implements BankRepository {

    private final Map<String, User> usersByUsername = new HashMap<>();
    private final Map<String, BankAccount> accountsByNumber = new HashMap<>();
    private long nextAccountNumber = 1_000_000_001L;

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        // Objects are changed in place and the rules check everything before changing anything,
        // so there is nothing to roll back here.
        return work.get();
    }

    @Override
    public boolean usernameExists(String username) {
        return usersByUsername.containsKey(username);
    }

    @Override
    public void insertUser(User user) {
        if (usersByUsername.containsKey(user.getUsername())) {
            throw new UsernameTakenException();
        }
        usersByUsername.put(user.getUsername(), user);
        accountsByNumber.put(user.getAccount().getCustomerId(), user.getAccount());
    }

    @Override
    public Optional<User> findUser(String username) {
        return Optional.ofNullable(usersByUsername.get(username));
    }

    @Override
    public Optional<BankAccount> findAccount(String accountNumber) {
        return Optional.ofNullable(accountsByNumber.get(accountNumber));
    }

    @Override
    public void saveLoginState(User user) {
        // The stored object is the one that was changed.
    }

    @Override
    public void applyChange(String accountNumber, Transaction transaction) {
        // The stored account was already changed in place.
    }

    @Override
    public String nextAccountNumber() {
        return String.valueOf(nextAccountNumber++);
    }

    @Override
    public void close() {
        // Nothing to release.
    }
}
