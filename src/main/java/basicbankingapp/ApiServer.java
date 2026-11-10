package basicbankingapp;

import basicbankingapp.ApiDtos.AccountResponse;
import basicbankingapp.ApiDtos.AmountRequest;
import basicbankingapp.ApiDtos.AuditEntryResponse;
import basicbankingapp.ApiDtos.ChangePasswordRequest;
import basicbankingapp.ApiDtos.BalanceResponse;
import basicbankingapp.ApiDtos.ErrorResponse;
import basicbankingapp.ApiDtos.LoginRequest;
import basicbankingapp.ApiDtos.LoginResponse;
import basicbankingapp.ApiDtos.RegisterRequest;
import basicbankingapp.ApiDtos.RegisterResponse;
import basicbankingapp.ApiDtos.StatusResponse;
import basicbankingapp.ApiDtos.TransactionResponse;
import basicbankingapp.ApiDtos.TransferRequest;
import basicbankingapp.ApiDtos.UnlockRequest;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A small JSON REST API over {@link Bank}, built on the JDK's own HTTP server.
 *
 * <pre>
 * GET  /api/health
 * POST /api/register      {username, fullName, password}
 * POST /api/login         {username, password}          -> bearer token
 * POST /api/logout                                       (needs token)
 * GET  /api/account                                      (needs token)
 * GET  /api/transactions                                 (needs token)
 * POST /api/deposit       {amount}                       (needs token)
 * POST /api/withdraw      {amount}                       (needs token)
 * POST /api/transfer      {toAccountNumber, amount}      (needs token)
 * POST /api/password      {oldPassword, newPassword}     (needs token)
 * GET  /api/openapi.yaml                                 the OpenAPI description of this API
 * POST /api/admin/unlock  {username}                     (needs the admin token)
 * GET  /api/admin/audit?username=&limit=                 (needs the admin token)
 * </pre>
 *
 * deposit, withdraw and transfer accept an optional {@code Idempotency-Key} header: sending the same key and
 * the same request again returns the first answer instead of moving the money twice.
 *
 * Every protected endpoint acts only on the account of the token's owner, so there is no
 * account-number parameter that could be changed to reach someone else's data.
 * This is plain HTTP meant for localhost; real use needs HTTPS in front of it.
 */
public final class ApiServer implements AutoCloseable {

