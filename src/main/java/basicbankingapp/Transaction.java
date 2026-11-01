package basicbankingapp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** An immutable record of one deposit or withdrawal. */
public record Transaction(Type type, BigDecimal amount, BigDecimal balanceAfter, LocalDateTime timestamp) {

    public enum Type { DEPOSIT, WITHDRAWAL }

    @Override
    public String toString() {
        String label = (type == Type.DEPOSIT) ? "Deposited" : "Withdrawn";
        return String.format("%s | %s %s | balance %s", timestamp.withNano(0), label, amount, balanceAfter);
    }
}
