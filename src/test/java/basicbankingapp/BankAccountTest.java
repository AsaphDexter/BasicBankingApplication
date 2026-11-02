package basicbankingapp;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BankAccountTest {

    private BankAccount account;

    @BeforeEach
    void setUp() {
        account = new BankAccount("Strawberry", "JX11004");
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    @Test
    void newAccountStartsWithZeroBalanceAndNoHistory() {
        assertEquals(0, account.getBalance().compareTo(BigDecimal.ZERO));
        assertTrue(account.getHistory().isEmpty());
        assertTrue(account.getPreviousTransaction().isEmpty());
    }

    @Test
    void depositIncreasesBalance() {
        account.deposit(money("100.50"));
        assertEquals(money("100.50"), account.getBalance());
    }

    @Test
    void withdrawalDecreasesBalance() {
        account.deposit(money("200"));
        account.withdraw(money("75.25"));
        assertEquals(money("124.75"), account.getBalance());
    }

    @Test
    void withdrawingExactBalanceLeavesZero() {
        account.deposit(money("50"));
        account.withdraw(money("50"));
        assertEquals(0, account.getBalance().compareTo(BigDecimal.ZERO));
    }

    @Test
    void withdrawalAboveBalanceIsRejectedAndBalanceUnchanged() {
        account.deposit(money("50"));
        assertThrows(InsufficientFundsException.class, () -> account.withdraw(money("50.01")));
        assertEquals(money("50.00"), account.getBalance());
        assertEquals(1, account.getHistory().size());
    }

    @Test
    void zeroNegativeAndNullAmountsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> account.deposit(BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> account.deposit(money("-5")));
        assertThrows(IllegalArgumentException.class, () -> account.deposit(null));
        assertThrows(IllegalArgumentException.class, () -> account.withdraw(money("-5")));
        assertThrows(IllegalArgumentException.class, () -> account.withdraw(BigDecimal.ZERO));
        assertTrue(account.getHistory().isEmpty());
    }

    @Test
    void amountsWithMoreThanTwoDecimalPlacesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> account.deposit(money("10.001")));
    }

    @Test
    void historyIsRecordedInOrderWithBalanceAfterEachStep() {
        account.deposit(money("100"));
        account.withdraw(money("30"));
        account.deposit(money("5"));

        var history = account.getHistory();
        assertEquals(3, history.size());
        assertEquals(Transaction.Type.DEPOSIT, history.get(0).type());
        assertEquals(Transaction.Type.WITHDRAWAL, history.get(1).type());
        assertEquals(money("70.00"), history.get(1).balanceAfter());
        assertEquals(money("75.00"), history.get(2).balanceAfter());
    }

    @Test
    void previousTransactionIsTheLatestOne() {
        account.deposit(money("100"));
        account.withdraw(money("40"));
        var previous = account.getPreviousTransaction().orElseThrow();
        assertEquals(Transaction.Type.WITHDRAWAL, previous.type());
        assertEquals(money("40.00"), previous.amount());
    }

    @Test
    void rejectedOperationsDoNotAppearInHistory() {
        assertThrows(InsufficientFundsException.class, () -> account.withdraw(money("10")));
        assertTrue(account.getHistory().isEmpty());
    }

    @Test
    void historyListCannotBeModifiedFromOutside() {
        account.deposit(money("10"));
        assertThrows(UnsupportedOperationException.class, () -> account.getHistory().clear());
    }

    @Test
    void blankCustomerDetailsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new BankAccount(" ", "X1"));
        assertThrows(IllegalArgumentException.class, () -> new BankAccount("Ann", ""));
    }

    @Test
    void amountAboveTheMaximumIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> account.deposit(BankAccount.MAX_AMOUNT.add(new BigDecimal("0.01"))));
        account.deposit(BankAccount.MAX_AMOUNT); // exactly the maximum is fine
    }

    @Test
    void absurdlyLargeExponentIsRejectedQuicklyWithoutExhaustingMemory() {
        assertThrows(IllegalArgumentException.class, () -> account.deposit(new BigDecimal("1E+999999999")));
        assertThrows(IllegalArgumentException.class, () -> account.withdraw(new BigDecimal("1E+999999999")));
        assertTrue(account.getHistory().isEmpty());
    }

    @Test
    void balanceCannotGrowPastTheLimit() {
        BigDecimal almostFull = BankAccount.MAX_BALANCE.subtract(BigDecimal.ONE);
        for (BigDecimal left = almostFull; left.signum() > 0; ) {
            BigDecimal chunk = left.min(BankAccount.MAX_AMOUNT);
            account.deposit(chunk);
            left = left.subtract(chunk);
        }
        assertThrows(IllegalArgumentException.class, () -> account.deposit(new BigDecimal("1.01")));
        account.deposit(BigDecimal.ONE); // reaching the limit exactly is allowed
        assertEquals(0, account.getBalance().compareTo(BankAccount.MAX_BALANCE));
    }
}
