# Basic Banking Application

[![Build and test](https://github.com/AsaphDexter/BasicBankingApplication/actions/workflows/build.yml/badge.svg)](https://github.com/AsaphDexter/BasicBankingApplication/actions/workflows/build.yml)

A console banking app in Java. It supports deposits, withdrawals, balance checks and a transaction history, with the banking rules kept separate from the menu so they can be unit tested.

## Features

- Check balance
- Deposit and withdraw, with validation:
  - amounts must be greater than zero
  - at most 2 decimal places
  - a withdrawal cannot exceed the balance (`InsufficientFundsException`)
- View the previous transaction or the full history (type, amount, balance after, timestamp)
- Menu options are case-insensitive and bad input does not crash the app

## Design

| Class | Responsibility |
|---|---|
| `BankAccount` | Balance, rules and history. No console I/O. |
| `Transaction` | Immutable record of one deposit or withdrawal. |
| `InsufficientFundsException` | Raised for overdraft attempts. |
| `BasicBankingApp` | Console menu and input handling only. |

Money uses `BigDecimal`, not `int` or `double`, to avoid rounding errors.

## Run it

Requires Java 17+ and Maven.

```bash
mvn -B verify          # build and run the tests
java -cp target/classes basicbankingapp.BasicBankingApp
```

## Tests

JUnit 5 tests in `src/test/java/basicbankingapp/BankAccountTest.java` cover deposits, withdrawals, the exact-balance edge case, overdraft rejection, zero/negative/null/too-many-decimals amounts, history order, and that rejected operations leave no record. GitHub Actions runs them on every push.

## Possible next steps

- Transfers between two accounts
- Persistence (file or database)
- A REST API with the same test suite around it
