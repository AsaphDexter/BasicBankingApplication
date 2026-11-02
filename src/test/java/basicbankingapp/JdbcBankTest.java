package basicbankingapp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Runs the shared bank tests against a real H2 database, plus checks that only
 * make sense with a database: saving across restarts, rollback and database-level rules.
 */
class JdbcBankTest extends AbstractBankTest {

    @Override
    protected BankRepository createRepository() {
        return JdbcBankRepository.inMemory();
    }

    private Connection rawConnection() throws SQLException {
        return DriverManager.getConnection(((JdbcBankRepository) repository).getUrl(), "sa", "");
    }

    // ---------- data survives a restart ----------

    @Test
    void usersBalancesAndHistorySurviveRestartingTheApp() throws IOException {
        Path dir = Files.createTempDirectory("bba-test");
        try {
            Path dbFile = dir.resolve("bank");
            String aliceNumber;
            String bobNumber;
            try (JdbcBankRepository first = JdbcBankRepository.file(dbFile)) {
                Bank bank1 = new Bank(FAST_HASHER, first);
                User alice = bank1.register("alice", "Alice Doe", pw("Passw0rd1"));
                User bob = bank1.register("bob", "Bob Roe", pw("Passw0rd1"));
                aliceNumber = acct(alice);
                bobNumber = acct(bob);
                bank1.deposit(aliceNumber, money("100"));
                bank1.transfer(aliceNumber, bobNumber, money("30.50"));
                bank1.withdraw(aliceNumber, money("10"));
            }

            try (JdbcBankRepository second = JdbcBankRepository.file(dbFile)) {
                Bank bank2 = new Bank(FAST_HASHER, second);
                User alice = bank2.login("alice", pw("Passw0rd1"));
                assertEquals(aliceNumber, acct(alice));
                assertEquals(money("59.50"), bank2.findAccount(aliceNumber).getBalance());
                assertEquals(money("30.50"), bank2.findAccount(bobNumber).getBalance());

                var history = bank2.findAccount(aliceNumber).getHistory();
                assertEquals(3, history.size());
                assertEquals(Transaction.Type.DEPOSIT, history.get(0).type());
                assertEquals(Transaction.Type.TRANSFER_OUT, history.get(1).type());
                assertEquals(bobNumber, history.get(1).counterparty());
                assertEquals(Transaction.Type.WITHDRAWAL, history.get(2).type());

                User carol = bank2.register("carol", "Carol Poe", pw("Passw0rd1"));
                assertNotEquals(aliceNumber, acct(carol));
                assertNotEquals(bobNumber, acct(carol));
            }
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void lockoutSurvivesRestartingTheApp() throws IOException {
        Path dir = Files.createTempDirectory("bba-test");
        try {
            Path dbFile = dir.resolve("bank");
            try (JdbcBankRepository first = JdbcBankRepository.file(dbFile)) {
                Bank bank1 = new Bank(FAST_HASHER, first);
                bank1.register("alice", "Alice Doe", pw("Passw0rd1"));
                for (int i = 0; i < Bank.MAX_FAILED_LOGINS; i++) {
                    assertThrows(AuthenticationException.class, () -> bank1.login("alice", pw("Wrong0000")));
                }
            }
            try (JdbcBankRepository second = JdbcBankRepository.file(dbFile)) {
                Bank bank2 = new Bank(FAST_HASHER, second);
                assertThrows(AccountLockedException.class, () -> bank2.login("alice", pw("Passw0rd1")));
                bank2.unlock("alice");
                assertNotNull(bank2.login("alice", pw("Passw0rd1")));
            }
        } finally {
            deleteRecursively(dir);
        }
    }

    // ---------- all-or-nothing transfers ----------

    @Test
    void transferIsRolledBackCompletelyWhenTheSecondWriteFails() {
        User alice = register("alice");
        User bob = register("bob");
        bank.deposit(acct(alice), money("100"));

        FailingRepository flaky = new FailingRepository(repository);
        Bank flakyBank = new Bank(FAST_HASHER, flaky);
        flaky.failOnApplyChangeNumber(2); // the first write succeeds, the second one breaks

        assertThrows(RepositoryException.class, () -> flakyBank.transfer(acct(alice), acct(bob), money("40")));

        assertEquals(money("100.00"), balanceOf(alice));
        assertEquals(0, balanceOf(bob).compareTo(BigDecimal.ZERO));
        assertEquals(1, stored(alice).getHistory().size());
        assertTrue(stored(bob).getHistory().isEmpty());
    }

    // ---------- rules enforced by the database itself ----------

    @Test
    void databaseRefusesANegativeBalanceEvenIfTheJavaChecksAreBypassed() throws SQLException {
        User alice = register("alice");
        try (Connection c = rawConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE accounts SET balance = -1 WHERE account_number = ?")) {
            ps.setString(1, acct(alice));
            assertThrows(SQLException.class, ps::executeUpdate);
        }
        assertEquals(0, balanceOf(alice).compareTo(BigDecimal.ZERO));
    }

    @Test
    void databaseEnforcesUniqueUsernames() throws SQLException {
        register("alice");
        try (Connection c = rawConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO users (username, full_name, password_hash) VALUES ('alice', 'Imposter', 'x')")) {
            assertThrows(SQLException.class, ps::executeUpdate);
        }
    }

    @Test
    void databaseRefusesTransactionsForAccountsThatDoNotExist() throws SQLException {
        try (Connection c = rawConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO transactions (account_number, type, amount, balance_after, created_at) "
                     + "VALUES ('0000000000', 'DEPOSIT', 5, 5, CURRENT_TIMESTAMP)")) {
            assertThrows(SQLException.class, ps::executeUpdate);
        }
    }

    @Test
    void passwordsAreStoredOnlyAsHashes() throws SQLException {
        register("alice");
        try (Connection c = rawConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT password_hash FROM users WHERE username = 'alice'")) {
            assertTrue(rs.next());
            String stored = rs.getString(1);
            assertTrue(stored.startsWith("pbkdf2_sha256$"));
            assertFalse(stored.contains("Passw0rd1"));
        }
    }

    // ---------- injection ----------

    @Test
    void sqlInjectionStyleInputIsTreatedAsPlainText() {
        register("alice");
        assertNull(bank.getUser("alice' OR '1'='1"));
        assertThrows(AuthenticationException.class, () -> bank.login("alice' --", pw("Passw0rd1")));

        String nastyName = "Robert'); DROP TABLE users;--";
        User robert = bank.register("robert", nastyName, pw("Passw0rd1"));
        assertEquals(nastyName, bank.getUser("robert").getFullName());
        assertEquals(nastyName, stored(robert).getCustomerName());
        assertNotNull(bank.getUser("alice")); // the table is still there
    }

    // ---------- helpers ----------

    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    /** Wraps a real repository and makes one chosen {@code applyChange} call fail. */
    private static final class FailingRepository implements BankRepository {
        private final BankRepository delegate;
        private int callsBeforeFailure = -1;
        private int calls;

        FailingRepository(BankRepository delegate) {
            this.delegate = delegate;
        }

        void failOnApplyChangeNumber(int number) {
            this.callsBeforeFailure = number;
            this.calls = 0;
        }

        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return delegate.inTransaction(work);
        }

        @Override
        public boolean usernameExists(String username) {
            return delegate.usernameExists(username);
        }

        @Override
        public void insertUser(User user) {
            delegate.insertUser(user);
        }

        @Override
        public Optional<User> findUser(String username) {
            return delegate.findUser(username);
        }

        @Override
        public Optional<BankAccount> findAccount(String accountNumber) {
            return delegate.findAccount(accountNumber);
        }

        @Override
        public void saveLoginState(User user) {
            delegate.saveLoginState(user);
        }

        @Override
        public void applyChange(String accountNumber, Transaction transaction) {
            calls++;
            if (calls == callsBeforeFailure) {
                throw new RepositoryException("Simulated database failure");
            }
            delegate.applyChange(accountNumber, transaction);
        }

        @Override
        public String nextAccountNumber() {
            return delegate.nextAccountNumber();
        }

        @Override
        public void close() {
            // The wrapped repository is closed by the test's tearDown.
        }
    }
}
