# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: authentication.spec.ts >> account lockout >> a successful login resets the failure counter
- Location: tests\authentication.spec.ts:98:7

# Error details

```
Error: Wrong1111

expect(received).toBe(expected) // Object.is equality

Expected: 401
Received: 429
```

# Test source

```ts
  6   |   test('returns a bearer token with its lifetime', async ({ request }) => {
  7   |     const username = uniqueUsername();
  8   |     await register(request, username);
  9   |     const response = await request.post('/api/login', { data: { username, password: PASSWORD } });
  10  |     expect(response.status()).toBe(200);
  11  |     const body = (await response.json()) as { token: string; tokenType: string; expiresInSeconds: number };
  12  |     expect(body.token).toMatch(/^[A-Za-z0-9_-]{43}$/); // 256 bits, URL-safe Base64
  13  |     expect(body.tokenType).toBe('Bearer');
  14  |     expect(body.expiresInSeconds).toBe(1800);
  15  |     expect(JSON.stringify(body)).not.toContain(PASSWORD);
  16  |   });
  17  | 
  18  |   test('username is not case-sensitive but the password is', async ({ request }) => {
  19  |     const username = uniqueUsername();
  20  |     await register(request, username);
  21  |     const upper = await request.post('/api/login', { data: { username: username.toUpperCase(), password: PASSWORD } });
  22  |     expect(upper.status()).toBe(200);
  23  |     const wrongCase = await request.post('/api/login', { data: { username, password: PASSWORD.toLowerCase() } });
  24  |     expect(wrongCase.status()).toBe(401);
  25  |   });
  26  | 
  27  |   test('a wrong password and an unknown user give identical 401 responses', async ({ request }) => {
  28  |     const username = uniqueUsername();
  29  |     await register(request, username);
  30  |     const wrongPassword = await request.post('/api/login', { data: { username, password: 'Wrong1234' } });
  31  |     const unknownUser = await request.post('/api/login', { data: { username: uniqueUsername(), password: 'Wrong1234' } });
  32  |     expect(wrongPassword.status()).toBe(401);
  33  |     expect(unknownUser.status()).toBe(401);
  34  |     expect(await wrongPassword.text()).toBe(await unknownUser.text());
  35  |   });
  36  | 
  37  |   test('every login gets a different token and both stay valid', async ({ request }) => {
  38  |     const username = uniqueUsername();
  39  |     await register(request, username);
  40  |     const tokens = new Set<string>();
  41  |     for (let i = 0; i < 6; i += 1) {
  42  |       tokens.add(await login(request, username));
  43  |     }
  44  |     expect(tokens.size).toBe(6);
  45  |     for (const token of tokens) {
  46  |       expect((await request.get('/api/account', { headers: bearer(token) })).status()).toBe(200);
  47  |     }
  48  |   });
  49  | 
  50  |   const badBodies: Array<[string, string]> = [
  51  |     ['missing password', '{"username":"someone"}'],
  52  |     ['missing username', '{"password":"Passw0rd1"}'],
  53  |     ['empty object', '{}'],
  54  |     ['null password', '{"username":"someone","password":null}'],
  55  |     ['numeric password', '{"username":"someone","password":12345678}'],
  56  |     ['extra field', '{"username":"someone","password":"Passw0rd1","admin":true}'],
  57  |     ['not JSON', 'username=someone&password=Passw0rd1'],
  58  |   ];
  59  |   for (const [label, body] of badBodies) {
  60  |     test(`rejects a login with ${label}`, async ({ request }) => {
  61  |       const response = await postRaw(request, '/api/login', body);
  62  |       expect(response.status()).toBe(400);
  63  |     });
  64  |   }
  65  | 
  66  |   test('SQL-injection style credentials simply fail to log in', async ({ request }) => {
  67  |     const username = uniqueUsername();
  68  |     await register(request, username);
  69  |     for (const attempt of [`${username}' OR '1'='1`, `${username}'--`, "' OR 1=1 --", `${username}" OR ""="`]) {
  70  |       const response = await request.post('/api/login', { data: { username: attempt, password: "x' OR '1'='1" } });
  71  |       expect(response.status()).toBe(401);
  72  |     }
  73  |     expect((await request.post('/api/login', { data: { username, password: PASSWORD } })).status()).toBe(200);
  74  |   });
  75  | });
  76  | 
  77  | test.describe('account lockout', () => {
  78  |   test('three wrong passwords lock the account, even for the correct password afterwards', async ({ request }) => {
  79  |     const username = uniqueUsername();
  80  |     await register(request, username);
  81  |     const statuses: number[] = [];
  82  |     for (const password of ['Wrong1111', 'Wrong2222', 'Wrong3333', PASSWORD]) {
  83  |       statuses.push((await request.post('/api/login', { data: { username, password } })).status());
  84  |     }
  85  |     expect(statuses).toEqual([401, 401, 423, 423]);
  86  |   });
  87  | 
  88  |   test('the locked response explains itself', async ({ request }) => {
  89  |     const username = uniqueUsername();
  90  |     await register(request, username);
  91  |     for (const password of ['Wrong1111', 'Wrong2222', 'Wrong3333']) {
  92  |       await request.post('/api/login', { data: { username, password } });
  93  |     }
  94  |     const response = await request.post('/api/login', { data: { username, password: PASSWORD } });
  95  |     expect(((await response.json()) as { error: string }).error.toLowerCase()).toContain('locked');
  96  |   });
  97  | 
  98  |   test('a successful login resets the failure counter', async ({ request }) => {
  99  |     const username = uniqueUsername();
  100 |     await register(request, username);
  101 |     const attempts: Array<[string, number]> = [
  102 |       ['Wrong1111', 401], ['Wrong2222', 401], [PASSWORD, 200], ['Wrong3333', 401], ['Wrong4444', 401], [PASSWORD, 200],
  103 |     ];
  104 |     for (const [password, expected] of attempts) {
  105 |       const response = await request.post('/api/login', { data: { username, password } });
> 106 |       expect(response.status(), password).toBe(expected);
      |                                           ^ Error: Wrong1111
  107 |     }
  108 |   });
  109 | 
  110 |   test('locking one user does not affect another', async ({ request }) => {
  111 |     const victim = uniqueUsername();
  112 |     const bystander = uniqueUsername();
  113 |     await register(request, victim);
  114 |     await register(request, bystander);
  115 |     for (const password of ['Wrong1111', 'Wrong2222', 'Wrong3333']) {
  116 |       await request.post('/api/login', { data: { username: victim, password } });
  117 |     }
  118 |     expect((await request.post('/api/login', { data: { username: bystander, password: PASSWORD } })).status()).toBe(200);
  119 |   });
  120 | 
  121 |   test('an already-issued token keeps working after its owner gets locked', async ({ request }) => {
  122 |     const user = await createUser(request);
  123 |     for (const password of ['Wrong1111', 'Wrong2222', 'Wrong3333']) {
  124 |       await request.post('/api/login', { data: { username: user.username, password } });
  125 |     }
  126 |     expect((await request.get('/api/account', { headers: user.headers })).status()).toBe(200);
  127 |   });
  128 | 
  129 |   test('FIXED: a lock is temporary and the response says how long it lasts', async ({ request }) => {
  130 |     const username = uniqueUsername();
  131 |     await register(request, username);
  132 |     for (const password of ['Wrong1111', 'Wrong2222']) {
  133 |       await request.post('/api/login', { data: { username, password } });
  134 |     }
  135 |     const locking = await request.post('/api/login', { data: { username, password: 'Wrong3333' } });
  136 |     expect(locking.status()).toBe(423);
  137 |     const blocked = await request.post('/api/login', { data: { username, password: PASSWORD } });
  138 |     expect(blocked.status()).toBe(423);
  139 |     for (const response of [locking, blocked]) {
  140 |       const wait = Number(response.headers()['retry-after']);
  141 |       expect(wait).toBeGreaterThan(0);
  142 |       expect(wait).toBeLessThanOrEqual(900); // 15 minutes at most; short-lockout.spec.ts checks it really ends
  143 |     }
  144 |   });
  145 | 
  146 |   test('FINDING: the locked response reveals that a username exists', async ({ request }) => {
  147 |     test.info().annotations.push({
  148 |       type: 'finding',
  149 |       description: 'A locked existing user answers 423 while an unknown user answers 401, so lockout state leaks account existence.',
  150 |     });
  151 |     const victim = uniqueUsername();
  152 |     await register(request, victim);
  153 |     for (const password of ['Wrong1111', 'Wrong2222', 'Wrong3333']) {
  154 |       await request.post('/api/login', { data: { username: victim, password } });
  155 |     }
  156 |     const existing = await request.post('/api/login', { data: { username: victim, password: 'Wrong4444' } });
  157 |     const unknown = await request.post('/api/login', { data: { username: uniqueUsername(), password: 'Wrong4444' } });
  158 |     expect(existing.status()).toBe(423);
  159 |     expect(unknown.status()).toBe(401);
  160 |   });
  161 | });
  162 | 
  163 | test.describe('tokens and logout', () => {
  164 |   const protectedCalls: Array<[string, string, object]> = [
  165 |     ['GET', '/api/account', {}],
  166 |     ['GET', '/api/transactions', {}],
  167 |     ['POST', '/api/deposit', { amount: 10 }],
  168 |     ['POST', '/api/withdraw', { amount: 10 }],
  169 |     ['POST', '/api/transfer', { toAccountNumber: '1000000001', amount: 10 }],
  170 |     ['POST', '/api/logout', {}],
  171 |     ['POST', '/api/password', { oldPassword: 'Passw0rd1', newPassword: 'N3wPassword' }],
  172 |   ];
  173 | 
  174 |   for (const [method, path, data] of protectedCalls) {
  175 |     test(`${method} ${path} without a token is 401 with a Bearer challenge`, async ({ request }) => {
  176 |       const response = await request.fetch(path, { method, data: method === 'POST' ? data : undefined });
  177 |       expect(response.status()).toBe(401);
  178 |       expect(response.headers()['www-authenticate']).toBe('Bearer');
  179 |     });
  180 |   }
  181 | 
  182 |   const badAuthHeaders: Array<[string, string]> = [
  183 |     ['a random token', 'Bearer not-a-real-token'],
  184 |     ['an empty token', 'Bearer '],
  185 |     ['no token after the scheme', 'Bearer'],
  186 |     ['the Basic scheme', 'Basic YWxpY2U6UGFzc3cwcmQx'],
  187 |     ['a very long token', `Bearer ${'A'.repeat(5000)}`],
  188 |     ['SQL in the token', "Bearer ' OR '1'='1"],
  189 |   ];
  190 |   for (const [label, header] of badAuthHeaders) {
  191 |     test(`rejects ${label}`, async ({ request }) => {
  192 |       const response = await request.get('/api/account', { headers: { Authorization: header } });
  193 |       expect(response.status()).toBe(401);
  194 |     });
  195 |   }
  196 | 
  197 |   test('the token is only accepted in the Authorization header', async ({ request, newUser }) => {
  198 |     const user = await newUser();
  199 |     const inQuery = await request.get(`/api/account?token=${user.token}`);
  200 |     const inCookie = await request.get('/api/account', { headers: { Cookie: `token=${user.token}` } });
  201 |     expect(inQuery.status()).toBe(401);
  202 |     expect(inCookie.status()).toBe(401);
  203 |   });
  204 | 
  205 |   test('the Bearer scheme name is case-insensitive', async ({ request, newUser }) => {
  206 |     const user = await newUser();
```