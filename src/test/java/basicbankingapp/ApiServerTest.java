package basicbankingapp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Starts the real server on a free port with a real in-memory H2 database and calls it over HTTP. */
class ApiServerTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    /** Status, headers and body of one response. */
    private record Reply(int status, HttpResponse<String> raw) {
        String body() {
            return raw.body();
        }

        JsonNode json() throws Exception {
            return MAPPER.readTree(raw.body());
        }

        String header(String name) {
            return raw.headers().firstValue(name).orElse(null);
        }
    }

    private static final String ADMIN_TOKEN = "test-admin-token-0123456789";

    private final List<ApiServer> extraServers = new ArrayList<>();
    private JdbcBankRepository repository;
    private Bank bank;
    private ApiServer server;
    private HttpClient client;
    private String base;

    @BeforeEach
    void setUp() throws Exception {
        repository = JdbcBankRepository.inMemory();
        bank = new Bank(new PasswordHasher(1_000), repository); // low iterations: fast tests
        server = new ApiServer(bank, new SessionStore(Duration.ofMinutes(30)),
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        server.start();
        base = "http://127.0.0.1:" + server.port();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        extraServers.forEach(ApiServer::close);
        server.close();
        repository.close();
    }

    // ---------- helpers ----------

    private Reply call(String method, String path, String token, String contentType, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        request.method(method, body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response);
    }

    /** A second server over the same bank, with its own throttle and admin settings. Returns its base address. */
    private String startServer(LoginThrottle throttle, String adminToken) throws Exception {
        ApiServer extra = new ApiServer(bank, new SessionStore(Duration.ofMinutes(30)), throttle, adminToken,
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        extra.start();
        extraServers.add(extra);
        return "http://127.0.0.1:" + extra.port();
    }

    private Reply send(String baseUrl, String method, String path, Map<String, String> headers, String body)
        throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path));
        headers.forEach(request::header);
        request.method(method, body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response);
    }

    private Reply adminPost(String baseUrl, String path, String adminToken, String body) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        if (adminToken != null) {
            headers.put("X-Admin-Token", adminToken);
        }
        return send(baseUrl, "POST", path, headers, body);
    }

    private Reply adminGet(String baseUrl, String path, String adminToken) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        if (adminToken != null) {
            headers.put("X-Admin-Token", adminToken);
        }
        return send(baseUrl, "GET", path, headers, null);
    }

    private Reply post(String path, String token, String jsonBody) throws Exception {
        return call("POST", path, token, "application/json", jsonBody);
    }

    private Reply get(String path, String token) throws Exception {
        return call("GET", path, token, null, null);
    }

    private static String obj(Object... keysAndValues) throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return MAPPER.writeValueAsString(map);
    }

    private Reply register(String username) throws Exception {
        return post("/api/register", null, obj("username", username, "fullName", "Test " + username,
            "password", "Passw0rd1"));
    }

    private String registerAndLogin(String username) throws Exception {
        assertEquals(201, register(username).status());
        Reply login = post("/api/login", null, obj("username", username, "password", "Passw0rd1"));
        assertEquals(200, login.status());
        return login.json().get("token").asText();
    }

    private String accountNumberOf(String token) throws Exception {
        return get("/api/account", token).json().get("accountNumber").asText();
    }

    private BigDecimal balanceOf(String token) throws Exception {
        return get("/api/account", token).json().get("balance").decimalValue();
    }

    private void deposit(String token, String amount) throws Exception {
        assertEquals(200, post("/api/deposit", token, "{\"amount\":" + amount + "}").status());
    }

    // ---------- basics ----------

    @Test
    void healthEndpointIsUp() throws Exception {
        Reply reply = get("/api/health", null);
        assertEquals(200, reply.status());
        assertEquals("UP", reply.json().get("status").asText());
    }

    @Test
    void responsesAreJsonAndCarrySecurityHeaders() throws Exception {
        Reply reply = get("/api/health", null);
        assertTrue(reply.header("Content-Type").startsWith("application/json"));
        assertEquals("nosniff", reply.header("X-Content-Type-Options"));
        assertEquals("no-store", reply.header("Cache-Control"));
    }

    @Test
    void unknownPathIs404() throws Exception {
        assertEquals(404, get("/api/nothing-here", null).status());
        assertEquals(404, get("/", null).status());
    }

    @Test
    void wrongMethodIs405AndSaysWhatIsAllowed() throws Exception {
        Reply reply = get("/api/login", null);
        assertEquals(405, reply.status());
        assertEquals("POST", reply.header("Allow"));
        assertEquals(405, post("/api/health", null, "{}").status());
        assertEquals(405, call("DELETE", "/api/account", null, null, null).status());
    }

    // ---------- register ----------

    @Test
    void registerReturns201WithAccountNumberAndNoSecrets() throws Exception {
        Reply reply = register("alice");
        assertEquals(201, reply.status());
        assertEquals("alice", reply.json().get("username").asText());
        assertEquals("Test alice", reply.json().get("fullName").asText());
        assertFalse(reply.json().get("accountNumber").asText().isBlank());
        assertFalse(reply.body().contains("Passw0rd1"));
        assertFalse(reply.body().contains("pbkdf2"));
    }

    @Test
    void duplicateUsernameIs409() throws Exception {
        assertEquals(201, register("alice").status());
        assertEquals(409, register("ALICE").status());
    }

    @Test
    void weakPasswordAndBadUsernameAre400() throws Exception {
        assertEquals(400, post("/api/register", null,
            obj("username", "alice", "fullName", "Alice", "password", "short1")).status());
        assertEquals(400, post("/api/register", null,
            obj("username", "bad name!", "fullName", "Alice", "password", "Passw0rd1")).status());
    }

    @Test
    void missingFieldsAre400() throws Exception {
        assertEquals(400, post("/api/register", null, obj("username", "alice", "password", "Passw0rd1")).status());
        assertEquals(400, post("/api/register", null, obj("username", "  ", "fullName", "A", "password", "Passw0rd1")).status());
    }

    // ---------- bad requests ----------

    @Test
    void malformedJsonIs400() throws Exception {
        assertEquals(400, post("/api/login", null, "{not json").status());
        assertEquals(400, post("/api/login", null, "").status());
        assertEquals(400, post("/api/login", null, "null").status());
        assertEquals(400, post("/api/login", null, "[]").status());
    }

    @Test
    void unknownFieldsAreRejected() throws Exception {
        assertEquals(400, post("/api/login", null,
            obj("username", "alice", "password", "Passw0rd1", "admin", true)).status());
    }

    @Test
    void wrongTypesAreRejectedInsteadOfBeingGuessed() throws Exception {
        String token = registerAndLogin("alice");
        assertEquals(400, post("/api/deposit", token, "{\"amount\":\"100\"}").status()); // text, not a number
        assertEquals(400, post("/api/login", null, "{\"username\":\"alice\",\"password\":12345678}").status());
    }

    @Test
    void wrongContentTypeIs415() throws Exception {
        assertEquals(415, call("POST", "/api/login", null, "text/plain", "{}").status());
        assertEquals(415, call("POST", "/api/login", null, null, "{}").status());
    }

    @Test
    void oversizedBodyIs413() throws Exception {
        String huge = obj("username", "a".repeat(20_000), "password", "Passw0rd1");
        assertEquals(413, post("/api/login", null, huge).status());
    }

    // ---------- login, logout, tokens ----------

    @Test
    void loginReturnsABearerToken() throws Exception {
        assertEquals(201, register("alice").status());
        Reply reply = post("/api/login", null, obj("username", "alice", "password", "Passw0rd1"));
        assertEquals(200, reply.status());
        assertEquals("Bearer", reply.json().get("tokenType").asText());
        assertEquals(1800L, reply.json().get("expiresInSeconds").asLong());
        assertTrue(reply.json().get("token").asText().length() >= 43);
        assertFalse(reply.body().contains("Passw0rd1"));
    }

    @Test
    void wrongPasswordAndUnknownUserGiveIdentical401() throws Exception {
        assertEquals(201, register("alice").status());
        Reply wrongPassword = post("/api/login", null, obj("username", "alice", "password", "Wrong1234"));
        Reply unknownUser = post("/api/login", null, obj("username", "nobody", "password", "Wrong1234"));
        assertEquals(401, wrongPassword.status());
        assertEquals(401, unknownUser.status());
        assertEquals(wrongPassword.body(), unknownUser.body());
    }

    @Test
    void threeWrongPasswordsLockTheAccountWith423() throws Exception {
        assertEquals(201, register("alice").status());
        assertEquals(401, post("/api/login", null, obj("username", "alice", "password", "Wrong1111")).status());
        assertEquals(401, post("/api/login", null, obj("username", "alice", "password", "Wrong2222")).status());
        assertEquals(423, post("/api/login", null, obj("username", "alice", "password", "Wrong3333")).status());
        assertEquals(423, post("/api/login", null, obj("username", "alice", "password", "Passw0rd1")).status());
    }

    @Test
    void protectedEndpointsRequireAToken() throws Exception {
        List<Reply> replies = List.of(
            get("/api/account", null),
            get("/api/transactions", null),
            post("/api/deposit", null, "{\"amount\":10}"),
            post("/api/withdraw", null, "{\"amount\":10}"),
            post("/api/transfer", null, "{\"toAccountNumber\":\"1\",\"amount\":10}"),
            post("/api/logout", null, "{}"));
        for (Reply reply : replies) {
            assertEquals(401, reply.status());
            assertEquals("Bearer", reply.header("WWW-Authenticate"));
        }
    }

    @Test
    void garbageAndMalformedTokensAreRejected() throws Exception {
        assertEquals(401, get("/api/account", "garbage").status());
        assertEquals(401, get("/api/account", "").status());
        HttpRequest basic = HttpRequest.newBuilder(URI.create(base + "/api/account"))
            .header("Authorization", "Basic YWxpY2U6cGFzcw==").GET().build();
        assertEquals(401, client.send(basic, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void logoutRevokesTheToken() throws Exception {
        String token = registerAndLogin("alice");
        assertEquals(200, get("/api/account", token).status());
        assertEquals(204, post("/api/logout", token, "{}").status());
        assertEquals(401, get("/api/account", token).status());
    }

    // ---------- money ----------

    @Test
    void depositWithdrawAndAccountShowExactAmounts() throws Exception {
        String token = registerAndLogin("alice");
        Reply deposit = post("/api/deposit", token, "{\"amount\":100.5}");
        assertEquals(200, deposit.status());
        assertTrue(deposit.body().contains("\"balance\":100.50"));

        Reply withdraw = post("/api/withdraw", token, "{\"amount\":40.25}");
        assertEquals(200, withdraw.status());
        assertTrue(withdraw.body().contains("\"balance\":60.25"));

        Reply account = get("/api/account", token);
        assertEquals("Test alice", account.json().get("ownerName").asText());
        assertEquals(0, account.json().get("balance").decimalValue().compareTo(new BigDecimal("60.25")));
    }

    @Test
    void overdraftIs422AndChangesNothing() throws Exception {
        String token = registerAndLogin("alice");
        deposit(token, "50");
        Reply reply = post("/api/withdraw", token, "{\"amount\":50.01}");
        assertEquals(422, reply.status());
        assertTrue(reply.json().get("error").asText().contains("Insufficient funds"));
        assertEquals(0, balanceOf(token).compareTo(new BigDecimal("50")));
    }

    @Test
    void invalidAmountsAre400() throws Exception {
        String token = registerAndLogin("alice");
        for (String amount : List.of("0", "-5", "1.001", "2000000000", "1e999999999")) {
            assertEquals(400, post("/api/deposit", token, "{\"amount\":" + amount + "}").status());
            assertEquals(400, post("/api/withdraw", token, "{\"amount\":" + amount + "}").status());
        }
        assertEquals(400, post("/api/deposit", token, "{}").status());
        assertEquals(400, post("/api/deposit", token, "{\"amount\":null}").status());
        assertEquals(0, balanceOf(token).compareTo(BigDecimal.ZERO));
    }

    @Test
    void transactionsAreListedOldestFirstWithTypes() throws Exception {
        String alice = registerAndLogin("alice");
        String bob = registerAndLogin("bob");
        deposit(alice, "100");
        assertEquals(200, post("/api/transfer", alice, obj("toAccountNumber", accountNumberOf(bob),
            "amount", new BigDecimal("30"))).status());
        assertEquals(200, post("/api/withdraw", alice, "{\"amount\":10}").status());

        JsonNode list = get("/api/transactions", alice).json();
        assertEquals(3, list.size());
        assertEquals("DEPOSIT", list.get(0).get("type").asText());
        assertEquals("TRANSFER_OUT", list.get(1).get("type").asText());
        assertEquals(accountNumberOf(bob), list.get(1).get("counterparty").asText());
        assertEquals("WITHDRAWAL", list.get(2).get("type").asText());
        assertEquals(0, list.get(2).get("balanceAfter").decimalValue().compareTo(new BigDecimal("60")));
        assertFalse(list.get(0).get("timestamp").asText().isBlank());

        JsonNode bobList = get("/api/transactions", bob).json();
        assertEquals(1, bobList.size());
        assertEquals("TRANSFER_IN", bobList.get(0).get("type").asText());
    }

    // ---------- transfers ----------

    @Test
    void transferMovesMoneyBetweenUsers() throws Exception {
        String alice = registerAndLogin("alice");
        String bob = registerAndLogin("bob");
        deposit(alice, "100");

        Reply reply = post("/api/transfer", alice, obj("toAccountNumber", accountNumberOf(bob),
            "amount", new BigDecimal("40.25")));
        assertEquals(200, reply.status());
        assertTrue(reply.body().contains("\"balance\":59.75"));
        assertEquals(0, balanceOf(alice).compareTo(new BigDecimal("59.75")));
        assertEquals(0, balanceOf(bob).compareTo(new BigDecimal("40.25")));
    }

    @Test
    void transferProblemsUseTheRightStatusAndChangeNothing() throws Exception {
        String alice = registerAndLogin("alice");
        String bob = registerAndLogin("bob");
        deposit(alice, "100");
        String bobNumber = accountNumberOf(bob);

        assertEquals(404, post("/api/transfer", alice, obj("toAccountNumber", "9999999999",
            "amount", new BigDecimal("5"))).status());
        assertEquals(400, post("/api/transfer", alice, obj("toAccountNumber", accountNumberOf(alice),
            "amount", new BigDecimal("5"))).status());
        assertEquals(422, post("/api/transfer", alice, obj("toAccountNumber", bobNumber,
            "amount", new BigDecimal("100.01"))).status());
        assertEquals(400, post("/api/transfer", alice, obj("toAccountNumber", bobNumber,
            "amount", new BigDecimal("-1"))).status());
        assertEquals(400, post("/api/transfer", alice, obj("amount", new BigDecimal("5"))).status());

        assertEquals(0, balanceOf(alice).compareTo(new BigDecimal("100")));
        assertEquals(0, balanceOf(bob).compareTo(BigDecimal.ZERO));
    }

    // ---------- isolation between users ----------

    @Test
    void aTokenOnlyEverActsOnItsOwnAccount() throws Exception {
        String alice = registerAndLogin("alice");
        String bob = registerAndLogin("bob");
        deposit(alice, "100");

        assertEquals(0, balanceOf(bob).compareTo(BigDecimal.ZERO));
        assertNotEquals(accountNumberOf(alice), accountNumberOf(bob));
        assertEquals(0, get("/api/transactions", bob).json().size());

        // Even if bob names alice's account number anywhere, the only account he can move money from is his own.
        assertEquals(422, post("/api/withdraw", bob, "{\"amount\":1}").status());
        assertEquals(0, balanceOf(alice).compareTo(new BigDecimal("100")));
    }

    @Test
    void nothingSecretEverAppearsInAnyResponse() throws Exception {
        List<String> bodies = new ArrayList<>();
        bodies.add(register("alice").body());
        String token = registerAndLogin("bob");
        bodies.add(get("/api/account", token).body());
        bodies.add(get("/api/transactions", token).body());
        bodies.add(post("/api/login", null, obj("username", "bob", "password", "Wrong1234")).body());
        for (String body : bodies) {
            assertFalse(body.contains("Passw0rd1"));
            assertFalse(body.toLowerCase().contains("pbkdf2"));
        }
    }

    // ---------- concurrency ----------

    @Test
    void manyConcurrentDepositsAreAllCounted() throws Exception {
        String token = registerAndLogin("alice");
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                results.add(pool.submit((Callable<Integer>) () -> post("/api/deposit", token, "{\"amount\":1}").status()));
            }
            for (Future<Integer> result : results) {
                assertEquals(200, result.get());
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, balanceOf(token).compareTo(new BigDecimal("40")));
        assertEquals(40, get("/api/transactions", token).json().size());
    }

    @Test
    void concurrentTransfersNeverCreateOrDestroyMoney() throws Exception {
        String alice = registerAndLogin("alice");
        String bob = registerAndLogin("bob");
        deposit(alice, "100");
        deposit(bob, "100");
        String aliceNumber = accountNumberOf(alice);
        String bobNumber = accountNumberOf(bob);

        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                results.add(pool.submit((Callable<Integer>) () -> post("/api/transfer", alice,
                    obj("toAccountNumber", bobNumber, "amount", new BigDecimal("7"))).status()));
                results.add(pool.submit((Callable<Integer>) () -> post("/api/transfer", bob,
                    obj("toAccountNumber", aliceNumber, "amount", new BigDecimal("3"))).status()));
            }
            for (Future<Integer> result : results) {
                int status = result.get();
                assertTrue(status == 200 || status == 422); // 422 only if funds ran out, never a crash
            }
        } finally {
            pool.shutdownNow();
        }
        BigDecimal total = balanceOf(alice).add(balanceOf(bob));
        assertEquals(0, total.compareTo(new BigDecimal("200")));
        assertTrue(balanceOf(alice).signum() >= 0);
        assertTrue(balanceOf(bob).signum() >= 0);
    }

    // ---------- timed lockout over HTTP ----------

    @Test
    void lockedResponseCarriesARetryAfterHeader() throws Exception {
        assertEquals(201, register("alice").status());
        post("/api/login", null, obj("username", "alice", "password", "Wrong1111"));
        post("/api/login", null, obj("username", "alice", "password", "Wrong2222"));
        Reply locking = post("/api/login", null, obj("username", "alice", "password", "Wrong3333"));
        assertEquals(423, locking.status());
        assertEquals("900", locking.header("Retry-After"));
        Reply blocked = post("/api/login", null, obj("username", "alice", "password", "Passw0rd1"));
        assertEquals(423, blocked.status());
        assertEquals("900", blocked.header("Retry-After"));
    }

    // ---------- change password ----------

    private void changePassword(String token, String oldPassword, String newPassword, int expectedStatus) throws Exception {
        Reply reply = post("/api/password", token, obj("oldPassword", oldPassword, "newPassword", newPassword));
        assertEquals(expectedStatus, reply.status());
    }

    @Test
    void changingThePasswordWorksAndEndsTheOtherSessionsButNotTheCurrentOne() throws Exception {
        String current = registerAndLogin("alice");
        String otherDevice = post("/api/login", null, obj("username", "alice", "password", "Passw0rd1"))
            .json().get("token").asText();

        changePassword(current, "Passw0rd1", "N3wPassword", 204);

        assertEquals(200, get("/api/account", current).status());     // the session that changed it stays signed in
        assertEquals(401, get("/api/account", otherDevice).status()); // every other session is ended
        assertEquals(401, post("/api/login", null, obj("username", "alice", "password", "Passw0rd1")).status());
        assertEquals(200, post("/api/login", null, obj("username", "alice", "password", "N3wPassword")).status());
    }

    @Test
    void changingAPasswordNeverTouchesOtherUsersSessions() throws Exception {
        String alice = registerAndLogin("alice");
        String bob = registerAndLogin("bob");
        changePassword(alice, "Passw0rd1", "N3wPassword", 204);
        assertEquals(200, get("/api/account", bob).status());
    }

    @Test
    void wrongCurrentPasswordIs403AndRepeatedGuessesLockTheAccount() throws Exception {
        String token = registerAndLogin("alice");
        changePassword(token, "Wrong1111", "N3wPassword", 403);
        changePassword(token, "Wrong2222", "N3wPassword", 403);
        Reply locking = post("/api/password", token, obj("oldPassword", "Wrong3333", "newPassword", "N3wPassword"));
        assertEquals(423, locking.status());
        assertEquals("900", locking.header("Retry-After"));
        changePassword(token, "Passw0rd1", "N3wPassword", 423); // even the right password, while locked
    }

    @Test
    void weakOrUnchangedNewPasswordsAre400AndNothingChanges() throws Exception {
        String token = registerAndLogin("alice");
        changePassword(token, "Passw0rd1", "short1", 400);
        changePassword(token, "Passw0rd1", "onlyletters", 400);
        changePassword(token, "Passw0rd1", "Passw0rd1", 400);
        assertEquals(200, post("/api/login", null, obj("username", "alice", "password", "Passw0rd1")).status());
    }

    @Test
    void changePasswordNeedsATokenAndBothFields() throws Exception {
        String token = registerAndLogin("alice");
        assertEquals(401, post("/api/password", null, obj("oldPassword", "Passw0rd1", "newPassword", "N3wPassword")).status());
        assertEquals(400, post("/api/password", token, obj("oldPassword", "Passw0rd1")).status());
        assertEquals(400, post("/api/password", token, obj("newPassword", "N3wPassword")).status());
        assertEquals(400, post("/api/password", token, obj("oldPassword", "Passw0rd1", "newPassword", "N3wPassword", "x", 1)).status());
    }

    // ---------- admin endpoints ----------

    @Test
    void adminEndpointsDoNotExistWhenNoAdminTokenIsConfigured() throws Exception {
        assertEquals(404, adminPost(base, "/api/admin/unlock", ADMIN_TOKEN, obj("username", "alice")).status());
        assertEquals(404, adminGet(base, "/api/admin/audit", ADMIN_TOKEN).status());
    }

    @Test
    void adminEndpointsRefuseMissingAndWrongTokens() throws Exception {
        String admin = startServer(LoginThrottle.disabled(), ADMIN_TOKEN);
        assertEquals(401, adminPost(admin, "/api/admin/unlock", null, obj("username", "alice")).status());
        assertEquals(401, adminPost(admin, "/api/admin/unlock", "wrong-token-wrong-token", obj("username", "alice")).status());
        assertEquals(401, adminGet(admin, "/api/admin/audit", null).status());
        assertEquals(401, adminGet(admin, "/api/admin/audit", ADMIN_TOKEN + "x").status());
        // a normal user's login token is not an admin token
        String userToken = registerAndLogin("alice");
        assertEquals(401, adminPost(admin, "/api/admin/unlock", userToken, obj("username", "alice")).status());
    }

    @Test
    void adminCanUnlockALockedUser() throws Exception {
        String admin = startServer(LoginThrottle.disabled(), ADMIN_TOKEN);
        assertEquals(201, register("alice").status());
        for (String wrong : List.of("Wrong1111", "Wrong2222", "Wrong3333")) {
            post("/api/login", null, obj("username", "alice", "password", wrong));
        }
        assertEquals(423, post("/api/login", null, obj("username", "alice", "password", "Passw0rd1")).status());

        assertEquals(204, adminPost(admin, "/api/admin/unlock", ADMIN_TOKEN, obj("username", "alice")).status());
        assertEquals(200, post("/api/login", null, obj("username", "alice", "password", "Passw0rd1")).status());
    }

    @Test
    void unlockingAnUnknownUserIs404AndAnEmptyRequestIs400() throws Exception {
        String admin = startServer(LoginThrottle.disabled(), ADMIN_TOKEN);
        assertEquals(404, adminPost(admin, "/api/admin/unlock", ADMIN_TOKEN, obj("username", "nobody")).status());
        assertEquals(400, adminPost(admin, "/api/admin/unlock", ADMIN_TOKEN, "{}").status());
    }

    @Test
    void adminCanReadTheAuditLogFilterAndLimitIt() throws Exception {
        String admin = startServer(LoginThrottle.disabled(), ADMIN_TOKEN);
        String token = registerAndLogin("alice");
        post("/api/login", null, obj("username", "alice", "password", "Wrong1111"));
        changePassword(token, "Passw0rd1", "N3wPassword", 204);

        Reply all = adminGet(admin, "/api/admin/audit?username=alice", ADMIN_TOKEN);
        assertEquals(200, all.status());
        List<String> types = new ArrayList<>();
        all.json().forEach(entry -> types.add(entry.get("type").asText()));
        assertEquals(List.of("PASSWORD_CHANGED", "LOGIN_FAILED", "LOGIN_SUCCESS", "REGISTERED"), types);
        assertFalse(all.body().contains("Passw0rd1"));
        assertFalse(all.body().contains("N3wPassword"));
        assertFalse(all.body().toLowerCase().contains("pbkdf2"));
        assertFalse(all.json().get(0).get("timestamp").asText().isBlank());

        assertEquals(2, adminGet(admin, "/api/admin/audit?username=alice&limit=2", ADMIN_TOKEN).json().size());
        assertEquals(0, adminGet(admin, "/api/admin/audit?username=nobody", ADMIN_TOKEN).json().size());
        assertTrue(adminGet(admin, "/api/admin/audit", ADMIN_TOKEN).json().size() >= 4);
    }

    @Test
    void auditLimitMustBeAWholeNumberBetween1And200() throws Exception {
        String admin = startServer(LoginThrottle.disabled(), ADMIN_TOKEN);
        for (String bad : List.of("0", "201", "-1", "abc", "1.5", "")) {
            assertEquals(400, adminGet(admin, "/api/admin/audit?limit=" + bad, ADMIN_TOKEN).status(), bad);
        }
        assertEquals(200, adminGet(admin, "/api/admin/audit?limit=200", ADMIN_TOKEN).status());
    }

    // ---------- login throttling ----------

    @Test
    void afterTooManyFailedLoginsFromOneClientFurtherLoginsAre429() throws Exception {
        String throttled = startServer(new LoginThrottle(3, Duration.ofMinutes(5)), null);
        for (int i = 0; i < 3; i++) {
            assertEquals(401, send(throttled, "POST", "/api/login", Map.of("Content-Type", "application/json"),
                obj("username", "ghost" + i, "password", "Wrong1234")).status());
        }
        assertEquals(201, register("alice").status());
        Reply blocked = send(throttled, "POST", "/api/login", Map.of("Content-Type", "application/json"),
            obj("username", "alice", "password", "Passw0rd1")); // even correct credentials are refused for now
        assertEquals(429, blocked.status());
        assertTrue(Integer.parseInt(blocked.header("Retry-After")) > 0);
        assertTrue(Integer.parseInt(blocked.header("Retry-After")) <= 300);
    }

    @Test
    void aSuccessfulLoginDoesNotResetTheThrottleCount() throws Exception {
        String throttled = startServer(new LoginThrottle(3, Duration.ofMinutes(5)), null);
        assertEquals(201, register("alice").status());
        Map<String, String> json = Map.of("Content-Type", "application/json");
        assertEquals(401, send(throttled, "POST", "/api/login", json, obj("username", "ghost1", "password", "Wrong1234")).status());
        assertEquals(200, send(throttled, "POST", "/api/login", json, obj("username", "alice", "password", "Passw0rd1")).status());
        assertEquals(401, send(throttled, "POST", "/api/login", json, obj("username", "ghost2", "password", "Wrong1234")).status());
        assertEquals(200, send(throttled, "POST", "/api/login", json, obj("username", "alice", "password", "Passw0rd1")).status());
        assertEquals(401, send(throttled, "POST", "/api/login", json, obj("username", "ghost3", "password", "Wrong1234")).status());
        assertEquals(429, send(throttled, "POST", "/api/login", json, obj("username", "alice", "password", "Passw0rd1")).status());
    }

    @Test
    void wrongAdminTokensAndWrongOldPasswordsAlsoCountTowardsTheThrottle() throws Exception {
        String throttled = startServer(new LoginThrottle(3, Duration.ofMinutes(5)), ADMIN_TOKEN);
        String token = send(throttled, "POST", "/api/register", Map.of("Content-Type", "application/json"),
            obj("username", "alice", "fullName", "Alice", "password", "Passw0rd1")).status() == 201
            ? send(throttled, "POST", "/api/login", Map.of("Content-Type", "application/json"),
                obj("username", "alice", "password", "Passw0rd1")).json().get("token").asText() : "";
        assertFalse(token.isEmpty());

        assertEquals(401, adminPost(throttled, "/api/admin/unlock", "guess-guess-guess-1", obj("username", "alice")).status());
        assertEquals(401, adminGet(throttled, "/api/admin/audit", "guess-guess-guess-2").status());
        Map<String, String> auth = new LinkedHashMap<>();
        auth.put("Content-Type", "application/json");
        auth.put("Authorization", "Bearer " + token);
        assertEquals(403, send(throttled, "POST", "/api/password", auth,
            obj("oldPassword", "Wrong1111", "newPassword", "N3wPassword")).status());
        // three failures from this client: the next attempt of any of these kinds is refused
        assertEquals(429, adminGet(throttled, "/api/admin/audit", ADMIN_TOKEN).status());
        assertEquals(429, send(throttled, "POST", "/api/password", auth,
            obj("oldPassword", "Passw0rd1", "newPassword", "N3wPassword")).status());
    }

    @Test
    void theThrottleIsPerClientAddressAndLeavesAnUnthrottledServerAlone() throws Exception {
        String throttled = startServer(new LoginThrottle(1, Duration.ofMinutes(5)), null);
        Map<String, String> json = Map.of("Content-Type", "application/json");
        send(throttled, "POST", "/api/login", json, obj("username", "ghost", "password", "Wrong1234"));
        assertEquals(429, send(throttled, "POST", "/api/login", json, obj("username", "ghost", "password", "Wrong1234")).status());
        // the main test server has no throttle at all
        for (int i = 0; i < 5; i++) {
            assertEquals(401, post("/api/login", null, obj("username", "ghost", "password", "Wrong1234")).status());
        }
    }
}
