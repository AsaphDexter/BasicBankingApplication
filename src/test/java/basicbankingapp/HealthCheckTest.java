package basicbankingapp;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;

class HealthCheckTest {

    @Test
    void aRunningApiIsHealthy() throws Exception {
        try (JdbcBankRepository repository = JdbcBankRepository.inMemory();
             ApiServer server = new ApiServer(new Bank(new PasswordHasher(1_000), repository),
                 new SessionStore(java.time.Duration.ofMinutes(5)),
                 new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            server.start();
            assertTrue(HealthCheck.isHealthy("http://127.0.0.1:" + server.port() + "/api/health"));
            assertFalse(HealthCheck.isHealthy("http://127.0.0.1:" + server.port() + "/api/nothing")); // 404 is not healthy
        }
    }

    @Test
    void nothingListeningIsUnhealthy() throws Exception {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }
        assertFalse(HealthCheck.isHealthy("http://127.0.0.1:" + freePort + "/api/health"));
    }

    @Test
    void garbageAddressesAreUnhealthyNotACrash() {
        assertFalse(HealthCheck.isHealthy("not a url"));
        assertFalse(HealthCheck.isHealthy(""));
    }
}
