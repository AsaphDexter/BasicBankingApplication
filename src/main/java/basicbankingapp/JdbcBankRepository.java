package basicbankingapp;

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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Stores everything in an embedded H2 SQL database through JDBC.
 * <ul>
 *   <li>Every value goes in through a {@link PreparedStatement}, so SQL injection is not possible.</li>
 *   <li>The database enforces key rules itself (unique usernames, no negative balances,
 *       transactions must belong to a real account), even if the Java checks were bypassed.</li>
 *   <li>One connection is shared and all methods are synchronized, so one app instance at a time.</li>
 * </ul>
 */
public final class JdbcBankRepository implements BankRepository {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String DB_USER = "sa";

    private final String url;
    private final Connection connection;
    private boolean inTransaction;

    /** A throwaway database that disappears when the repository is closed (used by tests and --memory demos). */
    public static JdbcBankRepository inMemory() {
        return new JdbcBankRepository("jdbc:h2:mem:bank-" + UUID.randomUUID());
    }

    /** A database saved in files at the given path (H2 adds its own file extension). */
    public static JdbcBankRepository file(Path path) {
        try {
            Path absolute = path.toAbsolutePath();
            if (absolute.getParent() != null) {
                Files.createDirectories(absolute.getParent());
            }
            return new JdbcBankRepository("jdbc:h2:file:" + absolute.toString().replace('\\', '/'));
        } catch (IOException e) {
            throw new RepositoryException("Could not create the database folder", e);
        }
    }

    JdbcBankRepository(String url) {
        this.url = url;
        try {
            this.connection = DriverManager.getConnection(url, DB_USER, "");
            createSchema();
        } catch (SQLException e) {
            throw new RepositoryException(
                "Could not open the database (is the H2 library on the classpath?): " + e.getMessage(), e);
        }
    }

    String getUrl() {
        return url;
    }

    // ---------- transactions ----------

