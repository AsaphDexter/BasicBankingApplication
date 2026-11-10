package basicbankingapp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Asks a running API whether it is healthy: exit code 0 if it answers {"status":"UP"}, 1 otherwise.
 * Used by the Docker health check, so the image needs no curl.
 * Usage: HealthCheck [url]   (default http://localhost:8080/api/health)
 */
public final class HealthCheck {

    private HealthCheck() { }

    public static void main(String[] args) {
        String url = args.length > 0 ? args[0] : "http://localhost:8080/api/health";
        System.exit(isHealthy(url) ? 0 : 1);
    }

    static boolean isHealthy(String url) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && response.body().contains("\"UP\"");
        } catch (Exception e) {
            return false;
        }
    }
}
