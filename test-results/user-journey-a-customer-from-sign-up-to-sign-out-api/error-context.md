# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: user-journey.spec.ts >> a customer from sign-up to sign-out
- Location: tests\user-journey.spec.ts:3:5

# Error details

```
Error: expect(received).toBe(expected) // Object.is equality

Expected: 200
Received: 429
```

# Test source

```ts
  1  | import { expect, test, register, uniqueUsername, bearer, PASSWORD } from './support/fixtures';
  2  | 
  3  | test('a customer from sign-up to sign-out', async ({ request }) => {
  4  |   const aliceName = uniqueUsername();
  5  |   const bobName = uniqueUsername();
  6  |   let aliceToken = '';
  7  |   let bobAccount = '';
  8  | 
  9  |   await test.step('two customers register', async () => {
  10 |     const alice = await register(request, aliceName, PASSWORD, 'Alice Doe');
  11 |     const bob = await register(request, bobName, PASSWORD, 'Bob Roe');
  12 |     expect(alice.status()).toBe(201);
  13 |     expect(bob.status()).toBe(201);
  14 |     bobAccount = ((await bob.json()) as { accountNumber: string }).accountNumber;
  15 |   });
  16 | 
  17 |   await test.step('Alice logs in and starts with an empty account', async () => {
  18 |     const login = await request.post('/api/login', { data: { username: aliceName, password: PASSWORD } });
> 19 |     expect(login.status()).toBe(200);
     |                            ^ Error: expect(received).toBe(expected) // Object.is equality
  20 |     aliceToken = ((await login.json()) as { token: string }).token;
  21 |     const account = await request.get('/api/account', { headers: bearer(aliceToken) });
  22 |     expect(((await account.json()) as { balance: number }).balance).toBe(0);
  23 |   });
  24 | 
  25 |   await test.step('Alice deposits, withdraws and is stopped from overdrawing', async () => {
  26 |     const headers = bearer(aliceToken);
  27 |     expect((await request.post('/api/deposit', { headers, data: { amount: 250.75 } })).status()).toBe(200);
  28 |     expect((await request.post('/api/withdraw', { headers, data: { amount: 20 } })).status()).toBe(200);
  29 |     expect((await request.post('/api/withdraw', { headers, data: { amount: 999 } })).status()).toBe(422);
  30 |   });
  31 | 
  32 |   await test.step('Alice sends Bob 50 and her balance drops', async () => {
  33 |     const response = await request.post('/api/transfer', {
  34 |       headers: bearer(aliceToken),
  35 |       data: { toAccountNumber: bobAccount, amount: 50 },
  36 |     });
  37 |     expect(response.status()).toBe(200);
  38 |     expect(await response.text()).toBe('{"balance":180.75}');
  39 |   });
  40 | 
  41 |   await test.step('Bob logs in and sees exactly that money and that one transfer', async () => {
  42 |     const login = await request.post('/api/login', { data: { username: bobName, password: PASSWORD } });
  43 |     const bobToken = ((await login.json()) as { token: string }).token;
  44 |     const account = (await (await request.get('/api/account', { headers: bearer(bobToken) })).json()) as { balance: number };
  45 |     expect(account.balance).toBe(50);
  46 |     const history = (await (await request.get('/api/transactions', { headers: bearer(bobToken) })).json()) as Array<{ type: string }>;
  47 |     expect(history.map((entry) => entry.type)).toEqual(['TRANSFER_IN']);
  48 |   });
  49 | 
  50 |   await test.step('Alice logs out and her token is dead', async () => {
  51 |     expect((await request.post('/api/logout', { headers: bearer(aliceToken), data: {} })).status()).toBe(204);
  52 |     expect((await request.get('/api/account', { headers: bearer(aliceToken) })).status()).toBe(401);
  53 |   });
  54 | });
  55 | 
```