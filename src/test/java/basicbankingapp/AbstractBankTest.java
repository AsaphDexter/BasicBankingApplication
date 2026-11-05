package basicbankingapp;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
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

    protected static final Duration LOCKOUT = Duration.ofMinutes(15);

    protected BankRepository repository;
    protected TestClock clock;
    protected Bank bank;

    protected abstract BankRepository createRepository();

    @BeforeEach
    void setUp() {
        repository = createRepository();
        clock = new TestClock();
        bank = new Bank(FAST_HASHER, repository, clock, LOCKOUT);
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
        assertThrows(UsernameTakenException.class, () -> bank.register("ALICE", "Other Person", pw("Passw0rd1")));
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
        assertThrows(AccountNotFoundException.class, () -> bank.deposit("9999999999", money("5")));
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

    // ---------- timed lockout ----------

    private void lock(String username) {
        for (int i = 0; i < Bank.MAX_FAILED_LOGINS; i++) {
            assertThrows(AuthenticationException.class, () -> bank.login(username, pw("Wrong0000")));
        }
    }

    @Test
    void lockLastsExactlyTheLockoutPeriodAndThenLoginWorksAgain() {
        register("alice");
        lock("alice");
        clock.advance(LOCKOUT.minusSeconds(1));
        assertThrows(AccountLockedException.class, () -> bank.login("alice", pw("Passw0rd1")));
        clock.advance(Duration.ofSeconds(1)); // the lock ends exactly at the lockout time
        assertNotNull(bank.login("alice", pw("Passw0rd1")));
        assertFalse(bank.getUser("alice").isLocked());
        assertEquals(0, bank.getUser("alice").getFailedLogins());
    }

    @Test
    void lockedErrorSaysHowLongToWait() {
        register("alice");
        lock("alice");
        AccountLockedException blocked =
            assertThrows(AccountLockedException.class, () -> bank.login("alice", pw("Passw0rd1")));
        assertEquals(LOCKOUT.toSeconds(), blocked.getRetryAfterSeconds());
        clock.advance(Duration.ofMinutes(10));
        AccountLockedException later =
            assertThrows(AccountLockedException.class, () -> bank.login("alice", pw("Passw0rd1")));
        assertEquals(300L, later.getRetryAfterSeconds());
    }

    @Test
    void theLockingAttemptItselfReportsTheWait() {
        register("alice");
        assertThrows(AuthenticationException.class, () -> bank.login("alice", pw("Wrong1111")));
        assertThrows(AuthenticationException.class, () -> bank.login("alice", pw("Wrong2222")));
        AccountLockedException locking =
            assertThrows(AccountLockedException.class, () -> bank.login("alice", pw("Wrong3333")));
        assertEquals(LOCKOUT.toSeconds(), locking.getRetryAfterSeconds());
    }

    @Test
    void afterTheLockExpiresTheUserGetsAFreshSetOfAttempts() {
        register("alice");
        lock("alice");
        clock.advance(LOCKOUT);
        assertThrows(AuthenticationException.class, () -> bank.login("alice", pw("Wrong1111")));
        assertThrows(AuthenticationException.class, () -> bank.login("alice", pw("Wrong2222")));
        assertNotNull(bank.login("alice", pw("Passw0rd1"))); // not locked after only two new failures
    }

    @Test
    void wrongPasswordRightAfterExpiryStartsANewCountInsteadOfLockingAtOnce() {
        register("alice");
        lock("alice");
        clock.advance(LOCKOUT);
        assertThrows(AuthenticationException.class, () -> bank.login("alice", pw("Wrong1111")));
        assertEquals(1, bank.getUser("alice").getFailedLogins());
        assertFalse(bank.getUser("alice").isLockedAt(clock.instant()));
    }

    @Test
    void lockingAgainAfterExpiryNeedsAFullNewRunOfFailures() {
        register("alice");
        lock("alice");
        clock.advance(LOCKOUT);
        lock("alice");
        assertTrue(bank.getUser("alice").isLockedAt(clock.instant()));
    }

    // ---------- unlock ----------

    @Test
    void adminUnlockEndsTheLockAtOnce() {
        register("alice");
        lock("alice");
        bank.unlock("alice");
        assertNotNull(bank.login("alice", pw("Passw0rd1")));
    }

    @Test
    void unlockingAnUnknownUserIsRejected() {
        assertThrows(UserNotFoundException.class, () -> bank.unlock("nobody"));
        assertThrows(UserNotFoundException.class, () -> bank.unlock(null));
    }

    // ---------- change password ----------

    @Test
    void changePasswordReplacesTheOldPassword() {
        register("alice");
        bank.changePassword("alice", pw("Passw0rd1"), pw("N3wPassword"), null);
        assertThrows(AuthenticationException.class, () -> bank.login("alice", pw("Passw0rd1")));
        assertNotNull(bank.login("alice", pw("N3wPassword")));
    }

    @Test
    void wrongCurrentPasswordChangesNothing() {
        register("alice");
        assertThrows(AuthenticationException.class,
            () -> bank.changePassword("alice", pw("Wrong1111"), pw("N3wPassword"), null));
        assertNotNull(bank.login("alice", pw("Passw0rd1")));
    }

    @Test
    void wrongCurrentPasswordCountsTowardsTheLockout() {
        register("alice");
        for (int i = 0; i < Bank.MAX_FAILED_LOGINS - 1; i++) {
            assertThrows(AuthenticationException.class,
                () -> bank.changePassword("alice", pw("Wrong1111"), pw("N3wPassword"), null));
        }
        assertThrows(AccountLockedException.class,
            () -> bank.changePassword("alice", pw("Wrong1111"), pw("N3wPassword"), null));
        assertThrows(AccountLockedException.class, () -> bank.login("alice", pw("Passw0rd1")));
    }

    @Test
    void lockedUserCannotChangeTheirPasswordEvenWithTheRightOne() {
        register("alice");
        lock("alice");
        assertThrows(AccountLockedException.class,
            () -> bank.changePassword("alice", pw("Passw0rd1"), pw("N3wPassword"), null));
    }

    @Test
    void weakOrUnchangedNewPasswordsAreRejectedAndTheOldOneStillWorks() {
        register("alice");
        assertThrows(RegistrationException.class,
            () -> bank.changePassword("alice", pw("Passw0rd1"), pw("short1"), null));
        assertThrows(RegistrationException.class,
            () -> bank.changePassword("alice", pw("Passw0rd1"), pw("onlyletters"), null));
        assertThrows(RegistrationException.class,
            () -> bank.changePassword("alice", pw("Passw0rd1"), pw("Passw0rd1"), null)); // same as before
        assertNotNull(bank.login("alice", pw("Passw0rd1")));
    }

    @Test
    void passwordArraysAreWipedAfterAChange() {
        register("alice");
        char[] oldPassword = pw("Passw0rd1");
        char[] newPassword = pw("N3wPassword");
        bank.changePassword("alice", oldPassword, newPassword, null);
        for (char c : oldPassword) {
            assertEquals('\0', c);
        }
        for (char c : newPassword) {
            assertEquals('\0', c);
        }
    }

    // ---------- audit log ----------

    private List<AuditEvent.Type> typesFor(String username) {
        // newest first -> reverse so tests read in the order things happened
        List<AuditEvent.Type> types = bank.auditLog(username, 100).stream().map(AuditEvent::type)
            .collect(java.util.stream.Collectors.toList());
        java.util.Collections.reverse(types);
        return types;
    }

    @Test
    void auditLogRecordsRegistrationAndLoginsInOrder() {
        register("alice");
        bank.login("alice", pw("Passw0rd1"), "10.0.0.5");
        assertThrows(AuthenticationException.class, () -> bank.login("alice", pw("Wrong1111"), "10.0.0.5"));
        assertEquals(List.of(AuditEvent.Type.REGISTERED, AuditEvent.Type.LOGIN_SUCCESS, AuditEvent.Type.LOGIN_FAILED),
            typesFor("alice"));
    }

    @Test
    void auditLogRecordsLockingBlockingUnlockingAndPasswordChanges() {
        register("alice");
        lock("alice");
        assertThrows(AccountLockedException.class, () -> bank.login("alice", pw("Passw0rd1")));
        bank.unlock("alice");
        bank.changePassword("alice", pw("Passw0rd1"), pw("N3wPassword"), null);
        assertEquals(List.of(
            AuditEvent.Type.REGISTERED,
            AuditEvent.Type.LOGIN_FAILED, AuditEvent.Type.LOGIN_FAILED, AuditEvent.Type.LOGIN_FAILED,
            AuditEvent.Type.ACCOUNT_LOCKED, AuditEvent.Type.LOGIN_BLOCKED,
            AuditEvent.Type.ACCOUNT_UNLOCKED, AuditEvent.Type.PASSWORD_CHANGED),
            typesFor("alice"));
    }

    @Test
    void failedLoginsAreStillRecordedAfterTheExceptionIsThrown() {
        register("alice");
        assertThrows(AuthenticationException.class, () -> bank.login("alice", pw("Wrong1111")));
        assertEquals(1, bank.getUser("alice").getFailedLogins()); // not rolled back with the exception
        assertEquals(1L, bank.auditLog("alice", 100).stream()
            .filter(e -> e.type() == AuditEvent.Type.LOGIN_FAILED).count());
    }

    @Test
    void auditLogRecordsTransfersAndUnknownUsers() {
        User alice = register("alice");
        User bob = register("bob");
        bank.deposit(acct(alice), money("100"));
        bank.transfer(acct(alice), acct(bob), money("25.5"));
        assertThrows(AuthenticationException.class, () -> bank.login("ghost", pw("Passw0rd1"), "10.0.0.9"));

        var transfer = bank.auditLog(null, 100).stream()
            .filter(e -> e.type() == AuditEvent.Type.TRANSFER).findFirst().orElseThrow();
        assertTrue(transfer.detail().contains(acct(alice)));
        assertTrue(transfer.detail().contains(acct(bob)));
        assertTrue(transfer.detail().contains("25.50"));
        assertEquals(1, bank.auditLog("ghost", 10).size());
        assertTrue(bank.auditLog("ghost", 10).get(0).detail().contains("10.0.0.9"));
    }

    @Test
    void transferAuditEntriesCanBeFoundByTheUserWhoMadeThem() {
        User alice = register("alice");
        User bob = register("bob");
        bank.deposit(acct(alice), money("100"));
        bank.transfer(acct(alice), acct(bob), money("10"), "Alice");
        bank.transfer(acct(bob), acct(alice), money("3"), "bob");
        assertEquals(1L, bank.auditLog("alice", 100).stream().filter(e -> e.type() == AuditEvent.Type.TRANSFER).count());
        assertEquals(1L, bank.auditLog("bob", 100).stream().filter(e -> e.type() == AuditEvent.Type.TRANSFER).count());
    }

    @Test
    void auditLogNeverContainsPasswordsOrHashes() {
        register("alice");
        assertThrows(AuthenticationException.class, () -> bank.login("alice", pw("SecretGuess99")));
        bank.changePassword("alice", pw("Passw0rd1"), pw("N3wPassword"), "10.0.0.5");
        for (AuditEvent event : bank.auditLog(null, 100)) {
            String text = event.toString();
            assertFalse(text.contains("Passw0rd1"));
            assertFalse(text.contains("SecretGuess99"));
            assertFalse(text.contains("N3wPassword"));
            assertFalse(text.toLowerCase().contains("pbkdf2"));
        }
    }

    @Test
    void auditTextFromAttackersCannotForgeLogLines() {
        assertThrows(AuthenticationException.class,
            () -> bank.login("evil\nLOGIN_SUCCESS admin" + "x".repeat(200), pw("Passw0rd1")));
        AuditEvent event = bank.auditLog(null, 1).get(0);
        assertFalse(event.username().contains("\n"));
        assertTrue(event.username().length() <= 50);
    }

    @Test
    void auditLogLimitAndNewestFirstOrderingWork() {
        register("alice");
        for (int i = 0; i < 5; i++) {
            bank.login("alice", pw("Passw0rd1"));
        }
        assertEquals(3, bank.auditLog("alice", 3).size());
        assertEquals(AuditEvent.Type.LOGIN_SUCCESS, bank.auditLog("alice", 3).get(0).type());
        assertEquals(AuditEvent.Type.REGISTERED, bank.auditLog("alice", 100).get(5).type());
    }
}
