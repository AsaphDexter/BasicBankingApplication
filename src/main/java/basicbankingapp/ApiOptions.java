package basicbankingapp;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;

/** Command-line settings for {@link BankingApiApp}, parsed in one place so they can be tested. */
public record ApiOptions(String host, int port, boolean memoryOnly, String adminToken,
                         long lockoutSeconds, int failureLimit, long windowSeconds) {

    public static final String USAGE = "Usage: BankingApiApp [--host ADDRESS] [--port N] [--memory] "
        + "[--admin-token SECRET] [--lockout-seconds N] [--login-failure-limit N] [--login-window-seconds N]";

    /** True when the server accepts connections from other machines, not just this one. */
    public boolean listensOnAllInterfaces() {
        try {
            return !InetAddress.getByName(host).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return true;
        }
    }

    /**
     * @param args command-line arguments
     * @param env  environment variables ({@code BANKING_ADMIN_TOKEN} supplies the admin token if no option does)
     * @throws IllegalArgumentException with a message fit to show the user
     */
    public static ApiOptions parse(String[] args, Map<String, String> env) {
        String host = "localhost";
        int port = 8080;
        boolean memoryOnly = false;
        String fromEnvironment = env.get("BANKING_ADMIN_TOKEN");
        // Docker Compose passes an empty value when the variable is not set; that means "no admin token".
        String adminToken = fromEnvironment == null || fromEnvironment.isBlank() ? null : fromEnvironment;
        long lockoutSeconds = Bank.DEFAULT_LOCKOUT.toSeconds();
        int failureLimit = 20;
        long windowSeconds = 300;

        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--memory" -> memoryOnly = true;
                    case "--host" -> host = args[++i];
                    case "--port" -> port = Integer.parseInt(args[++i]);
                    case "--admin-token" -> adminToken = args[++i];
                    case "--lockout-seconds" -> lockoutSeconds = Long.parseLong(args[++i]);
                    case "--login-failure-limit" -> failureLimit = Integer.parseInt(args[++i]);
                    case "--login-window-seconds" -> windowSeconds = Long.parseLong(args[++i]);
                    default -> throw new IllegalArgumentException("Unknown option " + args[i] + "\n" + USAGE);
                }
            }
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            throw new IllegalArgumentException("Missing or invalid value for an option\n" + USAGE);
        }

        if (adminToken != null && adminToken.length() < 16) {
            throw new IllegalArgumentException("The admin token must be at least 16 characters.");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("The port must be between 0 and 65535.");
        }
        if (lockoutSeconds < 1 || windowSeconds < 1 || failureLimit < 0) {
            throw new IllegalArgumentException("Lockout and window must be at least 1 second and the limit cannot be negative.\n" + USAGE);
        }
        return new ApiOptions(host, port, memoryOnly, adminToken, lockoutSeconds, failureLimit, windowSeconds);
    }
}
