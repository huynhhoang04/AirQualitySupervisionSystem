# Scripting Recipes

Reference for the `bruno-api-testing` skill. Copy-paste starting points for Bruno request
scripts (`runtime.scripts` in YAML, `script { }` blocks in `.bru`). Adjust names to the target
API's contract. None of these scripts may ever print or persist a secret.

## 1. Reading variables safely

```js
function required(name, hint) {
  let v = bru.getEnvVar(name);
  if (!v) { try { v = bru.getSecretVar(name); } catch (e) {} }
  if (!v) throw new Error(
    "Missing variable '" + name + "'. " + hint
  );
  return v;
}

const secret = required("hmacSecret",
  "Set it in the selected Bruno environment (Secret Variables tab) before sending.");
```

`bru.getEnvVar` first, `bru.getSecretVar` fallback: a value may live in either tab depending on
who set it up. Fail at the start of the script - never sign with an empty key.

## 2. Bearer token capture (login request)

```js
// after-response
if (res.body && res.body.accessToken) bru.setVar("accessToken", res.body.accessToken);
if (res.body && res.body.refreshToken) bru.setVar("refreshToken", res.body.refreshToken);
```

Subsequent requests send `Authorization: Bearer {{accessToken}}` as a header. Tokens live in
runtime variables only - never write them into an environment file. Refresh flow: a dedicated
`NN-auth---refresh.yml` request repeats the capture.

## 3. HMAC request signing (gateway-style)

Canonical string: `METHOD\nPATH\nCANONICAL_QUERY\nTIMESTAMP\nSHA256_HEX(rawBody)`.

```js
// before-request
const CryptoJS = require("crypto-js");
const decodeUrl = (s) => { try { return decodeURIComponent(s); } catch (e) { return s; } };

const secret = required("hmacSecret", "Set hmacSecret in the environment before sending.");

const httpMethod = req.getMethod().toUpperCase();
let raw = "";
if (["POST", "PUT", "PATCH"].includes(httpMethod)) {
  const rawBody = req.getBody({ raw: true });
  raw = rawBody == null ? "" : (typeof rawBody === "string" ? rawBody : JSON.stringify(rawBody));
  raw = bru.interpolate(raw);
}

const timestamp = String(Math.floor(Date.now() / 1000));
const path = bru.interpolate(decodeUrl(req.getPath()));
const rawQuery = bru.interpolate(decodeUrl(req.getQueryString() || ""));
const canonicalQuery = rawQuery
  ? rawQuery.split("&").sort((a, b) => {
      const left = a.split("=", 1)[0];
      const right = b.split("=", 1)[0];
      return left < right ? -1 : left > right ? 1 : 0;
    }).join("&")
  : "";

const canonical = [
  httpMethod, path, canonicalQuery, timestamp,
  CryptoJS.SHA256(raw).toString(CryptoJS.enc.Hex),
].join("\n");

req.setHeader("X-Timestamp", timestamp);
req.setHeader("X-Signature", CryptoJS.HmacSHA256(canonical, secret).toString(CryptoJS.enc.Hex));
```

Notes:
- Sign the **interpolated** body (`bru.interpolate`), because that is what the server receives.
- Query keys are sorted; repeated keys keep their relative order.
- The signature and secret are never logged. Debug with the timestamp only.

## 4. Webhook body signature (provider callback style)

The signature covers the exact raw body, so the body is built once and signed as a string:

```js
// before-request
const CryptoJS = require("crypto-js");
const secret = required("providerSecret", "Set providerSecret in the environment before sending.");

const pinnedEvent = (bru.getEnvVar("webhookEventId") || "").trim();
const eventId = pinnedEvent || ("evt-" + Date.now());   // fresh event unless pinned for a retry
const bodyString = JSON.stringify({
  eventId: eventId,
  paymentIntentId: bru.interpolate("{{paymentIntentId}}"),
  status: "SUCCEEDED",
  amount: Number(bru.interpolate("{{amountVnd}}")),
  currency: "VND",
});

req.setBody(bodyString);
req.setHeader("X-Provider-Signature",
  CryptoJS.HmacSHA256(bodyString, secret).toString(CryptoJS.enc.Hex));
```

Retries of the same event keep the pinned `webhookEventId` - providers process one event ID
once.

## 5. Fresh idempotency key per logical write

```js
// before-request
const pinned = (bru.getEnvVar("idempotencyKey") || "").trim();
if (!pinned) bru.setVar("idempotencyKey", "uuid-" + Date.now() + "-" + Math.random().toString(16).slice(2));
```

Better: set `{{idempotencyKey}}` in the environment per run. The rule is behavioral: **new
operation -> new key; retry of the same operation -> same key and same payload.** Never reuse a
key with a different payload (servers answer `409` or, worse, replay the wrong response).

## 6. Tests

Assert the status first, then the one invariant that matters:

```js
// tests
test("HTTP 200 expected", function () {
  expect(res.status).to.equal(200);
});

test("created resource returns an id", function () {
  expect(res.body.id).to.be.a("string");
});
```

Money-style invariant example (integer smallest unit - never floats):

```js
test("balance increased by the deposited amount", function () {
  const before = Number(bru.getEnvVar("balanceBefore"));
  expect(res.body.balance).to.equal(before + Number(bru.getEnvVar("amountMinor")));
});
```

## 7. Capturing IDs for later requests

```js
// after-response
if (res.body && res.body.id) bru.setVar("createdId", res.body.id);
```

Downstream requests use `{{createdId}}`. When a run must not overwrite a known ID, pin it in
the environment and skip the capture.

## 8. Error output

Fail with a message that says what to do:

```js
if (res.status >= 400) {
  console.log("Request failed: " + res.status + " " + JSON.stringify(res.body).slice(0, 300));
}
```

Truncate bodies; never dump a full response that might contain tokens.
