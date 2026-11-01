package basicbankingapp;

import java.math.BigDecimal;
import java.util.List;
import java.util.Scanner;

/** Console menu. All banking rules live in {@link BankAccount}. */
public class BasicBankingApp {

    public static void main(String[] args) {
        BankAccount account = new BankAccount("Strawberry", "JX11004");
        new BasicBankingApp().run(account, new Scanner(System.in));
    }

    void run(BankAccount account, Scanner scanner) {
        System.out.println("Welcome, " + account.getCustomerName());
        System.out.println("Your ID is: " + account.getCustomerId());
        printMenu();

        char option;
        do {
            System.out.print("\nEnter an option: ");
            String line = scanner.hasNextLine() ? scanner.nextLine().trim() : "E";
            option = line.isEmpty() ? ' ' : Character.toUpperCase(line.charAt(0));

            switch (option) {
                case 'A' -> System.out.println("Balance: " + account.getBalance());
                case 'B' -> handleDeposit(account, scanner);
                case 'C' -> handleWithdrawal(account, scanner);
                case 'D' -> showPrevious(account);
                case 'F' -> showHistory(account);
                case 'E' -> System.out.println("Thank you for using our services. Goodbye!");
                default -> {
                    System.out.println("Invalid option, please try again.");
                    printMenu();
                }
            }
        } while (option != 'E');
    }

    private void printMenu() {
        System.out.println("""

            A. Check balance
            B. Deposit
            C. Withdraw
            D. Previous transaction
            F. Full transaction history
            E. Exit""");
    }

    private void handleDeposit(BankAccount account, Scanner scanner) {
        BigDecimal amount = readAmount("Enter an amount to deposit: ", scanner);
        if (amount == null) {
            return;
        }
        try {
            account.deposit(amount);
            System.out.println("Deposited: " + amount + ". New balance: " + account.getBalance());
        } catch (IllegalArgumentException e) {
            System.out.println("Deposit failed: " + e.getMessage());
        }
    }

    private void handleWithdrawal(BankAccount account, Scanner scanner) {
        BigDecimal amount = readAmount("Enter an amount to withdraw: ", scanner);
        if (amount == null) {
            return;
        }
        try {
            account.withdraw(amount);
            System.out.println("Withdrawn: " + amount + ". New balance: " + account.getBalance());
        } catch (InsufficientFundsException | IllegalArgumentException e) {
            System.out.println("Withdrawal failed: " + e.getMessage());
        }
    }

    private void showPrevious(BankAccount account) {
        System.out.println(account.getPreviousTransaction()
            .map(Transaction::toString)
            .orElse("No transactions yet."));
    }

    private void showHistory(BankAccount account) {
        List<Transaction> history = account.getHistory();
        if (history.isEmpty()) {
            System.out.println("No transactions yet.");
            return;
        }
        history.forEach(System.out::println);
    }

    /** Returns null (after printing a message) when the input is not a number. */
    private BigDecimal readAmount(String prompt, Scanner scanner) {
        System.out.print(prompt);
        String text = scanner.hasNextLine() ? scanner.nextLine().trim() : "";
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            System.out.println("That is not a valid number.");
            return null;
        }
    }
}
