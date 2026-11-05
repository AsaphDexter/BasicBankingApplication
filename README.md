# Basic Banking Application

[![Build and test](https://github.com/AsaphDexter/BasicBankingApplication/actions/workflows/build.yml/badge.svg)](https://github.com/AsaphDexter/BasicBankingApplication/actions/workflows/build.yml)

A Java banking app with multiple users, secure sign-in, transfers, a real SQL database (H2 through JDBC) and a JSON REST API. It has a console version and an HTTP version, both sitting on the same rules and storage, so each layer can be tested on its own.

## Features

- **Register and log in** (username + password)
  - usernames: 3-20 letters, numbers or underscores, not case-sensitive
  - passwords: 8+ characters with a letter and a number, stored only as a salted PBKDF2-HMAC-SHA256 hash
  - wrong username and wrong password give the same message
  - an account locks for 15 minutes after 3 failed logins in a row, and the response says how long to wait
  - failed logins are also limited per client address (429 when exceeded)
- **Deposit, withdraw, transfer, balance, history**, with validation (amount above zero, at most 2 decimals, no overdraft, per-transaction and balance limits)
- **All-or-nothing money movements**: a failed transfer leaves both balances unchanged
- **Data is saved** in an embedded H2 database and survives restarts
- **REST API** with token-based sign-in (below)
- **Change password**, which ends the user's other sessions
- **Audit log** of registrations, logins, failures, locks, unlocks, password changes and transfers
- **Admin endpoints** (off unless an admin token is set) to unlock a user and read the audit log

## Design

| Class | Responsibility |
|---|---|
| `Bank` | The rules: registration, login, timed lockout, password change, deposits, withdrawals, transfers, audit. Thread-safe, and password hashing runs outside its lock so a sign-in never blocks other customers |
| `BankRepository` | Interface for storage |
| `JdbcBankRepository` | H2 database through JDBC: prepared statements, transactions, constraints |
| `InMemoryBankRepository` | Same interface, kept in memory |
| `ApiServer` | The REST API on the JDK's built-in HTTP server, JSON via Jackson |
| `SessionStore` | Random 256-bit login tokens that expire and can be revoked |
| `LoginThrottle` | Limits failed logins per client address (successes do not reset the count) |
| `AuditEvent` | One entry in the audit log (never contains passwords, hashes or tokens) |
| `BasicBankingApp` / `BankingApiApp` | Console menus / API entry point |
| `User`, `BankAccount`, `Transaction`, `PasswordHasher` | Domain objects and password hashing |

## Run it

Requires Java 17+ and Maven.

```bash
mvn -B verify      # build and run all tests
```

Run `BasicBankingApp.main` for the console version, or `BankingApiApp.main` for the API, from IntelliJ (green play icon). Data is saved in the `data/` folder (ignored by Git). Do not run the console app and the API on the same `data/` folder at the same time.

API options (program arguments):

| Option | Default | Meaning |
|---|---|---|
| `--memory` | off | throwaway data instead of the saved database |
| `--port N` | 8080 | port to listen on (localhost only) |
| `--admin-token SECRET` | none | switches on the admin endpoints (16+ characters; or set `BANKING_ADMIN_TOKEN`) |
| `--lockout-seconds N` | 900 | how long an account stays locked after 3 failed logins |
| `--login-failure-limit N` | 20 | failed logins allowed per client address per window (0 = no limit) |
| `--login-window-seconds N` | 300 | the window for the limit above |

Databases created by earlier versions are upgraded automatically on first start (any old permanent lock is released).

## REST API

The API listens on `http://localhost:8080` (localhost only). Send and receive JSON. After `login`, send the token as `Authorization: Bearer <token>`.

| Method and path | Body | Success | Errors |
|---|---|---|---|
| `GET /api/health` | | 200 `{"status":"UP"}` | |
| `POST /api/register` | `username`, `fullName`, `password` | 201 with the account number | 400 invalid, 409 username taken |
| `POST /api/login` | `username`, `password` | 200 `token`, `expiresInSeconds` | 401 wrong credentials, 423 locked |
| `POST /api/logout` | | 204 | 401 |
| `GET /api/account` | | 200 `accountNumber`, `ownerName`, `balance` | 401 |
| `GET /api/transactions` | | 200 list, oldest first | 401 |
| `POST /api/deposit` | `amount` | 200 new `balance` | 400 invalid amount, 401 |
| `POST /api/withdraw` | `amount` | 200 new `balance` | 400, 401, 422 insufficient funds |
| `POST /api/transfer` | `toAccountNumber`, `amount` | 200 new `balance` | 400, 401, 404 unknown account, 422 |
| `POST /api/password` | `oldPassword`, `newPassword` | 204; the user's other sessions end | 400 weak or unchanged password, 401, 403 wrong current password, 423 locked |
| `POST /api/admin/unlock` | `username` (header `X-Admin-Token`) | 204 | 401 bad admin token, 404 unknown user (or admin API off) |
| `GET /api/admin/audit?username=&limit=` | header `X-Admin-Token` | 200 entries, newest first (`limit` 1 to 200, default 50) | 400, 401, 404 (admin API off) |

Other errors: 404 unknown path, 405 wrong method (with an `Allow` header), 413 body over 10 KB, 415 not `application/json`, 429 too many failed attempts from one client (with `Retry-After`; 423 also carries `Retry-After`). Errors look like `{"error": "..."}`.

Quick try with curl:

```bash
curl -X POST localhost:8080/api/register -H "Content-Type: application/json" \
  -d '{"username":"alice","fullName":"Alice Doe","password":"Passw0rd1"}'
curl -X POST localhost:8080/api/login -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"Passw0rd1"}'
curl -X POST localhost:8080/api/deposit -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" -d '{"amount":100.50}'
```

In IntelliJ, open `api-requests.http` and click the green arrows. On Windows PowerShell, use `Invoke-RestMethod` (plain `curl` there is an alias with different options).

### Security notes

- A token only ever acts on its owner's account: there is no account-number parameter to change, so one user cannot read or move another user's money
- Strict JSON: unknown fields, text where a number belongs and numbers where text belongs are all rejected; request size is capped
- Money is `BigDecimal` in the code and `DECIMAL(19,2)` in the database, and absurd values such as `1e999999999` are rejected cheaply
- Responses never contain passwords or hashes, and server errors return a generic message while details stay in the server log
- Brute-force protection in layers: temporary lockout per account, a limit on failed attempts per client, and the same limit applied to wrong current passwords and wrong admin tokens. A successful login does not reset the client's count
- A password change ends the user's other sessions, and a wrong current password counts as a failed login, so a stolen session cannot be used to guess the password
- The audit log removes control characters and caps text length, so attacker-supplied usernames cannot forge log lines
- This is plain HTTP for localhost. A real deployment needs HTTPS in front, rate limiting and proper secret management

## Tests

JUnit 5, about 211 tests:

- `AbstractBankTest`, run by `InMemoryBankTest` and `JdbcBankTest`: the same bank behaviour on both storage options
- `JdbcBankTest`: restart persistence, rollback of a failed transfer, database-enforced rules (no negative balance, unique usernames, no orphan transactions), SQL-injection-style input
- `ApiServerTest`: starts the real server on a free port and calls it over HTTP: status codes, token handling, strict JSON, user isolation, and concurrent deposits and transfers
- `BankLockingTest`: pauses a sign-in in the middle of hashing and proves other customers can still deposit and transfer (no timing luck involved)
- `LoginThrottleTest`, `SessionStoreTest` (fake clocks), `BankAccountTest`, `PasswordHasherTest`, `BasicBankingAppTest`
- An automated API test suite lives in a separate repo, [banking-api-tests](https://github.com/AsaphDexter/banking-api-tests)

GitHub Actions runs them on every push.

## Limitations

This is a learning project, not a real bank. The database file is not encrypted, only one process can use it at a time, sessions are lost when the server restarts, the audit log has no retention policy, the throttle trusts the connection address (it needs adjusting behind a proxy), there is one shared admin token, and there is no HTTPS or multi-factor authentication.

## Next steps

- Progressive delays instead of locking, and per-admin accounts
- A server database such as PostgreSQL (only a new `BankRepository` is needed)
