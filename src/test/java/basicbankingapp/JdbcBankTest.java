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
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
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

    @Test
    void timedLockAndAuditLogSurviveRestartingTheApp() throws IOException {
        Path dir = Files.createTempDirectory("bba-test");
        try {
            Path dbFile = dir.resolve("bank");
            try (JdbcBankRepository first = JdbcBankRepository.file(dbFile)) {
                Bank bank1 = new Bank(FAST_HASHER, first, clock, LOCKOUT);
                bank1.register("alice", "Alice Doe", pw("Passw0rd1"));
                for (int i = 0; i < Bank.MAX_FAILED_LOGINS; i++) {
                    assertThrows(AuthenticationException.class, () -> bank1.login("alice", pw("Wrong0000")));
                }
            }
            try (JdbcBankRepository second = JdbcBankRepository.file(dbFile)) {
                Bank bank2 = new Bank(FAST_HASHER, second, clock, LOCKOUT);
                assertThrows(AccountLockedException.class, () -> bank2.login("alice", pw("Passw0rd1")));
                assertEquals(clock.instant().plus(LOCKOUT), bank2.getUser("alice").getLockedUntil());
                clock.advance(LOCKOUT);
                assertNotNull(bank2.login("alice", pw("Passw0rd1")));
                assertTrue(bank2.auditLog("alice", 100).size() >= 7);
            }
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void changedPasswordSurvivesRestartingTheApp() throws IOException {
        Path dir = Files.createTempDirectory("bba-test");
        try {
            Path dbFile = dir.resolve("bank");
            try (JdbcBankRepository first = JdbcBankRepository.file(dbFile)) {
                Bank bank1 = new Bank(FAST_HASHER, first);
                bank1.register("alice", "Alice Doe", pw("Passw0rd1"));
                bank1.changePassword("alice", pw("Passw0rd1"), pw("N3wPassword"), null);
            }
            try (JdbcBankRepository second = JdbcBankRepository.file(dbFile)) {
                Bank bank2 = new Bank(FAST_HASHER, second);
                assertThrows(AuthenticationException.class, () -> bank2.login("alice", pw("Passw0rd1")));
                assertNotNull(bank2.login("alice", pw("N3wPassword")));
            }
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void aDatabaseCreatedBeforeTimedLockoutsIsUpgradedInPlace() throws Exception {
        Path dir = Files.createTempDirectory("bba-test");
        try {
            String url = "jdbc:h2:file:" + dir.resolve("old").toAbsolutePath().toString().replace('\\', '/');
            try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement st = c.createStatement()) {
                // the users table exactly as Stage 2 and 3 created it: no locked_until column, no audit table
                st.execute("CREATE TABLE users (username VARCHAR(20) PRIMARY KEY, full_name VARCHAR(100) NOT NULL, "
                    + "password_hash VARCHAR(255) NOT NULL, failed_logins INT DEFAULT 0 NOT NULL CHECK (failed_logins >= 0), "
                    + "locked BOOLEAN DEFAULT FALSE NOT NULL)");
                st.execute("CREATE TABLE accounts (account_number VARCHAR(20) PRIMARY KEY, "
                    + "username VARCHAR(20) NOT NULL UNIQUE REFERENCES users(username), "
                    + "customer_name VARCHAR(100) NOT NULL, balance DECIMAL(19, 2) NOT NULL CHECK (balance >= 0))");
                st.execute("INSERT INTO users (username, full_name, password_hash) VALUES ('olduser', 'Old User', "
                    + "'" + FAST_HASHER.hash(pw("Passw0rd1")) + "')");
                st.execute("INSERT INTO accounts VALUES ('1000000001', 'olduser', 'Old User', 12.34)");
            }
            try (JdbcBankRepository upgraded = new JdbcBankRepository(url)) {
                Bank bank2 = new Bank(FAST_HASHER, upgraded);
                User user = bank2.login("olduser", pw("Passw0rd1"));
                assertEquals(money("12.34"), bank2.findAccount(acct(user)).getBalance());
                assertEquals(1, bank2.auditLog("olduser", 10).size()); // the audit table now exists
            }
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void aRetryAfterRestartingTheAppStillGetsTheStoredAnswer() throws IOException {
        Path dir = Files.createTempDirectory("bba-test");
        try {
            Path dbFile = dir.resolve("bank");
            String accountNumber;
            try (JdbcBankRepository first = JdbcBankRepository.file(dbFile)) {
                Bank bank1 = new Bank(FAST_HASHER, first, clock, LOCKOUT);
                User alice = bank1.register("alice", "Alice Doe", pw("Passw0rd1"));
                accountNumber = acct(alice);
                bank1.executeOnce("alice", "retry-key-0001", "print", () -> {
                    bank1.deposit(accountNumber, money("10"));
                    return "{\"balance\":10.00}";
                });
            }
            try (JdbcBankRepository second = JdbcBankRepository.file(dbFile)) {
                Bank bank2 = new Bank(FAST_HASHER, second, clock, LOCKOUT);
                Bank.OnceResult retry = bank2.executeOnce("alice", "retry-key-0001", "print", () -> {
                    bank2.deposit(accountNumber, money("10"));
                    return "should never run";
                });
                assertTrue(retry.replayed());
                assertEquals("{\"balance\":10.00}", retry.result());
                assertEquals(money("10.00"), bank2.findAccount(accountNumber).getBalance());
            }
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void moneyAndTheIdempotencyRecordAreSavedTogetherOrNotAtAll() {
        User alice = register("alice");
        // The action moves money and then fails: the deposit must be undone and the key must stay free.
        assertThrows(IllegalStateException.class, () -> bank.executeOnce("alice", "retry-key-0001", "print", () -> {
            bank.deposit(acct(alice), money("50"));
            throw new IllegalStateException("simulated crash after the money moved");
        }));
        assertEquals(0, balanceOf(alice).compareTo(BigDecimal.ZERO));
        assertTrue(stored(alice).getHistory().isEmpty());
        assertFalse(repository.findIdempotencyRecord("alice", "retry-key-0001").isPresent());

        Bank.OnceResult retry = bank.executeOnce("alice", "retry-key-0001", "print", () -> {
            bank.deposit(acct(alice), money("50"));
            return "ok";
        });
        assertFalse(retry.replayed());
        assertEquals(money("50.00"), balanceOf(alice));
    }

    @Test
    void expiredRecordsAreCleanedOutOfTheDatabase() {
        register("alice");
        bank.executeOnce("alice", "retry-key-0001", "print", () -> "first");
        clock.advance(Bank.IDEMPOTENCY_TTL.plusSeconds(1));
        bank.executeOnce("alice", "retry-key-0002", "print", () -> "second"); // storing this clears the expired one
        assertFalse(repository.findIdempotencyRecord("alice", "retry-key-0001").isPresent());
        assertTrue(repository.findIdempotencyRecord("alice", "retry-key-0002").isPresent());
    }

    @Test
    void theDatabaseKeepsOneRecordPerUserAndKey() throws SQLException {
        register("alice");
        bank.executeOnce("alice", "retry-key-0001", "print", () -> "x");
        try (Connection c = rawConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO idempotency_keys VALUES ('alice', 'retry-key-0001', 'p', 'r', CURRENT_TIMESTAMP)")) {
            assertThrows(SQLException.class, ps::executeUpdate);
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
        public void updatePasswordHash(String username, String newPasswordHash) {
            delegate.updatePasswordHash(username, newPasswordHash);
        }

        @Override
        public void appendAudit(AuditEvent event) {
            delegate.appendAudit(event);
        }

        @Override
        public List<AuditEvent> recentAudit(String username, int limit) {
            return delegate.recentAudit(username, limit);
        }

        @Override
        public Optional<IdempotencyRecord> findIdempotencyRecord(String username, String key) {
            return delegate.findIdempotencyRecord(username, key);
        }

        @Override
        public void saveIdempotencyRecord(IdempotencyRecord record) {
            delegate.saveIdempotencyRecord(record);
        }

        @Override
        public void deleteIdempotencyRecordsBefore(java.time.Instant cutoff) {
            delegate.deleteIdempotencyRecordsBefore(cutoff);
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
