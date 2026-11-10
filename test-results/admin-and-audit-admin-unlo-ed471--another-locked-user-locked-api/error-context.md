# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: admin-and-audit.spec.ts >> admin unlock >> unlocking one user leaves another locked user locked
- Location: tests\admin-and-audit.spec.ts:77:7

# Error details

```
Error: expect(received).toBe(expected) // Object.is equality

Expected: 200
Received: 429
```

# Test source

```ts
  1   | import {
  2   |   ADMIN_TOKEN, adminHeaders, bearer, expect, lockOut, postRaw, register, test, uniqueUsername, PASSWORD,
  3   | } from './support/fixtures';
  4   | 
  5   | interface AuditEntry {
  6   |   timestamp: string;
  7   |   type: string;
  8   |   username?: string;
  9   |   detail?: string;
  10  | }
  11  | 
  12  | test.describe('admin access', () => {
  13  |   test('the admin endpoints refuse a missing or wrong admin token', async ({ request }) => {
  14  |     const unlock = '/api/admin/unlock';
  15  |     expect((await request.post(unlock, { data: { username: 'someone' } })).status()).toBe(401);
  16  |     expect((await request.post(unlock, { headers: { 'X-Admin-Token': 'wrong-wrong-wrong-1' }, data: { username: 'someone' } })).status()).toBe(401);
  17  |     expect((await request.get('/api/admin/audit')).status()).toBe(401);
  18  |     expect((await request.get('/api/admin/audit', { headers: { 'X-Admin-Token': ADMIN_TOKEN + 'x' } })).status()).toBe(401);
  19  |     expect((await request.get('/api/admin/audit', { headers: { 'X-Admin-Token': '' } })).status()).toBe(401);
  20  |   });
  21  | 
  22  |   test('a normal user token is not an admin token', async ({ request, newUser }) => {
  23  |     const user = await newUser();
  24  |     expect((await request.get('/api/admin/audit', { headers: { 'X-Admin-Token': user.token } })).status()).toBe(401);
  25  |     expect((await request.get('/api/admin/audit', { headers: user.headers })).status()).toBe(401);
  26  |   });
  27  | 
  28  |   test('the admin token does not work as a login token', async ({ request }) => {
  29  |     expect((await request.get('/api/account', { headers: bearer(ADMIN_TOKEN) })).status()).toBe(401);
  30  |   });
  31  | });
  32  | 
  33  | test.describe('admin unlock', () => {
  34  |   test('unlocks a locked user so they can log in straight away', async ({ request }) => {
  35  |     const username = uniqueUsername();
  36  |     await register(request, username);
  37  |     await lockOut(request, username);
  38  |     expect((await request.post('/api/login', { data: { username, password: PASSWORD } })).status()).toBe(423);
  39  | 
  40  |     const unlock = await request.post('/api/admin/unlock', { headers: adminHeaders, data: { username } });
  41  |     expect(unlock.status()).toBe(204);
  42  |     expect((await request.post('/api/login', { data: { username, password: PASSWORD } })).status()).toBe(200);
  43  |   });
  44  | 
  45  |   test('unlocking resets the failure count too, so the user gets a full set of attempts', async ({ request }) => {
  46  |     const username = uniqueUsername();
  47  |     await register(request, username);
  48  |     await lockOut(request, username);
  49  |     await request.post('/api/admin/unlock', { headers: adminHeaders, data: { username } });
  50  |     expect((await request.post('/api/login', { data: { username, password: 'Wrong1111' } })).status()).toBe(401);
  51  |     expect((await request.post('/api/login', { data: { username, password: 'Wrong2222' } })).status()).toBe(401);
  52  |     expect((await request.post('/api/login', { data: { username, password: PASSWORD } })).status()).toBe(200);
  53  |   });
  54  | 
  55  |   test('unlocking someone who is not locked is harmless', async ({ request }) => {
  56  |     const username = uniqueUsername();
  57  |     await register(request, username);
  58  |     expect((await request.post('/api/admin/unlock', { headers: adminHeaders, data: { username } })).status()).toBe(204);
  59  |     expect((await request.post('/api/login', { data: { username, password: PASSWORD } })).status()).toBe(200);
  60  |   });
  61  | 
  62  |   test('username matching ignores case', async ({ request }) => {
  63  |     const username = uniqueUsername();
  64  |     await register(request, username);
  65  |     await lockOut(request, username);
  66  |     const unlock = await request.post('/api/admin/unlock', { headers: adminHeaders, data: { username: username.toUpperCase() } });
  67  |     expect(unlock.status()).toBe(204);
  68  |   });
  69  | 
  70  |   test('an unknown user is 404, and bad requests are 400', async ({ request }) => {
  71  |     expect((await request.post('/api/admin/unlock', { headers: adminHeaders, data: { username: uniqueUsername() } })).status()).toBe(404);
  72  |     expect((await request.post('/api/admin/unlock', { headers: adminHeaders, data: {} })).status()).toBe(400);
  73  |     expect((await request.post('/api/admin/unlock', { headers: adminHeaders, data: { username: 'x', extra: 1 } })).status()).toBe(400);
  74  |     expect((await postRaw(request, '/api/admin/unlock', '{broken', adminHeaders)).status()).toBe(400);
  75  |   });
  76  | 
  77  |   test('unlocking one user leaves another locked user locked', async ({ request }) => {
  78  |     const first = uniqueUsername();
  79  |     const second = uniqueUsername();
  80  |     await register(request, first);
  81  |     await register(request, second);
  82  |     await lockOut(request, first);
  83  |     await lockOut(request, second);
  84  |     await request.post('/api/admin/unlock', { headers: adminHeaders, data: { username: first } });
> 85  |     expect((await request.post('/api/login', { data: { username: first, password: PASSWORD } })).status()).toBe(200);
      |                                                                                                            ^ Error: expect(received).toBe(expected) // Object.is equality
  86  |     expect((await request.post('/api/login', { data: { username: second, password: PASSWORD } })).status()).toBe(423);
  87  |   });
  88  | });
  89  | 
  90  | test.describe('audit log', () => {
  91  |   async function auditFor(request: Parameters<typeof lockOut>[0], username: string, limit = 50): Promise<AuditEntry[]> {
  92  |     const response = await request.get(`/api/admin/audit?username=${encodeURIComponent(username)}&limit=${limit}`, {
  93  |       headers: adminHeaders,
  94  |     });
  95  |     expect(response.status()).toBe(200);
  96  |     return (await response.json()) as AuditEntry[];
  97  |   }
  98  | 
  99  |   test('records the life of an account, newest first', async ({ request }) => {
  100 |     const username = uniqueUsername();
  101 |     await register(request, username);
  102 |     await request.post('/api/login', { data: { username, password: PASSWORD } });
  103 |     await request.post('/api/login', { data: { username, password: 'Wrong1111' } });
  104 |     const types = (await auditFor(request, username)).map((entry) => entry.type);
  105 |     expect(types).toEqual(['LOGIN_FAILED', 'LOGIN_SUCCESS', 'REGISTERED']);
  106 |   });
  107 | 
  108 |   test('records locking, blocked attempts, unlocking and password changes', async ({ request }) => {
  109 |     const username = uniqueUsername();
  110 |     await register(request, username);
  111 |     const token = ((await (await request.post('/api/login', { data: { username, password: PASSWORD } })).json()) as { token: string }).token;
  112 |     await lockOut(request, username);
  113 |     await request.post('/api/login', { data: { username, password: PASSWORD } }); // blocked
  114 |     await request.post('/api/admin/unlock', { headers: adminHeaders, data: { username } });
  115 |     await request.post('/api/password', { headers: bearer(token), data: { oldPassword: PASSWORD, newPassword: 'N3wPassword' } });
  116 | 
  117 |     const types = (await auditFor(request, username)).map((entry) => entry.type).reverse();
  118 |     expect(types).toEqual([
  119 |       'REGISTERED', 'LOGIN_SUCCESS', 'LOGIN_FAILED', 'LOGIN_FAILED', 'LOGIN_FAILED',
  120 |       'ACCOUNT_LOCKED', 'LOGIN_BLOCKED', 'ACCOUNT_UNLOCKED', 'PASSWORD_CHANGED',
  121 |     ]);
  122 |   });
  123 | 
  124 |   test('records transfers under the user who made them, with accounts and amount', async ({ request, newUser }) => {
  125 |     const alice = await newUser();
  126 |     const bob = await newUser();
  127 |     await request.post('/api/deposit', { headers: alice.headers, data: { amount: 100 } });
  128 |     await request.post('/api/transfer', { headers: alice.headers, data: { toAccountNumber: bob.accountNumber, amount: 25.5 } });
  129 | 
  130 |     const transfers = (await auditFor(request, alice.username)).filter((entry) => entry.type === 'TRANSFER');
  131 |     expect(transfers).toHaveLength(1);
  132 |     expect(transfers[0]?.detail).toContain(alice.accountNumber);
  133 |     expect(transfers[0]?.detail).toContain(bob.accountNumber);
  134 |     expect(transfers[0]?.detail).toContain('25.50');
  135 |     expect((await auditFor(request, bob.username)).filter((entry) => entry.type === 'TRANSFER')).toHaveLength(0);
  136 |   });
  137 | 
  138 |   test('records failed attempts against unknown users without confirming they exist', async ({ request }) => {
  139 |     const ghost = uniqueUsername();
  140 |     const response = await request.post('/api/login', { data: { username: ghost, password: PASSWORD } });
  141 |     expect(response.status()).toBe(401);
  142 |     const entries = await auditFor(request, ghost);
  143 |     expect(entries).toHaveLength(1);
  144 |     expect(entries[0]?.type).toBe('LOGIN_FAILED');
  145 |     expect(entries[0]?.detail).toContain('unknown user');
  146 |   });
  147 | 
  148 |   test('entries have ISO timestamps and never contain passwords, hashes or tokens', async ({ request }) => {
  149 |     const username = uniqueUsername();
  150 |     await register(request, username);
  151 |     const login = await request.post('/api/login', { data: { username, password: PASSWORD } });
  152 |     const token = ((await login.json()) as { token: string }).token;
  153 |     await request.post('/api/login', { data: { username, password: 'SecretGuess99' } });
  154 |     await request.post('/api/password', { headers: bearer(token), data: { oldPassword: PASSWORD, newPassword: 'N3wPassword' } });
  155 | 
  156 |     const response = await request.get(`/api/admin/audit?username=${username}`, { headers: adminHeaders });
  157 |     const text = await response.text();
  158 |     for (const secret of [PASSWORD, 'SecretGuess99', 'N3wPassword', token]) {
  159 |       expect(text).not.toContain(secret);
  160 |     }
  161 |     expect(text.toLowerCase()).not.toContain('pbkdf2');
  162 |     for (const entry of JSON.parse(text) as AuditEntry[]) {
  163 |       expect(Number.isNaN(Date.parse(entry.timestamp))).toBe(false);
  164 |     }
  165 |   });
  166 | 
  167 |   test('attacker-supplied text cannot forge log lines (control characters removed, length capped)', async ({ request }) => {
  168 |     const base = uniqueUsername();
  169 |     const hostile = `${base}\nLOGIN_SUCCESS admin${'x'.repeat(100)}`;
  170 |     await request.post('/api/login', { data: { username: hostile, password: PASSWORD } });
  171 |     const cleaned = `${base}?login_success admin${'x'.repeat(100)}`.slice(0, 50);
  172 |     const entries = await auditFor(request, cleaned);
  173 |     expect(entries.length).toBeGreaterThanOrEqual(1);
  174 |     for (const entry of entries) {
  175 |       expect(entry.username).not.toMatch(/[\r\n]/);
  176 |       expect((entry.username ?? '').length).toBeLessThanOrEqual(50);
  177 |     }
  178 |   });
  179 | 
  180 |   test('the limit parameter is validated (whole number from 1 to 200)', async ({ request }) => {
  181 |     for (const bad of ['0', '201', '-1', 'abc', '1.5', '']) {
  182 |       const response = await request.get(`/api/admin/audit?limit=${bad}`, { headers: adminHeaders });
  183 |       expect(response.status(), `limit=${bad}`).toBe(400);
  184 |     }
  185 |     expect((await request.get('/api/admin/audit?limit=200', { headers: adminHeaders })).status()).toBe(200);
```