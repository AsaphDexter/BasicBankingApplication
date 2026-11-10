package basicbankingapp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ApiOptionsTest {

    private static ApiOptions parse(String... args) {
        return ApiOptions.parse(args, Map.of());
    }

    @Test
    void defaultsAreSafe() {
        ApiOptions options = parse();
        assertEquals("localhost", options.host());
        assertEquals(8080, options.port());
        assertFalse(options.memoryOnly());
        assertNull(options.adminToken());
        assertEquals(900L, options.lockoutSeconds());
        assertEquals(20, options.failureLimit());
        assertEquals(300L, options.windowSeconds());
        assertFalse(options.listensOnAllInterfaces()); // only this machine can connect by default
    }

    @Test
    void everyOptionIsRead() {
        ApiOptions options = parse("--host", "0.0.0.0", "--port", "9090", "--memory", "--admin-token", "0123456789abcdef",
            "--lockout-seconds", "2", "--login-failure-limit", "5", "--login-window-seconds", "60");
        assertEquals("0.0.0.0", options.host());
        assertEquals(9090, options.port());
        assertTrue(options.memoryOnly());
        assertEquals("0123456789abcdef", options.adminToken());
        assertEquals(2L, options.lockoutSeconds());
        assertEquals(5, options.failureLimit());
        assertEquals(60L, options.windowSeconds());
        assertTrue(options.listensOnAllInterfaces());
    }

    @Test
    void loopbackAddressesAreNotExposed() {
        assertFalse(parse("--host", "127.0.0.1").listensOnAllInterfaces());
        assertFalse(parse("--host", "localhost").listensOnAllInterfaces());
        assertTrue(parse("--host", "0.0.0.0").listensOnAllInterfaces());
    }

    @Test
    void adminTokenCanComeFromTheEnvironment() {
        ApiOptions options = ApiOptions.parse(new String[0], Map.of("BANKING_ADMIN_TOKEN", "from-the-environment-123"));
        assertEquals("from-the-environment-123", options.adminToken());
        ApiOptions overridden = ApiOptions.parse(new String[] {"--admin-token", "from-the-command-line-1"},
            Map.of("BANKING_ADMIN_TOKEN", "from-the-environment-123"));
        assertEquals("from-the-command-line-1", overridden.adminToken());
    }

    @Test
    void anEmptyAdminTokenInTheEnvironmentMeansNoAdminToken() {
        assertNull(ApiOptions.parse(new String[0], Map.of("BANKING_ADMIN_TOKEN", "")).adminToken());
        assertNull(ApiOptions.parse(new String[0], Map.of("BANKING_ADMIN_TOKEN", "   ")).adminToken());
        // ...but an empty value typed on the command line is a mistake worth reporting
        assertThrows(IllegalArgumentException.class, () -> parse("--admin-token", ""));
    }

    @Test
    void shortAdminTokensAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> parse("--admin-token", "short"));
        assertThrows(IllegalArgumentException.class,
            () -> ApiOptions.parse(new String[0], Map.of("BANKING_ADMIN_TOKEN", "short")));
    }

    @Test
    void badInputIsRefusedWithAHelpfulMessage() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> parse("--nonsense")).getMessage().contains("--nonsense"));
        assertThrows(IllegalArgumentException.class, () -> parse("--port"));            // value missing
        assertThrows(IllegalArgumentException.class, () -> parse("--port", "eighty"));
        assertThrows(IllegalArgumentException.class, () -> parse("--port", "70000"));
        assertThrows(IllegalArgumentException.class, () -> parse("--lockout-seconds", "0"));
        assertThrows(IllegalArgumentException.class, () -> parse("--login-failure-limit", "-1"));
    }

    @Test
    void aFailureLimitOfZeroMeansNoLimit() {
        assertEquals(0, parse("--login-failure-limit", "0").failureLimit());
    }
}
