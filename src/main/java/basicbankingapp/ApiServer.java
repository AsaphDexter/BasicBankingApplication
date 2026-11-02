package basicbankingapp;

import basicbankingapp.ApiDtos.AccountResponse;
import basicbankingapp.ApiDtos.AmountRequest;
import basicbankingapp.ApiDtos.BalanceResponse;
import basicbankingapp.ApiDtos.ErrorResponse;
import basicbankingapp.ApiDtos.LoginRequest;
import basicbankingapp.ApiDtos.LoginResponse;
import basicbankingapp.ApiDtos.RegisterRequest;
import basicbankingapp.ApiDtos.RegisterResponse;
import basicbankingapp.ApiDtos.StatusResponse;
import basicbankingapp.ApiDtos.TransactionResponse;
import basicbankingapp.ApiDtos.TransferRequest;
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
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.util.List;
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
 * </pre>
 *
 * Every protected endpoint acts only on the account of the token's owner, so there is no
 * account-number parameter that could be changed to reach someone else's data.
 * This is plain HTTP meant for localhost; real use needs HTTPS in front of it.
 */
public final class ApiServer implements AutoCloseable {

    private static final int MAX_BODY_BYTES = 10_000;
    private static final String BEARER = "Bearer ";

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
    private final HttpServer server;
    private final ExecutorService executor = Executors.newFixedThreadPool(8);

    /** Binds to the address now; call {@link #start()} to begin answering requests. Use port 0 for any free port. */
    public ApiServer(Bank bank, SessionStore sessions, InetSocketAddress address) throws IOException {
        this.bank = bank;
        this.sessions = sessions;
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
        LoginRequest request = readJson(ex, LoginRequest.class);
        require(request.username(), "username");
        require(request.password(), "password");
        try {
            User user = bank.login(request.username(), request.password().toCharArray());
            String token = sessions.create(user.getUsername());
            send(ex, 200, new LoginResponse(token, "Bearer", sessions.getLifetime().toSeconds()));
        } catch (AccountLockedException e) {
            throw new ApiException(423, e.getMessage());
        } catch (AuthenticationException e) {
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
                t.balanceAfter(), t.timestamp().toString()))
            .toList();
        send(ex, 200, history);
    }

    private void deposit(HttpExchange ex) throws IOException {
        User user = authenticate(ex);
        BigDecimal amount = requireAmount(readJson(ex, AmountRequest.class).amount());
        perform(() -> bank.deposit(accountNumber(user), amount));
        send(ex, 200, new BalanceResponse(bank.findAccount(accountNumber(user)).getBalance()));
    }

    private void withdraw(HttpExchange ex) throws IOException {
        User user = authenticate(ex);
        BigDecimal amount = requireAmount(readJson(ex, AmountRequest.class).amount());
        perform(() -> bank.withdraw(accountNumber(user), amount));
        send(ex, 200, new BalanceResponse(bank.findAccount(accountNumber(user)).getBalance()));
    }

    private void transfer(HttpExchange ex) throws IOException {
        User user = authenticate(ex);
        TransferRequest request = readJson(ex, TransferRequest.class);
        require(request.toAccountNumber(), "toAccountNumber");
        BigDecimal amount = requireAmount(request.amount());
        perform(() -> bank.transfer(accountNumber(user), request.toAccountNumber().trim(), amount));
        send(ex, 200, new BalanceResponse(bank.findAccount(accountNumber(user)).getBalance()));
    }

    // ---------- helpers ----------

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

    private static void sendNoContent(HttpExchange ex) throws IOException {
        addCommonHeaders(ex);
        ex.sendResponseHeaders(204, -1);
    }

    private static void addCommonHeaders(HttpExchange ex) {
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    }
}
