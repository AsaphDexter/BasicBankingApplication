# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: authentication.spec.ts >> tokens and logout >> logout revokes the token immediately
- Location: tests\authentication.spec.ts:216:7

# Error details

```
Error: login for un8ndmw9of3td1

expect(received).toBe(expected) // Object.is equality

Expected: 200
Received: 429
```

# Test source

```ts
  1   | import { test as base, expect, type APIRequestContext, type APIResponse } from '@playwright/test';
  2   | 
  3   | export { expect };
  4   | 
  5   | /** A password that satisfies the API's rules (8+ characters, a letter and a number). */
  6   | export const PASSWORD = 'Passw0rd1';
  7   | 
  8   | /** Must match the --admin-token the API instances are started with. */
  9   | export const ADMIN_TOKEN = process.env.ADMIN_TOKEN ?? 'playwright-admin-token-123456';
  10  | export const adminHeaders = { 'X-Admin-Token': ADMIN_TOKEN };
  11  | 
  12  | export interface TestUser {
  13  |   username: string;
  14  |   password: string;
  15  |   accountNumber: string;
  16  |   token: string;
  17  |   headers: { Authorization: string };
  18  | }
  19  | 
  20  | let counter = 0;
  21  | 
  22  | /**
  23  |  * A username that is valid (3-20 letters/numbers/underscores) and unique across runs and workers,
  24  |  * so tests never collide with each other or with earlier runs against the same server.
  25  |  */
  26  | export function uniqueUsername(prefix = 'u'): string {
  27  |   counter += 1;
  28  |   const stamp = Date.now().toString(36);
  29  |   const random = Math.random().toString(36).slice(2, 6);
  30  |   return `${prefix.slice(0, 3)}${random}${stamp}${counter}`.slice(0, 20);
  31  | }
  32  | 
  33  | export const bearer = (token: string): { Authorization: string } => ({ Authorization: `Bearer ${token}` });
  34  | 
  35  | export function register(
  36  |   request: APIRequestContext,
  37  |   username: string = uniqueUsername(),
  38  |   password: string = PASSWORD,
  39  |   fullName?: string,
  40  | ): Promise<APIResponse> {
  41  |   return request.post('/api/register', {
  42  |     data: { username, fullName: fullName ?? `Test ${username}`, password },
  43  |   });
  44  | }
  45  | 
  46  | export async function login(
  47  |   request: APIRequestContext,
  48  |   username: string,
  49  |   password: string = PASSWORD,
  50  | ): Promise<string> {
  51  |   const response = await request.post('/api/login', { data: { username, password } });
> 52  |   expect(response.status(), `login for ${username}`).toBe(200);
      |                                                      ^ Error: login for un8ndmw9of3td1
  53  |   const body = (await response.json()) as { token: string };
  54  |   return body.token;
  55  | }
  56  | 
  57  | /** Registers a new user and logs them in. */
  58  | export async function createUser(request: APIRequestContext): Promise<TestUser> {
  59  |   const username = uniqueUsername();
  60  |   const registered = await register(request, username);
  61  |   expect(registered.status(), `register ${username}`).toBe(201);
  62  |   const { accountNumber } = (await registered.json()) as { accountNumber: string };
  63  |   const token = await login(request, username);
  64  |   return { username, password: PASSWORD, accountNumber, token, headers: bearer(token) };
  65  | }
  66  | 
  67  | export async function balanceOf(request: APIRequestContext, user: TestUser): Promise<number> {
  68  |   const response = await request.get('/api/account', { headers: user.headers });
  69  |   expect(response.status()).toBe(200);
  70  |   return ((await response.json()) as { balance: number }).balance;
  71  | }
  72  | 
  73  | export function deposit(request: APIRequestContext, user: TestUser, amount: number): Promise<APIResponse> {
  74  |   return request.post('/api/deposit', { headers: user.headers, data: { amount } });
  75  | }
  76  | 
  77  | export function withdraw(request: APIRequestContext, user: TestUser, amount: number): Promise<APIResponse> {
  78  |   return request.post('/api/withdraw', { headers: user.headers, data: { amount } });
  79  | }
  80  | 
  81  | export function transfer(
  82  |   request: APIRequestContext,
  83  |   from: TestUser,
  84  |   toAccountNumber: string,
  85  |   amount: number,
  86  | ): Promise<APIResponse> {
  87  |   return request.post('/api/transfer', { headers: from.headers, data: { toAccountNumber, amount } });
  88  | }
  89  | 
  90  | /** Sends a hand-written body, for cases JSON.stringify cannot produce (malformed JSON, 1e999999999 ...). */
  91  | export function postRaw(
  92  |   request: APIRequestContext,
  93  |   path: string,
  94  |   body: string,
  95  |   headers: Record<string, string> = {},
  96  | ): Promise<APIResponse> {
  97  |   return request.post(path, { data: body, headers: { 'Content-Type': 'application/json', ...headers } });
  98  | }
  99  | 
  100 | /** Locks a user out by sending three wrong passwords. */
  101 | export async function lockOut(request: APIRequestContext, username: string): Promise<void> {
  102 |   for (const password of ['Wrong1111', 'Wrong2222', 'Wrong3333']) {
  103 |     await request.post('/api/login', { data: { username, password } });
  104 |   }
  105 | }
  106 | 
  107 | /** True if the API at the project's base URL answers; used to skip specs when an instance is not running. */
  108 | export async function isUp(request: APIRequestContext): Promise<boolean> {
  109 |   try {
  110 |     return (await request.get('/api/health')).ok();
  111 |   } catch {
  112 |     return false;
  113 |   }
  114 | }
  115 | 
  116 | interface Fixtures {
  117 |   /** Creates a fresh registered and logged-in user for the test. */
  118 |   newUser: () => Promise<TestUser>;
  119 | }
  120 | 
  121 | export const test = base.extend<Fixtures>({
  122 |   newUser: async ({ request }, use) => {
  123 |     await use(() => createUser(request));
  124 |   },
  125 | });
  126 | 
```