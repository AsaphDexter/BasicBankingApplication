package basicbankingapp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Scanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** End-to-end checks of the console menus using scripted input. */
class BasicBankingAppTest {

    private Bank bank;

    @BeforeEach
    void setUp() {
        bank = new Bank(new PasswordHasher(1_000));
    }

    /** Runs the app with the given lines as keyboard input and returns everything it printed. */
    private String run(String... lines) {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured));
        try {
            new BasicBankingApp(bank, new Scanner(String.join("\n", lines) + "\n")).run();
        } finally {
            System.setOut(original);
        }
        return captured.toString();
    }

    @Test
    void registerThenLogInDepositAndCheckBalance() {
        String output = run(
            "R", "alice", "Alice Doe", "Passw0rd1", "Passw0rd1",
            "L", "alice", "Passw0rd1",
            "B", "100.50",
            "A",
            "O",
            "E");
        assertTrue(output.contains("Registered. Your account number is"));
        assertTrue(output.contains("Signed in as Alice Doe"));
        assertTrue(output.contains("Deposited: 100.50"));
        assertTrue(output.contains("Balance: 100.50"));
        assertTrue(output.contains("Signed out."));
    }

    @Test
    void transferBetweenTwoUsersThroughTheMenu() {
        User bob = bank.register("bob", "Bob Roe", "Passw0rd1".toCharArray());
        String output = run(
            "R", "alice", "Alice Doe", "Passw0rd1", "Passw0rd1",
            "L", "alice", "Passw0rd1",
            "B", "100",
            "T", bob.getAccount().getCustomerId(), "30",
            "O",
            "E");
        assertTrue(output.contains("Transferred: 30"));
        assertEquals(new java.math.BigDecimal("30.00"), bob.getAccount().getBalance());
        assertEquals(new java.math.BigDecimal("70.00"), bank.getUser("alice").getAccount().getBalance());
    }

    @Test
    void mismatchedPasswordConfirmationStopsRegistration() {
        String output = run("R", "alice", "Alice Doe", "Passw0rd1", "Different1", "E");
        assertTrue(output.contains("passwords do not match"));
        assertNull(bank.getUser("alice"));
    }

    @Test
    void wrongPasswordShowsAGenericMessageAndDoesNotSignIn() {
        bank.register("alice", "Alice Doe", "Passw0rd1".toCharArray());
        String output = run("L", "alice", "Nope12345", "E");
        assertTrue(output.contains("Login failed: Invalid username or password"));
        assertFalse(output.contains("Signed in as"));
    }

    @Test
    void lockedAccountMessageAppearsAfterThreeBadLogins() {
        bank.register("alice", "Alice Doe", "Passw0rd1".toCharArray());
        String output = run(
            "L", "alice", "Wrong1111",
            "L", "alice", "Wrong2222",
            "L", "alice", "Wrong3333",
            "L", "alice", "Passw0rd1",
            "E");
        assertTrue(output.contains("now locked"));
        assertTrue(output.contains("locked after too many failed attempts"));
        assertFalse(output.contains("Signed in as"));
    }

    @Test
    void badAmountsAndOverdraftsAreReportedWithoutCrashing() {
        bank.register("alice", "Alice Doe", "Passw0rd1".toCharArray());
        String output = run(
            "L", "alice", "Passw0rd1",
            "B", "abc",
            "C", "50",
            "B", "-5",
            "Z",
            "O",
            "E");
        assertTrue(output.contains("That is not a valid number."));
        assertTrue(output.contains("Withdrawal failed: Insufficient funds"));
        assertTrue(output.contains("Deposit failed: Amount must be greater than zero"));
        assertTrue(output.contains("Invalid option"));
    }

    @Test
    void endOfInputExitsInsteadOfLoopingForever() {
        String output = run("L", "alice", "Passw0rd1");
        assertTrue(output.contains("Goodbye!"));
    }
}
