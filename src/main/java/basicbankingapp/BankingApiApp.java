package basicbankingapp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

/**
 * Starts the REST API. Options (see {@link ApiOptions}):
 * <pre>
 * --host localhost          address to listen on; 0.0.0.0 for all interfaces (needed inside a container)
 * --port 8080               port to listen on
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
        ApiOptions options;
        try {
            options = ApiOptions.parse(args, System.getenv());
        } catch (IllegalArgumentException e) {
            System.out.println(e.getMessage());
            return;
        }

        BankRepository repository = options.memoryOnly()
            ? new InMemoryBankRepository()
            : JdbcBankRepository.file(Path.of("data", "bank"));
        Bank bank = new Bank(new PasswordHasher(), repository, Clock.systemUTC(),
            Duration.ofSeconds(options.lockoutSeconds()));
        ApiServer server = new ApiServer(bank, new SessionStore(Duration.ofMinutes(30)),
            new LoginThrottle(options.failureLimit(), Duration.ofSeconds(options.windowSeconds())),
            options.adminToken(), new InetSocketAddress(InetAddress.getByName(options.host()), options.port()));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            repository.close();
        }));

        server.start();
        System.out.println("Banking API listening on http://" + options.host() + ":" + server.port()
            + (options.memoryOnly() ? " (demo mode: nothing is saved)" : " (data saved in the data folder)"));
        if (options.listensOnAllInterfaces()) {
            System.out.println("WARNING: listening on all network interfaces over plain HTTP. "
                + "Put HTTPS in front of it, or publish the port to localhost only.");
        }
        System.out.println("Accounts lock for " + options.lockoutSeconds() + " s after " + Bank.MAX_FAILED_LOGINS
            + " failed logins; " + (options.failureLimit() == 0 ? "no per-client login limit"
            : options.failureLimit() + " failed logins per client per " + options.windowSeconds() + " s"));
        System.out.println(options.adminToken() == null ? "Admin endpoints are switched off (no admin token set)."
            : "Admin endpoints are on.");
        System.out.println("Press Ctrl+C to stop.");
    }
}
