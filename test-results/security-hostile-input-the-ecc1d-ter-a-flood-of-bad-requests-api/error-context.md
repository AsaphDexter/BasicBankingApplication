# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: security.spec.ts >> hostile input >> the server still answers after a flood of bad requests
- Location: tests\security.spec.ts:112:7

# Error details

```
Error: "{"

expect(received).toBe(expected) // Object.is equality

Expected: 400
Received: 429
```

# Test source

```ts
  16  |     // The API has no "from account" field, so Bob cannot spend Alice's money, whatever he sends.
  17  |     expect((await withdraw(request, bob, 1)).status()).toBe(422);
  18  |     const spoof = await postRaw(
  19  |       request,
  20  |       '/api/transfer',
  21  |       JSON.stringify({ toAccountNumber: bob.accountNumber, fromAccountNumber: alice.accountNumber, amount: 50 }),
  22  |       bob.headers,
  23  |     );
  24  |     expect(spoof.status()).toBe(400);
  25  |     expect(await balanceOf(request, alice)).toBe(100);
  26  |   });
  27  | 
  28  |   test('actions with one token never change another user', async ({ request, newUser }) => {
  29  |     const alice = await newUser();
  30  |     const bob = await newUser();
  31  |     await deposit(request, alice, 10);
  32  |     await deposit(request, alice, 20);
  33  |     expect(await balanceOf(request, alice)).toBe(30);
  34  |     expect(await balanceOf(request, bob)).toBe(0);
  35  |   });
  36  | 
  37  |   test('a token stops working for its user the moment it is revoked, while others are untouched', async ({ request, newUser }) => {
  38  |     const alice = await newUser();
  39  |     const bob = await newUser();
  40  |     await request.post('/api/logout', { headers: alice.headers, data: {} });
  41  |     expect((await request.get('/api/account', { headers: alice.headers })).status()).toBe(401);
  42  |     expect((await request.get('/api/account', { headers: bob.headers })).status()).toBe(200);
  43  |   });
  44  | 
  45  |   test('tokens look random: 12 logins give 12 different values with no shared prefix', async ({ request }) => {
  46  |     const username = uniqueUsername();
  47  |     await register(request, username);
  48  |     const tokens: string[] = [];
  49  |     for (let i = 0; i < 12; i += 1) {
  50  |       tokens.push(await login(request, username));
  51  |     }
  52  |     expect(new Set(tokens).size).toBe(12);
  53  |     const prefixes = new Set(tokens.map((token) => token.slice(0, 6)));
  54  |     expect(prefixes.size).toBeGreaterThan(8);
  55  |   });
  56  | });
  57  | 
  58  | test.describe('secrets never leak', () => {
  59  |   test('no response across the whole API contains a password or a hash', async ({ request }) => {
  60  |     const username = uniqueUsername();
  61  |     const bodies: string[] = [];
  62  |     bodies.push(await (await register(request, username)).text());
  63  |     const loginResponse = await request.post('/api/login', { data: { username, password: PASSWORD } });
  64  |     bodies.push(await loginResponse.text());
  65  |     const token = ((await loginResponse.json()) as { token: string }).token;
  66  |     bodies.push(await (await request.get('/api/account', { headers: bearer(token) })).text());
  67  |     bodies.push(await (await request.get('/api/transactions', { headers: bearer(token) })).text());
  68  |     bodies.push(await (await request.post('/api/login', { data: { username, password: 'Wrong1234' } })).text());
  69  |     bodies.push(await (await register(request, username)).text());
  70  |     for (const body of bodies) {
  71  |       expect(body).not.toContain(PASSWORD);
  72  |       expect(body.toLowerCase()).not.toContain('pbkdf2');
  73  |       expect(body.toLowerCase()).not.toContain('salt');
  74  |     }
  75  |   });
  76  | });
  77  | 
  78  | test.describe('hostile input', () => {
  79  |   test('SQL in a full name is stored as plain text and does not break anything', async ({ request }) => {
  80  |     const username = uniqueUsername();
  81  |     const nasty = "Robert'); DROP TABLE users;--";
  82  |     expect((await register(request, username, PASSWORD, nasty)).status()).toBe(201);
  83  |     const token = await login(request, username);
  84  |     const account = (await (await request.get('/api/account', { headers: bearer(token) })).json()) as { ownerName: string };
  85  |     expect(account.ownerName).toBe(nasty);
  86  |     expect((await register(request, uniqueUsername())).status()).toBe(201); // the database is still fine
  87  |   });
  88  | 
  89  |   test('a script in a full name comes back as harmless JSON text', async ({ request }) => {
  90  |     const username = uniqueUsername();
  91  |     const payload = '<script>alert(1)</script>';
  92  |     await register(request, username, PASSWORD, payload);
  93  |     const token = await login(request, username);
  94  |     const response = await request.get('/api/account', { headers: bearer(token) });
  95  |     expect(response.headers()['content-type']).toContain('application/json');
  96  |     expect(response.headers()['x-content-type-options']).toBe('nosniff');
  97  |     expect(((await response.json()) as { ownerName: string }).ownerName).toBe(payload);
  98  |   });
  99  | 
  100 |   test('unicode and very long full names are handled without a server error', async ({ request }) => {
  101 |     for (const fullName of ['José Müller 🙂', 'Ａｄａ', 'x'.repeat(100)]) {
  102 |       const response = await register(request, uniqueUsername(), PASSWORD, fullName);
  103 |       expect(response.status(), fullName).toBeLessThan(500);
  104 |     }
  105 |   });
  106 | 
  107 |   test('a request body over 10 KB is 413', async ({ request }) => {
  108 |     const body = JSON.stringify({ username: 'a'.repeat(20_000), password: PASSWORD });
  109 |     expect((await postRaw(request, '/api/login', body)).status()).toBe(413);
  110 |   });
  111 | 
  112 |   test('the server still answers after a flood of bad requests', async ({ request }) => {
  113 |     const bad = ['{', '[', '"', '{"a":', '\u0000', '{"username":"a","password":"b",}', '��'];
  114 |     for (const body of bad) {
  115 |       const response = await postRaw(request, '/api/login', body);
> 116 |       expect(response.status(), JSON.stringify(body)).toBe(400);
      |                                                       ^ Error: "{"
  117 |     }
  118 |     expect((await request.get('/api/health')).status()).toBe(200);
  119 |   });
  120 | 
  121 |   const contentTypes: Array<[string, string | undefined, number]> = [
  122 |     ['application/json', 'application/json', 401],
  123 |     ['application/json with a charset', 'application/json; charset=utf-8', 401],
  124 |     ['APPLICATION/JSON in capitals', 'APPLICATION/JSON', 401],
  125 |     ['text/plain', 'text/plain', 415],
  126 |     ['a form post', 'application/x-www-form-urlencoded', 415],
  127 |     ['xml', 'application/xml', 415],
  128 |     ['no declared type', undefined, 415],
  129 |   ];
  130 |   for (const [label, type, expected] of contentTypes) {
  131 |     test(`Content-Type ${label} gives ${expected}`, async ({ request }) => {
  132 |       const headers: Record<string, string> = type ? { 'Content-Type': type } : {};
  133 |       const response = await request.post('/api/login', {
  134 |         headers,
  135 |         data: JSON.stringify({ username: uniqueUsername(), password: PASSWORD }),
  136 |       });
  137 |       // application/json is accepted as far as parsing goes, so an unknown user then fails with 401.
  138 |       expect(response.status()).toBe(expected);
  139 |     });
  140 |   }
  141 | });
  142 | 
  143 | test.describe('input that could confuse the amount handling', () => {
  144 |   test('deposits of 1e2 and 100.0 are the same as 100', async ({ request, newUser }) => {
  145 |     const user = await newUser();
  146 |     expect(await (await postRaw(request, '/api/deposit', '{"amount":1e2}', user.headers)).text()).toBe('{"balance":100.00}');
  147 |     expect(await (await postRaw(request, '/api/deposit', '{"amount":100.0}', user.headers)).text()).toBe('{"balance":200.00}');
  148 |   });
  149 | 
  150 |   test('a transfer of an amount that is too precise leaves both balances alone', async ({ request, newUser }) => {
  151 |     const alice = await newUser();
  152 |     const bob = await newUser();
  153 |     await deposit(request, alice, 100);
  154 |     expect((await transfer(request, alice, bob.accountNumber, 0.001)).status()).toBe(400);
  155 |     expect(await balanceOf(request, alice)).toBe(100);
  156 |   });
  157 | });
  158 | 
```