    @Override
    public synchronized <T> T inTransaction(Supplier<T> work) {
        if (inTransaction) {
            return work.get(); // join the transaction that is already open
        }
        try {
            connection.setAutoCommit(false);
            inTransaction = true;
            T result = work.get();
            connection.commit();
            return result;
        } catch (SQLException e) {
            rollbackQuietly();
            throw new RepositoryException("Database error: " + e.getMessage(), e);
        } catch (RuntimeException | Error e) {
            rollbackQuietly();
            throw e;
        } finally {
            inTransaction = false;
            restoreAutoCommit();
        }
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The original failure is the one worth reporting.
        }
    }

    private void restoreAutoCommit() {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ignored) {
            // Nothing useful can be done here.
        }
    }

    // ---------- users ----------

    @Override
    public synchronized boolean usernameExists(String username) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM users WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RepositoryException("Could not check the username", e);
        }
    }

    @Override
    public synchronized void insertUser(User user) {
        runInTransaction(() -> {
            BankAccount account = user.getAccount();
            try (PreparedStatement insertUser = connection.prepareStatement(
                     "INSERT INTO users (username, full_name, password_hash, failed_logins, locked) VALUES (?, ?, ?, ?, ?)");
                 PreparedStatement insertAccount = connection.prepareStatement(
                     "INSERT INTO accounts (account_number, username, customer_name, balance) VALUES (?, ?, ?, ?)")) {
                insertUser.setString(1, user.getUsername());
                insertUser.setString(2, user.getFullName());
                insertUser.setString(3, user.getPasswordHash());
                insertUser.setInt(4, user.getFailedLogins());
                insertUser.setBoolean(5, user.isLocked());
                insertUser.executeUpdate();

                insertAccount.setString(1, account.getCustomerId());
                insertAccount.setString(2, user.getUsername());
                insertAccount.setString(3, account.getCustomerName());
                insertAccount.setBigDecimal(4, account.getBalance());
                insertAccount.executeUpdate();
            } catch (SQLException e) {
                if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                    throw new RegistrationException("That username is already taken");
                }
                throw new RepositoryException("Could not save the new user", e);
            }
        });
    }

    @Override
    public synchronized Optional<User> findUser(String username) {
        String sql = "SELECT u.username, u.full_name, u.password_hash, u.failed_logins, u.locked, "
            + "a.account_number, a.customer_name, a.balance "
            + "FROM users u JOIN accounts a ON a.username = u.username WHERE u.username = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String accountNumber = rs.getString("account_number");
                BankAccount account = BankAccount.restore(rs.getString("customer_name"), accountNumber,
                    rs.getBigDecimal("balance"), loadHistory(accountNumber));
                return Optional.of(new User(rs.getString("username"), rs.getString("full_name"),
                    rs.getString("password_hash"), account, rs.getInt("failed_logins"), rs.getBoolean("locked")));
            }
        } catch (SQLException e) {
            throw new RepositoryException("Could not load the user", e);
        }
    }

    @Override
    public synchronized void saveLoginState(User user) {
        try (PreparedStatement ps = connection.prepareStatement(
                 "UPDATE users SET failed_logins = ?, locked = ? WHERE username = ?")) {
            ps.setInt(1, user.getFailedLogins());
            ps.setBoolean(2, user.isLocked());
            ps.setString(3, user.getUsername());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RepositoryException("Could not save the login state", e);
        }
    }

    // ---------- accounts ----------

    @Override
    public synchronized Optional<BankAccount> findAccount(String accountNumber) {
        try (PreparedStatement ps = connection.prepareStatement(
                 "SELECT account_number, customer_name, balance FROM accounts WHERE account_number = ?")) {
            ps.setString(1, accountNumber);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(BankAccount.restore(rs.getString("customer_name"),
                    rs.getString("account_number"), rs.getBigDecimal("balance"), loadHistory(accountNumber)));
            }
        } catch (SQLException e) {
            throw new RepositoryException("Could not load the account", e);
        }
    }

    @Override
    public synchronized void applyChange(String accountNumber, Transaction transaction) {
        runInTransaction(() -> {
            try (PreparedStatement update = connection.prepareStatement(
                     "UPDATE accounts SET balance = ? WHERE account_number = ?");
                 PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO transactions (account_number, type, amount, balance_after, created_at, counterparty) "
                         + "VALUES (?, ?, ?, ?, ?, ?)")) {
                update.setBigDecimal(1, transaction.balanceAfter());
                update.setString(2, accountNumber);
                if (update.executeUpdate() != 1) {
                    throw new RepositoryException("Account not found: " + accountNumber);
                }
                insert.setString(1, accountNumber);
                insert.setString(2, transaction.type().name());
                insert.setBigDecimal(3, transaction.amount());
                insert.setBigDecimal(4, transaction.balanceAfter());
                insert.setObject(5, transaction.timestamp());
                insert.setString(6, transaction.counterparty());
                insert.executeUpdate();
            } catch (SQLException e) {
                throw new RepositoryException("Could not save the transaction", e);
            }
        });
    }

    @Override
    public synchronized String nextAccountNumber() {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT NEXT VALUE FOR account_number_seq")) {
            rs.next();
            return String.valueOf(rs.getLong(1));
        } catch (SQLException e) {
            throw new RepositoryException("Could not create an account number", e);
        }
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new RepositoryException("Could not close the database", e);
        }
    }

    // ---------- helpers ----------

    private List<Transaction> loadHistory(String accountNumber) throws SQLException {
        List<Transaction> history = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                 "SELECT type, amount, balance_after, created_at, counterparty "
                     + "FROM transactions WHERE account_number = ? ORDER BY id")) {
            ps.setString(1, accountNumber);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    BigDecimal amount = rs.getBigDecimal("amount");
                    BigDecimal balanceAfter = rs.getBigDecimal("balance_after");
                    history.add(new Transaction(Transaction.Type.valueOf(rs.getString("type")), amount,
                        balanceAfter, rs.getObject("created_at", LocalDateTime.class), rs.getString("counterparty")));
                }
            }
        }
        return history;
    }

    private void createSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS users ("
                + "username VARCHAR(20) PRIMARY KEY, "
                + "full_name VARCHAR(100) NOT NULL, "
                + "password_hash VARCHAR(255) NOT NULL, "
                + "failed_logins INT DEFAULT 0 NOT NULL CHECK (failed_logins >= 0), "
                + "locked BOOLEAN DEFAULT FALSE NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS accounts ("
                + "account_number VARCHAR(20) PRIMARY KEY, "
                + "username VARCHAR(20) NOT NULL UNIQUE REFERENCES users(username), "
                + "customer_name VARCHAR(100) NOT NULL, "
                + "balance DECIMAL(19, 2) NOT NULL CHECK (balance >= 0))");
            st.execute("CREATE TABLE IF NOT EXISTS transactions ("
                + "id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, "
                + "account_number VARCHAR(20) NOT NULL REFERENCES accounts(account_number), "
                + "type VARCHAR(20) NOT NULL, "
                + "amount DECIMAL(19, 2) NOT NULL CHECK (amount > 0), "
                + "balance_after DECIMAL(19, 2) NOT NULL CHECK (balance_after >= 0), "
                + "created_at TIMESTAMP NOT NULL, "
                + "counterparty VARCHAR(20))");
            st.execute("CREATE INDEX IF NOT EXISTS idx_transactions_account ON transactions(account_number, id)");
            st.execute("CREATE SEQUENCE IF NOT EXISTS account_number_seq START WITH 1000000001");
        }
    }
}
