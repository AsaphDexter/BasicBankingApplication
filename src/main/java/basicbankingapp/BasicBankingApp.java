package basicbankingapp;

import java.io.Console;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

/** Console menus. All banking rules live in {@link Bank} and {@link BankAccount}. */
public class BasicBankingApp {

    private final Bank bank;
    private final Scanner scanner;

    public static void main(String[] args) {
        new BasicBankingApp(new Bank(), new Scanner(System.in)).run();
    }

    BasicBankingApp(Bank bank, Scanner scanner) {
        this.bank = bank;
        this.scanner = scanner;
    }

    void run() {
        System.out.println("Welcome to Basic Banking App");
        char option;
        do {
            printStartMenu();
            option = readOption('E');
            switch (option) {
                case 'R' -> register();
                case 'L' -> {
                    User user = login();
                    if (user != null) {
                        runSession(user);
                    }
                }
                case 'E' -> System.out.println("Thank you for using our services. Goodbye!");
                default -> System.out.println("Invalid option, please try again.");
            }
        } while (option != 'E');
    }

    // ---------- start menu ----------

    private void printStartMenu() {
        System.out.println("""

            R. Register
            L. Log in
            E. Exit""");
    }

    private void register() {
        String username = readLine("Choose a username: ");
        String fullName = readLine("Full name: ");
        char[] password = readPassword("Choose a password (8+ characters, letters and numbers): ");
        char[] confirm = readPassword("Confirm password: ");
        boolean matches = Arrays.equals(password, confirm);
        Arrays.fill(confirm, '\0');
        if (!matches) {
            Arrays.fill(password, '\0');
            System.out.println("Registration failed: passwords do not match");
            return;
        }
        try {
            User user = bank.register(username, fullName, password);
            System.out.println("Registered. Your account number is " + user.getAccount().getCustomerId());
        } catch (RegistrationException e) {
            System.out.println("Registration failed: " + e.getMessage());
        }
    }

    private User login() {
        String username = readLine("Username: ");
        char[] password = readPassword("Password: ");
        try {
            return bank.login(username, password);
        } catch (AuthenticationException e) {
            System.out.println("Login failed: " + e.getMessage());
            return null;
        }
    }

    // ---------- signed-in menu ----------

    private void runSession(User user) {
        BankAccount account = user.getAccount();
        System.out.println("\nSigned in as " + user.getFullName());
        System.out.println("Account number: " + account.getCustomerId());
        char option;
        do {
            printAccountMenu();
            option = readOption('O');
            switch (option) {
                case 'A' -> System.out.println("Balance: " + account.getBalance());
                case 'B' -> deposit(account);
                case 'C' -> withdraw(account);
                case 'T' -> transfer(account);
                case 'D' -> showPrevious(account);
                case 'F' -> showHistory(account);
                case 'O' -> System.out.println("Signed out.");
                default -> System.out.println("Invalid option, please try again.");
            }
        } while (option != 'O');
    }

    private void printAccountMenu() {
        System.out.println("""

            A. Check balance
            B. Deposit
            C. Withdraw
            T. Transfer to another account
            D. Previous transaction
            F. Full transaction history
            O. Sign out""");
    }

    private void deposit(BankAccount account) {
        BigDecimal amount = readAmount("Enter an amount to deposit: ");
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

    private void withdraw(BankAccount account) {
        BigDecimal amount = readAmount("Enter an amount to withdraw: ");
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

    private void transfer(BankAccount account) {
        String toAccount = readLine("Recipient account number: ");
        BigDecimal amount = readAmount("Enter an amount to transfer: ");
        if (amount == null) {
            return;
        }
        try {
            bank.transfer(account.getCustomerId(), toAccount, amount);
            System.out.println("Transferred: " + amount + " to " + toAccount
                + ". New balance: " + account.getBalance());
        } catch (InsufficientFundsException | IllegalArgumentException e) {
            System.out.println("Transfer failed: " + e.getMessage());
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

    // ---------- input helpers ----------

    /** Returns {@code atEndOfInput} when there is no more input, so a closed stream cannot loop forever. */
    private char readOption(char atEndOfInput) {
        System.out.print("\nEnter an option: ");
        if (!scanner.hasNextLine()) {
            return atEndOfInput;
        }
        String line = scanner.nextLine().trim();
        return line.isEmpty() ? ' ' : Character.toUpperCase(line.charAt(0));
    }

    private String readLine(String prompt) {
        System.out.print(prompt);
        return scanner.hasNextLine() ? scanner.nextLine().trim() : "";
    }

    /** Hides typing when run in a real terminal; IDE run windows have no console, so it falls back to a visible prompt. */
    private char[] readPassword(String prompt) {
        Console console = System.console();
        if (console != null) {
            char[] password = console.readPassword("%s", prompt);
            return password == null ? new char[0] : password;
        }
        System.out.print(prompt);
        return (scanner.hasNextLine() ? scanner.nextLine() : "").toCharArray();
    }

    /** Returns null (after printing a message) when the input is not a number. */
    private BigDecimal readAmount(String prompt) {
        String text = readLine(prompt);
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            System.out.println("That is not a valid number.");
            return null;
        }
    }
}
