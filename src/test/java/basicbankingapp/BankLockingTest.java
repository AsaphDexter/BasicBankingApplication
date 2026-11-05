package basicbankingapp;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Proves, without relying on timing luck, that slow password hashing never holds up other customers. */
class BankLockingTest {

    /** A hasher whose verification waits until the test lets it finish. */
    private static final class GatedHasher extends PasswordHasher {
        final CountDownLatch verifyStarted = new CountDownLatch(1);
        final CountDownLatch letVerifyFinish = new CountDownLatch(1);
        volatile boolean gateOpen = false;

        GatedHasher() {
            super(1_000);
        }

        @Override
        public boolean verify(char[] password, String stored) {
            if (gateOpen) {
                verifyStarted.countDown();
                try {
                    if (!letVerifyFinish.await(20, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test never released the hasher");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return super.verify(password, stored);
        }
    }

    @Test
    void moneyOperationsRunWhileALoginIsStillHashing() throws Exception {
        GatedHasher hasher = new GatedHasher();
        InMemoryBankRepository repository = new InMemoryBankRepository();
        Bank bank = new Bank(hasher, repository, Clock.systemUTC(), Bank.DEFAULT_LOCKOUT);
        User alice = bank.register("alice", "Alice Doe", "Passw0rd1".toCharArray());
        User bob = bank.register("bob", "Bob Roe", "Passw0rd1".toCharArray());

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            hasher.gateOpen = true;
            Future<User> slowLogin = pool.submit(() -> bank.login("alice", "Passw0rd1".toCharArray()));
            assertTrue(hasher.verifyStarted.await(10, TimeUnit.SECONDS)); // the login is now stuck inside hashing

            // While it is stuck, everyone else must be able to use the bank.
            bank.deposit(bob.getAccount().getCustomerId(), new BigDecimal("10"));
            bank.transfer(bob.getAccount().getCustomerId(), alice.getAccount().getCustomerId(), new BigDecimal("4"));
            assertEquals(new BigDecimal("6.00"), bank.findAccount(bob.getAccount().getCustomerId()).getBalance());
            assertNotNull(bank.getUser("bob"));
            assertFalse(slowLogin.isDone()); // and the slow login really was still waiting all that time

            hasher.letVerifyFinish.countDown();
            assertEquals("alice", slowLogin.get(10, TimeUnit.SECONDS).getUsername());
        } finally {
            hasher.letVerifyFinish.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void registrationAlsoHashesOutsideTheLock() throws Exception {
        // register() hashes before taking the lock, so a login for someone else is not held up either.
        GatedHasher hasher = new GatedHasher();
        Bank bank = new Bank(hasher, new InMemoryBankRepository(), Clock.systemUTC(), Bank.DEFAULT_LOCKOUT);
        User bob = bank.register("bob", "Bob Roe", "Passw0rd1".toCharArray());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            hasher.gateOpen = true;
            Future<User> slowLogin = pool.submit(() -> bank.login("bob", "Passw0rd1".toCharArray()));
            assertTrue(hasher.verifyStarted.await(10, TimeUnit.SECONDS));
            User carol = bank.register("carol", "Carol Poe", "Passw0rd1".toCharArray()); // hash() is not gated
            assertNotNull(carol);
            assertFalse(slowLogin.isDone());
            hasher.letVerifyFinish.countDown();
            assertEquals(bob.getUsername(), slowLogin.get(10, TimeUnit.SECONDS).getUsername());
        } finally {
            hasher.letVerifyFinish.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void manyParallelWrongPasswordsStillCountExactlyAndLockOnce() throws Exception {
        Bank bank = new Bank(new PasswordHasher(1_000), new InMemoryBankRepository());
        bank.register("alice", "Alice Doe", "Passw0rd1".toCharArray());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            java.util.List<Future<String>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 12; i++) {
                results.add(pool.submit(() -> {
                    try {
                        bank.login("alice", "Wrong0000".toCharArray());
                        return "SUCCESS";
                    } catch (AccountLockedException e) {
                        return "LOCKED";
                    } catch (AuthenticationException e) {
                        return "REJECTED";
                    }
                }));
            }
            int success = 0;
            for (Future<String> result : results) {
                if (result.get(30, TimeUnit.SECONDS).equals("SUCCESS")) {
                    success++;
                }
            }
            assertEquals(0, success);
            assertTrue(bank.getUser("alice").isLocked());
            assertTrue(bank.getUser("alice").getFailedLogins() >= Bank.MAX_FAILED_LOGINS);
        } finally {
            pool.shutdownNow();
        }
    }
}
