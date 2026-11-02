# Basic Banking Application

[![Build and test](https://github.com/AsaphDexter/BasicBankingApplication/actions/workflows/build.yml/badge.svg)](https://github.com/AsaphDexter/BasicBankingApplication/actions/workflows/build.yml)

A console banking app in Java with multiple users, secure sign-in, transfers and a real SQL database (H2, through JDBC). The banking rules are kept separate from both the menus and the storage, so each part can be tested on its own.

## Features

- **Register and log in** (username + password)
  - usernames: 3-20 letters, numbers or underscores, not case-sensitive
  - passwords: 8+ characters with a letter and a number
  - passwords are never stored: only a salted PBKDF2-HMAC-SHA256 hash (600,000 iterations)
  - wrong username and wrong password give the same message
  - an account locks after 3 failed logins in a row
- **Each user has an account** with a generated account number
- **Deposit, withdraw, check balance**, with validation (amount above zero, at most 2 decimal places, no overdraft)
- **Transfer money to another user's account**; a transfer is all-or-nothing, so a failure leaves both balances unchanged
- **Transaction history** (type, amount, other account, balance after, timestamp)
- **Data is saved** in an embedded H2 database and is still there after the app restarts

## Design

| Class | Responsibility |
|---|---|
| `Bank` | The rules: registration, login, lockout, deposits, withdrawals, transfers |
| `BankRepository` | Interface for storage (the `Bank` only talks to this) |
| `JdbcBankRepository` | H2 database through JDBC: prepared statements, transactions, constraints |
| `InMemoryBankRepository` | Same interface, kept in memory (used for `--memory` demos and tests) |
| `User`, `BankAccount`, `Transaction` | The domain objects |
| `PasswordHasher` | Salted PBKDF2 hashing and constant-time verification (JDK only) |
| `BasicBankingApp` | Console menus and input handling only |

### Database

Tables `users`, `accounts` and `transactions`, created automatically on first run. The database enforces key rules itself, even if the Java checks were bypassed:

- usernames are unique
- an account balance can never be negative
- a transaction must belong to a real account
- every query uses a `PreparedStatement`, so SQL injection is not possible
- money movements run inside a database transaction, so a crash or error halfway rolls everything back

Money uses `BigDecimal` / `DECIMAL(19,2)`, never floating point.

## Run it

Requires Java 17+ and Maven.

```bash
mvn -B verify      # build and run all tests
```

To start the app, run `BasicBankingApp.main` from IntelliJ (green play icon), or from a terminal:

```bash
mvn -q compile exec:java -Dexec.mainClass=basicbankingapp.BasicBankingApp
```

Data is saved in the `data/` folder next to where you run it (ignored by Git). For a throwaway demo that forgets everything, add `-Dexec.args="--memory"`.

Run it from a real terminal to hide your password as you type; an IDE run window shows it.

## Tests

JUnit 5, about 97 tests. The same bank tests run against both storage options:

- `AbstractBankTest`, run by `InMemoryBankTest` and `JdbcBankTest`: registration, login, lockout, deposits, withdrawals, transfers, history
- `JdbcBankTest` also checks what only a database can do: data survives a restart, a failed transfer is rolled back completely, the database refuses negative balances, duplicate usernames and orphan transactions, passwords are stored only as hashes, and SQL-injection-style input is treated as plain text
- `BankAccountTest`, `PasswordHasherTest`, `BasicBankingAppTest` (end-to-end menu flows with scripted input)

GitHub Actions runs them on every push.

## Limitations

This is a learning project, not a real bank. The database file is not encrypted, only one app instance can use it at a time, and it has no audit log, session handling or multi-factor authentication. Real banking software needs all of those plus regulated security controls.

## Next steps

- Admin unlock for locked users
- Move to a server database such as PostgreSQL (only a new `BankRepository` is needed)
- A REST API with the same test suite around it
