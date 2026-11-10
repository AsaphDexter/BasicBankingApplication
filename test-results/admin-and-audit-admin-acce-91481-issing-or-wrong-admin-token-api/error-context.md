# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: admin-and-audit.spec.ts >> admin access >> the admin endpoints refuse a missing or wrong admin token
- Location: tests\admin-and-audit.spec.ts:13:7

# Error details

```
Error: expect(received).toBe(expected) // Object.is equality

Expected: 401
Received: 404
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
> 15  |     expect((await request.post(unlock, { data: { username: 'someone' } })).status()).toBe(401);
      |                                                                                      ^ Error: expect(received).toBe(expected) // Object.is equality
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
  85  |     expect((await request.post('/api/login', { data: { username: first, password: PASSWORD } })).status()).toBe(200);
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
```