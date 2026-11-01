package basicbankingapp;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BankTest {

    private Bank bank;

    @BeforeEach
    void setUp() {
        bank = new Bank(new PasswordHasher(1_000)); // low iterations: fast tests
    }

    private static char[] pw(String value) {
        return value.toCharArray();
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    private User register(String username) {
        return bank.register(username, "Test " + username, pw("Passw0rd1"));
    }

    // ---------- registration ----------

    @Test
    void registrationCreatesUserWithEmptyAccount() {
        User user = bank.register("alice", "Alice Doe", pw("Passw0rd1"));
        assertEquals("alice", user.getUsername());
        assertEquals("Alice Doe", user.getFullName());
        assertEquals(0, user.getAccount().getBalance().compareTo(BigDecimal.ZERO));
        assertSame(user, bank.getUser("alice"));
        assertSame(user.getAccount(), bank.findAccount(user.getAccount().getCustomerId()));
    }

    @Test
    void eachUserGetsADifferentAccountNumber() {
        User alice = register("alice");
        User bob = register("bob");
        assertNotEquals(alice.getAccount().getCustomerId(), bob.getAccount().getCustomerId());
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
        User alice = register("alice");
        assertSame(alice, bank.login("alice", pw("Passw0rd1")));
    }

    @Test
    void usernameIsNotCaseSensitiveAtLogin() {
        User alice = register("alice");
        assertSame(alice, bank.login("  ALICE ", pw("Passw0rd1")));
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
        User alice = register("alice");
        assertThrows(AuthenticationException.class, () -> bank.login("alice", pw("Wrong1111")));
        assertThrows(AuthenticationException.class, () -> bank.login("alice", pw("Wrong2222")));
        assertThrows(AccountLockedException.class, () -> bank.login("alice", pw("Wrong3333")));
        assertTrue(alice.isLocked());
        assertThrows(AccountLockedException.class, () -> bank.login("alice", pw("Passw0rd1")));
    }

    @Test
    void successfulLoginResetsTheFailureCount() {
        User alice = register("alice");
        assertThrows(AuthenticationException.class, () -> bank.login("alice", pw("Wrong1111")));
        assertThrows(AuthenticationException.class, () -> bank.login("alice", pw("Wrong2222")));
        bank.login("alice", pw("Passw0rd1"));
        assertEquals(0, alice.getFailedLogins());
        assertThrows(AuthenticationException.class, () -> bank.login("alice", pw("Wrong3333")));
        assertThrows(AuthenticationException.class, () -> bank.login("alice", pw("Wrong4444")));
        assertFalse(alice.isLocked());
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

    // ---------- transfers ----------

    @Test
    void transferMovesMoneyAndRecordsBothSides() {
        BankAccount alice = register("alice").getAccount();
        BankAccount bob = register("bob").getAccount();
        alice.deposit(money("100"));

        bank.transfer(alice.getCustomerId(), bob.getCustomerId(), money("40.25"));

        assertEquals(money("59.75"), alice.getBalance());
        assertEquals(money("40.25"), bob.getBalance());

        Transaction out = alice.getPreviousTransaction().orElseThrow();
        assertEquals(Transaction.Type.TRANSFER_OUT, out.type());
        assertEquals(bob.getCustomerId(), out.counterparty());
        assertEquals(money("59.75"), out.balanceAfter());

        Transaction in = bob.getPreviousTransaction().orElseThrow();
        assertEquals(Transaction.Type.TRANSFER_IN, in.type());
        assertEquals(alice.getCustomerId(), in.counterparty());
        assertEquals(money("40.25"), in.balanceAfter());
    }

    @Test
    void transferOfTheWholeBalanceIsAllowed() {
        BankAccount alice = register("alice").getAccount();
        BankAccount bob = register("bob").getAccount();
        alice.deposit(money("25"));
        bank.transfer(alice.getCustomerId(), bob.getCustomerId(), money("25"));
        assertEquals(0, alice.getBalance().compareTo(BigDecimal.ZERO));
        assertEquals(money("25.00"), bob.getBalance());
    }

    @Test
    void transferWithInsufficientFundsChangesNothing() {
        BankAccount alice = register("alice").getAccount();
        BankAccount bob = register("bob").getAccount();
        alice.deposit(money("10"));

        assertThrows(InsufficientFundsException.class,
            () -> bank.transfer(alice.getCustomerId(), bob.getCustomerId(), money("10.01")));

        assertEquals(money("10.00"), alice.getBalance());
        assertEquals(0, bob.getBalance().compareTo(BigDecimal.ZERO));
        assertEquals(1, alice.getHistory().size());
        assertTrue(bob.getHistory().isEmpty());
    }

    @Test
    void transferToAnUnknownAccountIsRejectedAndNothingChanges() {
        BankAccount alice = register("alice").getAccount();
        alice.deposit(money("50"));
        assertThrows(IllegalArgumentException.class,
            () -> bank.transfer(alice.getCustomerId(), "9999999999", money("5")));
        assertEquals(money("50.00"), alice.getBalance());
    }

    @Test
    void transferFromAnUnknownAccountIsRejected() {
        BankAccount bob = register("bob").getAccount();
        assertThrows(IllegalArgumentException.class,
            () -> bank.transfer("9999999999", bob.getCustomerId(), money("5")));
        assertEquals(0, bob.getBalance().compareTo(BigDecimal.ZERO));
    }

    @Test
    void transferToTheSameAccountIsRejected() {
        BankAccount alice = register("alice").getAccount();
        alice.deposit(money("50"));
        assertThrows(IllegalArgumentException.class,
            () -> bank.transfer(alice.getCustomerId(), alice.getCustomerId(), money("5")));
        assertEquals(money("50.00"), alice.getBalance());
        assertEquals(1, alice.getHistory().size());
    }

    @Test
    void zeroNegativeAndTooPreciseTransferAmountsAreRejected() {
        BankAccount alice = register("alice").getAccount();
        BankAccount bob = register("bob").getAccount();
        alice.deposit(money("100"));
        assertThrows(IllegalArgumentException.class,
            () -> bank.transfer(alice.getCustomerId(), bob.getCustomerId(), BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,
            () -> bank.transfer(alice.getCustomerId(), bob.getCustomerId(), money("-5")));
        assertThrows(IllegalArgumentException.class,
            () -> bank.transfer(alice.getCustomerId(), bob.getCustomerId(), money("1.005")));
        assertThrows(IllegalArgumentException.class,
            () -> bank.transfer(alice.getCustomerId(), bob.getCustomerId(), null));
        assertEquals(money("100.00"), alice.getBalance());
        assertTrue(bob.getHistory().isEmpty());
    }

    @Test
    void totalMoneyIsUnchangedByAChainOfTransfers() {
        BankAccount alice = register("alice").getAccount();
        BankAccount bob = register("bob").getAccount();
        BankAccount carol = register("carol").getAccount();
        alice.deposit(money("300"));
        bank.transfer(alice.getCustomerId(), bob.getCustomerId(), money("120.10"));
        bank.transfer(bob.getCustomerId(), carol.getCustomerId(), money("20.05"));
        bank.transfer(carol.getCustomerId(), alice.getCustomerId(), money("5"));
        BigDecimal total = alice.getBalance().add(bob.getBalance()).add(carol.getBalance());
        assertEquals(0, total.compareTo(money("300")));
    }
}
