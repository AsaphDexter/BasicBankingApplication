package basicbankingapp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Starts the REST API. Options: {@code --port 8080} and {@code --memory} (throwaway data).
 * Listens on localhost only. Do not run this and the console app on the same database at once.
 */
public final class BankingApiApp {

    private BankingApiApp() { }

    public static void main(String[] args) throws IOException {
        int port = 8080;
        boolean memoryOnly = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--memory" -> memoryOnly = true;
                case "--port" -> {
                    if (i + 1 >= args.length) {
                        usage();
                        return;
                    }
                    try {
                        port = Integer.parseInt(args[++i]);
                    } catch (NumberFormatException e) {
                        usage();
                        return;
                    }
                }
                default -> {
                    usage();
                    return;
                }
            }
        }

        BankRepository repository = memoryOnly
            ? new InMemoryBankRepository()
            : JdbcBankRepository.file(Path.of("data", "bank"));
        Bank bank = new Bank(new PasswordHasher(), repository);
        ApiServer server = new ApiServer(bank, new SessionStore(Duration.ofMinutes(30)),
            new InetSocketAddress(InetAddress.getLoopbackAddress(), port));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            repository.close();
        }));

        server.start();
        System.out.println("Banking API listening on http://localhost:" + server.port()
            + (memoryOnly ? " (demo mode: nothing is saved)" : " (data saved in the data folder)"));
        System.out.println("Press Ctrl+C to stop.");
    }

    private static void usage() {
        System.out.println("Usage: BankingApiApp [--port <number>] [--memory]");
    }
}
