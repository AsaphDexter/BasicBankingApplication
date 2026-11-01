# Basic Banking Application

[![Build and test](https://github.com/AsaphDexter/BasicBankingApplication/actions/workflows/build.yml/badge.svg)](https://github.com/AsaphDexter/BasicBankingApplication/actions/workflows/build.yml)

A console banking app in Java with multiple users, sign-in and transfers. The banking and security rules are kept separate from the menus so they can be unit tested.

## Features

- **Register and log in** (username + password)
  - usernames: 3-20 letters, numbers or underscores, not case-sensitive
  - passwords: 8+ characters with a letter and a number
  - passwords are never stored: only a salted PBKDF2-HMAC-SHA256 hash (600,000 iterations)
  - wrong username and wrong password give the same message
  - an account locks after 3 failed logins in a row
- **Each user has an account** with a generated account number
- **Deposit, withdraw, check balance**, with validation:
  - amounts must be greater than zero, with at most 2 decimal places
  - a withdrawal cannot exceed the balance (`InsufficientFundsException`)
- **Transfer money to another user's account** by account number; a failed transfer changes neither balance
- **Transaction history** (type, amount, other account, balance after, timestamp)

## Design

| Class | Responsibility |
|---|---|
| `Bank` | Registration, login, lockout, account lookup and transfers |
| `User` | Username, name, password hash, account, failed-login state |
| `PasswordHasher` | Salted PBKDF2 hashing and constant-time verification (JDK only) |
| `BankAccount` | Balance, deposit/withdraw rules and history. No console I/O |
| `Transaction` | Immutable record of one money movement |
| `BasicBankingApp` | Console menus and input handling only |
| `*Exception` classes | `InsufficientFundsException`, `RegistrationException`, `AuthenticationException`, `AccountLockedException` |

Money uses `BigDecimal`, not `int` or `double`, to avoid rounding errors.

## Run it

Requires Java 17+ and Maven.

```bash
mvn -B verify          # build and run the tests
java -cp target/classes basicbankingapp.BasicBankingApp
```

Run it from a real terminal to hide your password as you type. In an IDE run window the password is visible.

## Tests

JUnit 5 tests in `src/test/java/basicbankingapp/` cover:

- `BankAccountTest`: deposits, withdrawals, overdraft, bad amounts, history
- `PasswordHasherTest`: hashing, salting, wrong passwords, malformed stored values
- `BankTest`: registration rules, login, lockout and unlock, transfers (including failures and total money staying constant)
- `BasicBankingAppTest`: end-to-end menu flows with scripted input

GitHub Actions runs them on every push.

## Limitations

This is a learning project. Data is kept in memory and lost when the app exits, and it is not thread-safe. A real bank also needs audit logs, session handling, multi-factor authentication and regulated security controls.

## Next steps

- Store users and transactions in a database (the in-memory storage will move behind a repository interface)
- Admin unlock for locked users