    private static final int MAX_BODY_BYTES = 10_000;
    private static final String BEARER = "Bearer ";
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    private static final ObjectMapper JSON = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS) // "100" is not a number
        .withCoercionConfig(LogicalType.Textual, config -> config // 123, 1.5 and true are not text either
            .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
        .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
        .build()
        .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private final Bank bank;
    private final SessionStore sessions;
    private final LoginThrottle throttle;
    private final String adminToken; // null: the admin endpoints do not exist
    private final HttpServer server;
    private final byte[] openApiDocument; // null if the spec is not on the classpath
    private final ExecutorService executor = Executors.newFixedThreadPool(8);

    /** No throttling and no admin endpoints. */
    public ApiServer(Bank bank, SessionStore sessions, InetSocketAddress address) throws IOException {
        this(bank, sessions, LoginThrottle.disabled(), null, address);
    }

    /**
     * Binds to the address now; call {@link #start()} to begin answering requests. Use port 0 for any free port.
     *
     * @param throttle   limits failed logins per client address
     * @param adminToken secret for the admin endpoints, or null to switch them off
     */
    public ApiServer(Bank bank, SessionStore sessions, LoginThrottle throttle, String adminToken,
                     InetSocketAddress address) throws IOException {
        this.bank = bank;
        this.sessions = sessions;
        this.throttle = throttle;
        this.adminToken = adminToken;
        this.openApiDocument = loadOpenApiDocument();
        this.server = HttpServer.create(address, 0);
        this.server.createContext("/", this::handle);
        this.server.setExecutor(executor);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    // ---------- request handling ----------

    private void handle(HttpExchange exchange) throws IOException {
        try {
            route(exchange);
        } catch (ApiException e) {
            if (e.retryAfterSeconds() != null) {
                exchange.getResponseHeaders().set("Retry-After", String.valueOf(e.retryAfterSeconds()));
            }
            send(exchange, e.status(), new ErrorResponse(e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace(); // the details stay in the server log, not in the response
            send(exchange, 500, new ErrorResponse("Internal server error"));
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        switch (path) {
            case "/api/health" -> {
                requireMethod(ex, "GET");
                send(ex, 200, new StatusResponse("UP"));
            }
            case "/api/register" -> {
                requireMethod(ex, "POST");
                register(ex);
            }
            case "/api/login" -> {
                requireMethod(ex, "POST");
                login(ex);
            }
            case "/api/logout" -> {
                requireMethod(ex, "POST");
                authenticate(ex);
                sessions.revoke(bearerToken(ex));
                sendNoContent(ex);
            }
            case "/api/account" -> {
                requireMethod(ex, "GET");
                account(ex);
            }
            case "/api/transactions" -> {
                requireMethod(ex, "GET");
                transactions(ex);
            }
            case "/api/deposit" -> {
                requireMethod(ex, "POST");
                deposit(ex);
            }
            case "/api/withdraw" -> {
                requireMethod(ex, "POST");
                withdraw(ex);
            }
            case "/api/transfer" -> {
                requireMethod(ex, "POST");
                transfer(ex);
            }
            case "/api/openapi.yaml" -> {
                requireMethod(ex, "GET");
                openApi(ex);
            }
            case "/api/password" -> {
                requireMethod(ex, "POST");
                changePassword(ex);
            }
            case "/api/admin/unlock" -> {
                requireMethod(ex, "POST");
                adminUnlock(ex);
            }
            case "/api/admin/audit" -> {
                requireMethod(ex, "GET");
                adminAudit(ex);
            }
            default -> throw new ApiException(404, "Not found");
        }
    }

    // ---------- public endpoints ----------

    private void register(HttpExchange ex) throws IOException {
        RegisterRequest request = readJson(ex, RegisterRequest.class);
        require(request.username(), "username");
        require(request.fullName(), "fullName");
        require(request.password(), "password");
        try {
            User user = bank.register(request.username(), request.fullName(), request.password().toCharArray());
            send(ex, 201, new RegisterResponse(user.getUsername(), user.getFullName(),
                user.getAccount().getCustomerId()));
        } catch (UsernameTakenException e) {
            throw new ApiException(409, e.getMessage());
        } catch (RegistrationException e) {
            throw new ApiException(400, e.getMessage());
        }
    }

    private void login(HttpExchange ex) throws IOException {
        String client = clientAddress(ex);
        checkNotThrottled(client);
        LoginRequest request = readJson(ex, LoginRequest.class);
        require(request.username(), "username");
        require(request.password(), "password");
        try {
            User user = bank.login(request.username(), request.password().toCharArray(), client);
            String token = sessions.create(user.getUsername());
            send(ex, 200, new LoginResponse(token, "Bearer", sessions.getLifetime().toSeconds()));
        } catch (AccountLockedException e) {
            throttle.recordFailure(client);
            throw new ApiException(423, e.getMessage(), e.getRetryAfterSeconds());
        } catch (AuthenticationException e) {
            throttle.recordFailure(client);
            throw new ApiException(401, e.getMessage());
        }
    }

    // ---------- protected endpoints ----------

    private void account(HttpExchange ex) throws IOException {
        User user = authenticate(ex);
        BankAccount account = bank.findAccount(accountNumber(user));
        send(ex, 200, new AccountResponse(account.getCustomerId(), account.getCustomerName(), account.getBalance()));
    }

    private void transactions(HttpExchange ex) throws IOException {
        User user = authenticate(ex);
        List<TransactionResponse> history = bank.findAccount(accountNumber(user)).getHistory().stream()
            .map(t -> new TransactionResponse(t.type().name(), t.amount(), t.counterparty(),
                t.balanceAfter(),
                DateTimeFormatter.ISO_INSTANT.format(t.timestamp().toInstant(ZoneOffset.UTC))))
            .toList();
        send(ex, 200, history);
    }

    private void deposit(HttpExchange ex) throws IOException {
        User user = authenticate(ex);
        String key = idempotencyKey(ex);
        BigDecimal amount = requireAmount(readJson(ex, AmountRequest.class).amount());
        moneyOperation(ex, user, key, fingerprint("/api/deposit", canonical(amount)),
            () -> bank.deposit(accountNumber(user), amount));
    }

    private void withdraw(HttpExchange ex) throws IOException {
        User user = authenticate(ex);
        String key = idempotencyKey(ex);
        BigDecimal amount = requireAmount(readJson(ex, AmountRequest.class).amount());
        moneyOperation(ex, user, key, fingerprint("/api/withdraw", canonical(amount)),
            () -> bank.withdraw(accountNumber(user), amount));
    }

    private void transfer(HttpExchange ex) throws IOException {
        User user = authenticate(ex);
        String key = idempotencyKey(ex);
        TransferRequest request = readJson(ex, TransferRequest.class);
        require(request.toAccountNumber(), "toAccountNumber");
        BigDecimal amount = requireAmount(request.amount());
        String recipient = request.toAccountNumber().trim();
        moneyOperation(ex, user, key, fingerprint("/api/transfer", recipient, canonical(amount)),
            () -> bank.transfer(accountNumber(user), recipient, amount, user.getUsername()));
    }

    /**
     * Runs a money-moving request and answers with the caller's new balance. With an Idempotency-Key the
     * request runs at most once: a repeat gets the first answer back (header Idempotent-Replayed: true).
     */
    private void moneyOperation(HttpExchange ex, User user, String key, String fingerprint, Runnable operation)
        throws IOException {
        String account = accountNumber(user);
        Supplier<String> action = () -> {
            perform(operation);
            return toJson(new BalanceResponse(bank.findAccount(account).getBalance()));
        };
        if (key == null) {
            sendJson(ex, 200, action.get());
            return;
        }
        Bank.OnceResult outcome;
        try {
            outcome = bank.executeOnce(user.getUsername(), key, fingerprint, action);
        } catch (IdempotencyKeyReusedException e) {
            throw new ApiException(422, e.getMessage());
        }
        ex.getResponseHeaders().set("Idempotent-Replayed", String.valueOf(outcome.replayed()));
        sendJson(ex, 200, outcome.result());
    }

    private void openApi(HttpExchange ex) throws IOException {
        if (openApiDocument == null) {
            throw new ApiException(404, "Not found");
        }
        addCommonHeaders(ex);
        ex.getResponseHeaders().set("Content-Type", "application/yaml; charset=utf-8");
        ex.sendResponseHeaders(200, openApiDocument.length);
        ex.getResponseBody().write(openApiDocument);
    }

    private void changePassword(HttpExchange ex) throws IOException {
        User user = authenticate(ex);
        String client = clientAddress(ex);
        checkNotThrottled(client);
        ChangePasswordRequest request = readJson(ex, ChangePasswordRequest.class);
        require(request.oldPassword(), "oldPassword");
        require(request.newPassword(), "newPassword");
        try {
            bank.changePassword(user.getUsername(), request.oldPassword().toCharArray(),
                request.newPassword().toCharArray(), client);
        } catch (AccountLockedException e) {
            throttle.recordFailure(client);
            throw new ApiException(423, e.getMessage(), e.getRetryAfterSeconds());
        } catch (AuthenticationException e) {
            throttle.recordFailure(client);
            throw new ApiException(403, e.getMessage());
        } catch (RegistrationException e) {
            throw new ApiException(400, e.getMessage());
        }
        sessions.revokeAllExcept(user.getUsername(), bearerToken(ex)); // other devices must sign in again
        sendNoContent(ex);
    }

    // ---------- admin endpoints ----------

    private void adminUnlock(HttpExchange ex) throws IOException {
        requireAdmin(ex);
        UnlockRequest request = readJson(ex, UnlockRequest.class);
        require(request.username(), "username");
        try {
            bank.unlock(request.username());
        } catch (UserNotFoundException e) {
            throw new ApiException(404, e.getMessage());
        }
        sendNoContent(ex);
    }

    private void adminAudit(HttpExchange ex) throws IOException {
        requireAdmin(ex);
        Map<String, String> query = queryParameters(ex);
        int limit = 50;
        if (query.containsKey("limit")) {
            try {
                limit = Integer.parseInt(query.get("limit"));
            } catch (NumberFormatException e) {
                throw new ApiException(400, "limit must be a whole number from 1 to 200");
            }
            if (limit < 1 || limit > 200) {
                throw new ApiException(400, "limit must be a whole number from 1 to 200");
            }
        }
        List<AuditEntryResponse> entries = bank.auditLog(query.get("username"), limit).stream()
            .map(event -> new AuditEntryResponse(event.time().toString(), event.type().name(),
                event.username(), event.detail()))
            .toList();
        send(ex, 200, entries);
    }

    private void requireAdmin(HttpExchange ex) {
        if (adminToken == null) {
            throw new ApiException(404, "Not found"); // the admin API is switched off, so it does not exist
        }
        String client = clientAddress(ex);
        checkNotThrottled(client);
        String supplied = ex.getRequestHeaders().getFirst("X-Admin-Token");
        if (supplied == null || !sameSecret(supplied, adminToken)) {
            throttle.recordFailure(client);
            throw new ApiException(401, "Admin token required");
        }
    }

    /** Compares secrets in constant time, whatever their lengths. */
    private static boolean sameSecret(String supplied, String expected) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return MessageDigest.isEqual(sha.digest(supplied.getBytes(StandardCharsets.UTF_8)),
                sha.digest(expected.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- helpers ----------

    private void checkNotThrottled(String client) {
        throttle.retryAfter(client).ifPresent(wait -> {
            throw new ApiException(429, "Too many failed attempts. Try again later",
                Math.max(1, (wait.toMillis() + 999) / 1000));
        });
    }

    private static String clientAddress(HttpExchange ex) {
        return ex.getRemoteAddress().getAddress().getHostAddress();
    }

    private static Map<String, String> queryParameters(HttpExchange ex) {
        Map<String, String> parameters = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return parameters;
        }
        for (String pair : raw.split("&")) {
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            try {
                parameters.put(URLDecoder.decode(name, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw new ApiException(400, "Bad query string");
            }
        }
        return parameters;
    }

    /** The Idempotency-Key header, or null if there is none. A bad or repeated header is a 400. */
    private static String idempotencyKey(HttpExchange ex) {
        List<String> values = ex.getRequestHeaders().get("Idempotency-Key");
        if (values == null) {
            return null;
        }
        if (values.size() != 1 || !IDEMPOTENCY_KEY.matcher(values.get(0).trim()).matches()) {
            throw new ApiException(400, "Idempotency-Key must be 8 to 64 letters, numbers, hyphens or underscores, sent once");
        }
        return values.get(0).trim();
    }

    /** The same amount written differently (10, 10.00, 1e1) gives the same text. Cheap even for 1e999999999. */
    private static String canonical(BigDecimal amount) {
        return amount.stripTrailingZeros().toString();
    }

    /** A fixed-size summary of a request, used to notice a key being reused for something else. */
    private static String fingerprint(String... parts) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(String.join("\n", parts).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String toJson(Object body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (IOException e) { // in practice a JsonProcessingException

            throw new IllegalStateException(e);
        }
    }

    private static byte[] loadOpenApiDocument() {
        try (InputStream in = ApiServer.class.getResourceAsStream("/openapi.yaml")) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    private static String accountNumber(User user) {
        return user.getAccount().getCustomerId();
    }

    /** Turns the bank's rule violations into the right HTTP status. */
    private static void perform(Runnable operation) {
        try {
            operation.run();
        } catch (InsufficientFundsException e) {
            throw new ApiException(422, e.getMessage());
        } catch (AccountNotFoundException e) {
            throw new ApiException(404, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
    }

    private User authenticate(HttpExchange ex) {
        String token = bearerToken(ex);
        User user = sessions.usernameFor(token).map(bank::getUser).orElse(null);
        if (user == null) {
            ex.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            throw new ApiException(401, "Missing or invalid token");
        }
        return user;
    }

    private static String bearerToken(HttpExchange ex) {
        String header = ex.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return null;
        }
        return header.substring(BEARER.length()).trim();
    }

    private static void requireMethod(HttpExchange ex, String allowed) {
        if (!allowed.equals(ex.getRequestMethod())) {
            ex.getResponseHeaders().set("Allow", allowed);
            throw new ApiException(405, "Method not allowed. Use " + allowed);
        }
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ApiException(400, field + " is required");
        }
    }

    private static BigDecimal requireAmount(BigDecimal amount) {
        if (amount == null) {
            throw new ApiException(400, "amount is required");
        }
        return amount;
    }

    private static <T> T readJson(HttpExchange ex, Class<T> type) throws IOException {
        String contentType = ex.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            throw new ApiException(415, "Content-Type must be application/json");
        }
        byte[] body = ex.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            throw new ApiException(413, "Request body is too large");
        }
        try {
            T value = JSON.readValue(body, type);
            if (value == null) {
                throw new ApiException(400, "Request body is required");
            }
            return value;
        } catch (JsonProcessingException e) {
            throw new ApiException(400, "Request body is not valid JSON for this endpoint");
        }
    }

    private static void send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        addCommonHeaders(ex);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    /** Sends JSON that is already text (a stored answer being replayed, or one built for storing). */
    private static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        addCommonHeaders(ex);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    private static void sendNoContent(HttpExchange ex) throws IOException {
        addCommonHeaders(ex);
        ex.sendResponseHeaders(204, -1);
    }

    private static void addCommonHeaders(HttpExchange ex) {
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    }
}
