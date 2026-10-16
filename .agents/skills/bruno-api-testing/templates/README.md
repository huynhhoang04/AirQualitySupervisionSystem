# Bruno - My Project API

Bruno workspace for testing the **My Project** API. Replace every placeholder (`My Project`,
URLs, variable names) with the real project's values and delete the sample requests once real
ones exist.

## Import and pick an environment

1. In Bruno choose **Open Workspace** and open this folder (or **Reload Workspace** if it is
   already open).
2. Open the environment menu (top right), pick **local** (workspace-level, in `environments/`).
   If it does not appear, **Reload Workspace** first.
3. Fill the plain variables on the variable tab and press **Save**. Variables marked
   `secret: true` (`hmacSecret`, `providerSecret`, tokens, passwords) go to the **Secret
   Variables** tab - Bruno stores them encrypted locally and does not write them back to the
   file.
4. Collections that need their own hosts/tokens use a collection environment
   (`collections/<collection>/environments/`) - pick it in that collection's environment menu.

## Auth flow

1. Run `01 - Auth - Login` first: it signs the request (Gateway HMAC) and stores
   `accessToken`/`refreshToken` in Bruno runtime variables via `bru.setVar()`.
2. Authenticated requests send `Authorization: Bearer {{accessToken}}`.
3. Request signing (if the gateway requires it) is done per request in `before-request` by the
   recipe in the skill: `METHOD\nPATH\nCANONICAL_QUERY\nTIMESTAMP\nSHA256(body)` with
   `X-Timestamp` / `X-Signature` headers. Scripts never print the secret or the signature.
4. Webhook-style requests sign the exact raw body and send it as the provider's signature
   header. Each event ID is processed once.

## Reaching internal services

Internal/admin ports are loopback-only on the server. From a laptop use an SSH tunnel (script
or `ssh -L`), and keep the mapping instructions generic - remote ports change per deploy:

```bash
# read the real ports from the deploy config on the target host, then:
ssh -N -L 8081:127.0.0.1:<remote-port> <user>@<host>
```

Never open a backend port to the internet just to make testing easier.

## Safety notes

- Read-only requests can be run freely. Requests `02 - Widgets - Create` and any gRPC write RPC
  have side effects - send them one at a time from the app, never via Collection Runner.
- Use a demo account and small integer amounts. Every logical write gets a fresh
  `{{idempotencyKey}}` (a UUID); keep the same key and payload when retrying the same
  operation, never reuse a key for a different payload.
- Do not paste real secrets into request files. If a secret was ever committed, rotate it.

## Inventory

- `01 - Public REST`: 2 sample requests (login + signed write) - replace with the real set.
- `02 - gRPC`: 1 sample unary read - add RPCs per the service's proto.
