package basicbankingapp;

import java.io.Console;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

/** Console menus. All banking rules live in {@link Bank} and {@link BankAccount}. */
public class BasicBankingApp {

    private final Bank bank;
    private final Scanner scanner;

    /** Saves to {@code data/bank} by default. Pass {@code --memory} for a throwaway demo that forgets everything. */
    public static void main(String[] args) {
        boolean memoryOnly = args.length > 0 && args[0].equals("--memory");
        try (BankRepository repository = memoryOnly
                 ? new InMemoryBankRepository()
                 : JdbcBankRepository.file(Path.of("data", "bank"))) {
            System.out.println(memoryOnly
                ? "Demo mode: nothing will be saved."
                : "Data is saved in the data folder.");
            new BasicBankingApp(new Bank(new PasswordHasher(), repository), new Scanner(System.in)).run();
        }
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
        String accountNumber = user.getAccount().getCustomerId();
        System.out.println("\nSigned in as " + user.getFullName());
        System.out.println("Account number: " + accountNumber);
        char option;
        do {
            printAccountMenu();
            option = readOption('O');
            switch (option) {
                case 'A' -> System.out.println("Balance: " + balanceOf(accountNumber));
                case 'B' -> deposit(accountNumber);
                case 'C' -> withdraw(accountNumber);
                case 'T' -> transfer(accountNumber);
                case 'D' -> showPrevious(accountNumber);
                case 'F' -> showHistory(accountNumber);
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

    private void deposit(String accountNumber) {
        BigDecimal amount = readAmount("Enter an amount to deposit: ");
        if (amount == null) {
            return;
        }
        try {
            bank.deposit(accountNumber, amount);
            System.out.println("Deposited: " + amount + ". New balance: " + balanceOf(accountNumber));
        } catch (IllegalArgumentException e) {
            System.out.println("Deposit failed: " + e.getMessage());
        }
    }

    private void withdraw(String accountNumber) {
        BigDecimal amount = readAmount("Enter an amount to withdraw: ");
        if (amount == null) {
            return;
        }
        try {
            bank.withdraw(accountNumber, amount);
            System.out.println("Withdrawn: " + amount + ". New balance: " + balanceOf(accountNumber));
        } catch (InsufficientFundsException | IllegalArgumentException e) {
            System.out.println("Withdrawal failed: " + e.getMessage());
        }
    }

    private void transfer(String accountNumber) {
        String toAccount = readLine("Recipient account number: ");
        BigDecimal amount = readAmount("Enter an amount to transfer: ");
        if (amount == null) {
            return;
        }
        try {
            bank.transfer(accountNumber, toAccount, amount);
            System.out.println("Transferred: " + amount + " to " + toAccount
                + ". New balance: " + balanceOf(accountNumber));
        } catch (InsufficientFundsException | IllegalArgumentException e) {
            System.out.println("Transfer failed: " + e.getMessage());
        }
    }

    private void showPrevious(String accountNumber) {
        System.out.println(bank.findAccount(accountNumber).getPreviousTransaction()
            .map(Transaction::toString)
            .orElse("No transactions yet."));
    }

    private void showHistory(String accountNumber) {
        List<Transaction> history = bank.findAccount(accountNumber).getHistory();
        if (history.isEmpty()) {
            System.out.println("No transactions yet.");
            return;
        }
        history.forEach(System.out::println);
    }

    private BigDecimal balanceOf(String accountNumber) {
        return bank.findAccount(accountNumber).getBalance();
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
