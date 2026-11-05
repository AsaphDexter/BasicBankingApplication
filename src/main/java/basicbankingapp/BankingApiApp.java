package basicbankingapp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

/**
 * Starts the REST API. Options:
 * <pre>
 * --port 8080               port to listen on (localhost only)
 * --memory                  throwaway data instead of the saved database
 * --admin-token SECRET      switches on the admin endpoints (or set BANKING_ADMIN_TOKEN); 16+ characters
 * --lockout-seconds 900     how long an account stays locked after 3 failed logins
 * --login-failure-limit 20  failed logins allowed per client address per window (0 = no limit)
 * --login-window-seconds 300
 * </pre>
 * Do not run this and the console app on the same database at once.
 */
public final class BankingApiApp {

    private BankingApiApp() { }

    public static void main(String[] args) throws IOException {
        int port = 8080;
        boolean memoryOnly = false;
        String adminToken = System.getenv("BANKING_ADMIN_TOKEN");
        long lockoutSeconds = Bank.DEFAULT_LOCKOUT.toSeconds();
        int failureLimit = 20;
        long windowSeconds = 300;

        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--memory" -> memoryOnly = true;
                    case "--port" -> port = Integer.parseInt(args[++i]);
                    case "--admin-token" -> adminToken = args[++i];
                    case "--lockout-seconds" -> lockoutSeconds = Long.parseLong(args[++i]);
                    case "--login-failure-limit" -> failureLimit = Integer.parseInt(args[++i]);
                    case "--login-window-seconds" -> windowSeconds = Long.parseLong(args[++i]);
                    default -> {
                        usage();
                        return;
                    }
                }
            }
            if (adminToken != null && adminToken.length() < 16) {
                System.out.println("The admin token must be at least 16 characters.");
                return;
            }
            if (lockoutSeconds < 1 || windowSeconds < 1 || failureLimit < 0) {
                usage();
                return;
            }
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            usage();
            return;
        }

        BankRepository repository = memoryOnly
            ? new InMemoryBankRepository()
            : JdbcBankRepository.file(Path.of("data", "bank"));
        Bank bank = new Bank(new PasswordHasher(), repository, Clock.systemUTC(), Duration.ofSeconds(lockoutSeconds));
        ApiServer server = new ApiServer(bank, new SessionStore(Duration.ofMinutes(30)),
            new LoginThrottle(failureLimit, Duration.ofSeconds(windowSeconds)), adminToken,
            new InetSocketAddress(InetAddress.getLoopbackAddress(), port));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            repository.close();
        }));

        server.start();
        System.out.println("Banking API listening on http://localhost:" + server.port()
            + (memoryOnly ? " (demo mode: nothing is saved)" : " (data saved in the data folder)"));
        System.out.println("Accounts lock for " + lockoutSeconds + " s after " + Bank.MAX_FAILED_LOGINS
            + " failed logins; " + (failureLimit == 0 ? "no per-client login limit"
            : failureLimit + " failed logins per client per " + windowSeconds + " s"));
        System.out.println(adminToken == null ? "Admin endpoints are switched off (no admin token set)."
            : "Admin endpoints are on.");
        System.out.println("Press Ctrl+C to stop.");
    }

    private static void usage() {
        System.out.println("Usage: BankingApiApp [--port N] [--memory] [--admin-token SECRET] "
            + "[--lockout-seconds N] [--login-failure-limit N] [--login-window-seconds N]");
    }
}
