package basicbankingapp;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Behaviour every storage option must provide. {@link InMemoryBankTest} and
 * {@link JdbcBankTest} run exactly these tests against their own repository.
 */
abstract class AbstractBankTest {

    // A low iteration count keeps the tests fast; the real app uses the much higher default.
    protected static final PasswordHasher FAST_HASHER = new PasswordHasher(1_000);

    protected BankRepository repository;
    protected Bank bank;

    protected abstract BankRepository createRepository();

    @BeforeEach
    void setUp() {
        repository = createRepository();
        bank = new Bank(FAST_HASHER, repository);
    }

    @AfterEach
    void tearDown() {
        repository.close();
    }

    // ---------- helpers ----------

    protected static char[] pw(String value) {
        return value.toCharArray();
    }

    protected static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    protected User register(String username) {
        return bank.register(username, "Test " + username, pw("Passw0rd1"));
    }

    protected static String acct(User user) {
        return user.getAccount().getCustomerId();
    }

    /** Always reads the current stored state, never a stale object. */
    protected BankAccount stored(User user) {
        return bank.findAccount(acct(user));
    }

    protected BigDecimal balanceOf(User user) {
        return stored(user).getBalance();
    }

    // ---------- registration ----------

    @Test
    void registrationCreatesUserWithEmptyAccount() {
        User user = bank.register("alice", "Alice Doe", pw("Passw0rd1"));
        assertEquals("alice", user.getUsername());
        assertEquals("Alice Doe", user.getFullName());
        assertEquals(0, balanceOf(user).compareTo(BigDecimal.ZERO));
        assertEquals("alice", bank.getUser("alice").getUsername());
        assertEquals("Alice Doe", bank.findAccount(acct(user)).getCustomerName());
    }

    @Test
    void eachUserGetsADifferentAccountNumber() {
        User alice = register("alice");
        User bob = register("bob");
        assertNotEquals(acct(alice), acct(bob));
    }

    @Test
    void duplicateUsernameIsRejectedIgnoringCase() {
        register("alice");
        assertThrows(RegistrationException.class, () -> bank.register("ALICE", "Other Person", pw("Passw0rd1")));
    }

    @Test
    void invalidUsernamesAreRejected() {
        assertThrows(RegistrationException.class, () -> bank.register("ab", "X Y", pw("Passw0rd1")));
        assertThrows(RegistrationException.class, () -> bank.register("has space", "X Y", pw("Passw0rd1")));
        assertThrows(RegistrationException.class, () -> bank.register("bad!name", "X Y", pw("Passw0rd1")));
        assertThrows(RegistrationException.class, () -> bank.register("a".repeat(21), "X Y", pw("Passw0rd1")));
        assertThrows(RegistrationException.class, () -> bank.register(null, "X Y", pw("Passw0rd1")));
    }

    @Test
    void blankFullNameIsRejected() {
        assertThrows(RegistrationException.class, () -> bank.register("alice", "  ", pw("Passw0rd1")));
        assertThrows(RegistrationException.class, () -> bank.register("alice", null, pw("Passw0rd1")));
    }

    @Test
    void weakPasswordsAreRejected() {
        assertThrows(RegistrationException.class, () -> bank.register("alice", "A B", pw("short1")));
        assertThrows(RegistrationException.class, () -> bank.register("alice", "A B", pw("onlyletters")));
        assertThrows(RegistrationException.class, () -> bank.register("alice", "A B", pw("12345678")));
        assertThrows(RegistrationException.class, () -> bank.register("alice", "A B", null));
        assertThrows(RegistrationException.class, () -> bank.register("alice123", "A B", pw("ALICE123")));
        assertNull(bank.getUser("alice"));
    }

    @Test
    void passwordIsNotStoredInPlainText() {
        User user = bank.register("alice", "Alice Doe", pw("Passw0rd1"));
        assertFalse(user.getPasswordHash().contains("Passw0rd1"));
        assertFalse(bank.getUser("alice").getPasswordHash().contains("Passw0rd1"));
    }

    @Test
    void registrationWipesThePasswordArray() {
        char[] password = pw("Passw0rd1");
        bank.register("alice", "Alice Doe", password);
        for (char c : password) {
            assertEquals('\0', c);
        }
    }

    // ---------- login ----------

    @Test
    void correctCredentialsLogIn() {
        register("alice");
        assertEquals("alice", bank.login("alice", pw("Passw0rd1")).getUsername());
    }

    @Test
    void loggedInUserOwnsTheSameAccount() {
        User alice = register("alice");
        assertEquals(acct(alice), acct(bank.login("alice", pw("Passw0rd1"))));
    }

    @Test
    void usernameIsNotCaseSensitiveAtLogin() {
        register("alice");
        assertEquals("alice", bank.login("  ALICE ", pw("Passw0rd1")).getUsername());
    }

