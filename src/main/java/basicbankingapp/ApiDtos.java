package basicbankingapp;

import java.math.BigDecimal;

/** Shapes of the JSON sent to and from the API. */
public final class ApiDtos {

    private ApiDtos() { }

    // ----- requests -----
    public record RegisterRequest(String username, String fullName, String password) { }

    public record LoginRequest(String username, String password) { }

    public record AmountRequest(BigDecimal amount) { }

    public record TransferRequest(String toAccountNumber, BigDecimal amount) { }

    public record ChangePasswordRequest(String oldPassword, String newPassword) { }

    public record UnlockRequest(String username) { }

    // ----- responses -----
    public record RegisterResponse(String username, String fullName, String accountNumber) { }

    public record LoginResponse(String token, String tokenType, long expiresInSeconds) { }

    public record AccountResponse(String accountNumber, String ownerName, BigDecimal balance) { }

    public record BalanceResponse(BigDecimal balance) { }

    public record TransactionResponse(String type, BigDecimal amount, String counterparty,
                                      BigDecimal balanceAfter, String timestamp) { }

    public record AuditEntryResponse(String timestamp, String type, String username, String detail) { }

    public record ErrorResponse(String error) { }

    public record StatusResponse(String status) { }
}
