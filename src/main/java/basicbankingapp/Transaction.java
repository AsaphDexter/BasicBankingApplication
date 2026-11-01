package basicbankingapp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * An immutable record of one money movement.
 * {@code counterparty} is the other account number for transfers, otherwise null.
 */
public record Transaction(Type type, BigDecimal amount, BigDecimal balanceAfter,
                          LocalDateTime timestamp, String counterparty) {

    public enum Type { DEPOSIT, WITHDRAWAL, TRANSFER_OUT, TRANSFER_IN }

    @Override
    public String toString() {
        String label = switch (type) {
            case DEPOSIT -> "Deposited";
            case WITHDRAWAL -> "Withdrawn";
            case TRANSFER_OUT -> "Transferred out";
            case TRANSFER_IN -> "Transfer received";
        };
        String other = counterparty == null ? ""
            : (type == Type.TRANSFER_OUT ? " to " : " from ") + counterparty;
        return String.format("%s | %s %s%s | balance %s", timestamp.withNano(0), label, amount, other, balanceAfter);
    }
}