    @Test
    void passwordIsCaseSensitive() {
        register("alice");
        assertThrows(AuthenticationException.class, () -> bank.login("alice", pw("passw0rd1")));
    }

    @Test
    void unknownUserAndWrongPasswordGiveTheSameMessage() {
        register("alice");
        AuthenticationException wrongPassword =
            assertThrows(AuthenticationException.class, () -> bank.login("alice", pw("Wrong1234")));
        AuthenticationException unknownUser =
            assertThrows(AuthenticationException.class, () -> bank.login("nobody", pw("Wrong1234")));
        assertEquals(wrongPassword.getMessage(), unknownUser.getMessage());
    }

    @Test
    void nullUsernameOrPasswordIsRejectedWithoutCrashing() {
        register("alice");
        assertThrows(AuthenticationException.class, () -> bank.login(null, pw("Passw0rd1")));
        assertThrows(AuthenticationException.class, () -> bank.login("alice", null));
    }

    @Test
    void threeFailedLoginsLockTheAccountEvenForTheCorrectPassword() {
        register("alice");
        assertThrows(AuthenticationException.class, () -> bank.login("alice", pw("Wrong1111")));
        assertThrows(AuthenticationException.class, () -> bank.login("alice", pw("Wrong2222")));
        assertThrows(AccountLockedException.class, () -> bank.login("alice", pw("Wrong3333")));
        assertTrue(bank.getUser("alice").isLocked());
        assertThrows(AccountLockedException.class, () -> bank.login("alice", pw("Passw0rd1")));
    }

    @Test
    void successfulLoginResetsTheFailureCount() {
        register("alice");
        assertThrows(AuthenticationException.class, () -> bank.login("alice", pw("Wrong1111")));
        assertThrows(AuthenticationException.class, () -> bank.login("alice", pw("Wrong2222")));
        assertEquals(2, bank.getUser("alice").getFailedLogins());
        bank.login("alice", pw("Passw0rd1"));
        assertEquals(0, bank.getUser("alice").getFailedLogins());
        assertThrows(AuthenticationException.class, () -> bank.login("alice", pw("Wrong3333")));
        assertThrows(AuthenticationException.class, () -> bank.login("alice", pw("Wrong4444")));
        assertFalse(bank.getUser("alice").isLocked());
    }

    @Test
    void unlockLetsALockedUserSignInAgain() {
        register("alice");
        for (int i = 0; i < Bank.MAX_FAILED_LOGINS; i++) {
            assertThrows(AuthenticationException.class, () -> bank.login("alice", pw("Wrong0000")));
        }
        bank.unlock("alice");
        assertNotNull(bank.login("alice", pw("Passw0rd1")));
    }

    @Test
    void lockingOneUserDoesNotAffectAnother() {
        register("alice");
        register("bob");
        for (int i = 0; i < Bank.MAX_FAILED_LOGINS; i++) {
            assertThrows(AuthenticationException.class, () -> bank.login("alice", pw("Wrong0000")));
        }
        assertNotNull(bank.login("bob", pw("Passw0rd1")));
    }

    // ---------- deposit and withdraw ----------

    @Test
    void depositAndWithdrawUpdateBalanceAndHistory() {
        User alice = register("alice");
        bank.deposit(acct(alice), money("100.50"));
        bank.withdraw(acct(alice), money("40.25"));

        assertEquals(money("60.25"), balanceOf(alice));
        var history = stored(alice).getHistory();
        assertEquals(2, history.size());
        assertEquals(Transaction.Type.DEPOSIT, history.get(0).type());
        assertEquals(money("100.50"), history.get(0).amount());
        assertEquals(Transaction.Type.WITHDRAWAL, history.get(1).type());
        assertEquals(money("60.25"), history.get(1).balanceAfter());
    }

    @Test
    void withdrawalAboveBalanceIsRejectedAndNothingChanges() {
        User alice = register("alice");
        bank.deposit(acct(alice), money("50"));
        assertThrows(InsufficientFundsException.class, () -> bank.withdraw(acct(alice), money("50.01")));
        assertEquals(money("50.00"), balanceOf(alice));
        assertEquals(1, stored(alice).getHistory().size());
    }

    @Test
    void invalidAmountsAreRejectedByDepositAndWithdraw() {
        User alice = register("alice");
        assertThrows(IllegalArgumentException.class, () -> bank.deposit(acct(alice), BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> bank.deposit(acct(alice), money("-5")));
        assertThrows(IllegalArgumentException.class, () -> bank.deposit(acct(alice), money("1.001")));
        assertThrows(IllegalArgumentException.class, () -> bank.withdraw(acct(alice), null));
        assertTrue(stored(alice).getHistory().isEmpty());
    }

    @Test
    void depositToAnUnknownAccountIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> bank.deposit("9999999999", money("5")));
        assertThrows(IllegalArgumentException.class, () -> bank.withdraw("9999999999", money("5")));
    }

    // ---------- transfers ----------

    @Test
    void transferMovesMoneyAndRecordsBothSides() {
        User alice = register("alice");
        User bob = register("bob");
        bank.deposit(acct(alice), money("100"));

        bank.transfer(acct(alice), acct(bob), money("40.25"));

        assertEquals(money("59.75"), balanceOf(alice));
        assertEquals(money("40.25"), balanceOf(bob));

        Transaction out = stored(alice).getPreviousTransaction().orElseThrow();
        assertEquals(Transaction.Type.TRANSFER_OUT, out.type());
        assertEquals(acct(bob), out.counterparty());
        assertEquals(money("59.75"), out.balanceAfter());

        Transaction in = stored(bob).getPreviousTransaction().orElseThrow();
        assertEquals(Transaction.Type.TRANSFER_IN, in.type());
        assertEquals(acct(alice), in.counterparty());
        assertEquals(money("40.25"), in.balanceAfter());
    }

    @Test
    void transferOfTheWholeBalanceIsAllowed() {
        User alice = register("alice");
        User bob = register("bob");
        bank.deposit(acct(alice), money("25"));
        bank.transfer(acct(alice), acct(bob), money("25"));
        assertEquals(0, balanceOf(alice).compareTo(BigDecimal.ZERO));
        assertEquals(money("25.00"), balanceOf(bob));
    }

    @Test
    void transferWithInsufficientFundsChangesNothing() {
        User alice = register("alice");
        User bob = register("bob");
        bank.deposit(acct(alice), money("10"));

        assertThrows(InsufficientFundsException.class, () -> bank.transfer(acct(alice), acct(bob), money("10.01")));

        assertEquals(money("10.00"), balanceOf(alice));
        assertEquals(0, balanceOf(bob).compareTo(BigDecimal.ZERO));
        assertEquals(1, stored(alice).getHistory().size());
        assertTrue(stored(bob).getHistory().isEmpty());
    }

    @Test
    void transferToAnUnknownAccountIsRejectedAndNothingChanges() {
        User alice = register("alice");
        bank.deposit(acct(alice), money("50"));
        assertThrows(IllegalArgumentException.class, () -> bank.transfer(acct(alice), "9999999999", money("5")));
        assertEquals(money("50.00"), balanceOf(alice));
    }

    @Test
    void transferFromAnUnknownAccountIsRejected() {
        User bob = register("bob");
        assertThrows(IllegalArgumentException.class, () -> bank.transfer("9999999999", acct(bob), money("5")));
        assertEquals(0, balanceOf(bob).compareTo(BigDecimal.ZERO));
    }

    @Test
    void transferToTheSameAccountIsRejected() {
        User alice = register("alice");
        bank.deposit(acct(alice), money("50"));
        assertThrows(IllegalArgumentException.class, () -> bank.transfer(acct(alice), acct(alice), money("5")));
        assertEquals(money("50.00"), balanceOf(alice));
        assertEquals(1, stored(alice).getHistory().size());
    }

    @Test
    void zeroNegativeAndTooPreciseTransferAmountsAreRejected() {
        User alice = register("alice");
        User bob = register("bob");
        bank.deposit(acct(alice), money("100"));
        assertThrows(IllegalArgumentException.class, () -> bank.transfer(acct(alice), acct(bob), BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> bank.transfer(acct(alice), acct(bob), money("-5")));
        assertThrows(IllegalArgumentException.class, () -> bank.transfer(acct(alice), acct(bob), money("1.005")));
        assertThrows(IllegalArgumentException.class, () -> bank.transfer(acct(alice), acct(bob), null));
        assertEquals(money("100.00"), balanceOf(alice));
        assertTrue(stored(bob).getHistory().isEmpty());
    }

    @Test
    void totalMoneyIsUnchangedByAChainOfTransfers() {
        User alice = register("alice");
        User bob = register("bob");
        User carol = register("carol");
        bank.deposit(acct(alice), money("300"));
        bank.transfer(acct(alice), acct(bob), money("120.10"));
        bank.transfer(acct(bob), acct(carol), money("20.05"));
        bank.transfer(acct(carol), acct(alice), money("5"));
        BigDecimal total = balanceOf(alice).add(balanceOf(bob)).add(balanceOf(carol));
        assertEquals(0, total.compareTo(money("300")));
    }

    @Test
    void historyKeepsTheOrderOfEvents() {
        User alice = register("alice");
        User bob = register("bob");
        bank.deposit(acct(alice), money("100"));
        bank.transfer(acct(alice), acct(bob), money("30"));
        bank.withdraw(acct(alice), money("10"));
        var types = stored(alice).getHistory().stream().map(Transaction::type).toList();
        assertEquals(java.util.List.of(Transaction.Type.DEPOSIT, Transaction.Type.TRANSFER_OUT,
            Transaction.Type.WITHDRAWAL), types);
    }
}
