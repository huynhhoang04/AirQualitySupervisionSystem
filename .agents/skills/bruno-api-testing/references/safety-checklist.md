# Safety Checklist

Reference for the `bruno-api-testing` skill. A workspace that can move money, change state, or
touch production is a loaded tool - these rules keep it safe to hand to someone else.

## 1. Secrets

- Never commit: HMAC keys, provider secrets, API keys, passwords, real tokens, real account
  numbers, personal data. Committed environment files carry `secret: true` markers with empty
  values.
- Values are entered in Bruno's **Secret Variables** tab (stored encrypted on that machine) or
  in a gitignored `*.local.yml` / `*.local.bru`.
- Scripts never log a secret or a full signature; logs mention variable **names** only.
- If a secret ever lands in a committed file, an old workspace, or a screenshot: **rotate it**
  and say so in the workspace README. Removing it from git history is not enough.

## 2. Side effects

- Every mutating request states its side effect in `docs:` and, when the filename alone is not
  obvious, in the filename itself (`simulate-callback-disabled-in-production`, `run-(side-effect)`).
- Demo-only endpoints are disabled on production targets via configuration, not by convention.
- One provider/webhook event is processed once - do not change an event ID to replay it without
  first checking the downstream state.
- Read-only requests (health, reads, verification endpoints) are the ones safe to run first,
  bulk, or in demos. Say which requests those are in the README's safety notes.

## 3. Idempotency

- Writes carry an idempotency key (or the API's replay guard): fresh UUID per logical
  operation; unchanged when retrying the same operation with the same payload.
- Reusing a key for a different payload is a bug even when the server "happens" to accept it.
- If the API has no idempotency mechanism, treat every write as fire-once and say so in
  `docs:`.

## 4. Collection Runner

- Never run Collection Runner over a workspace that contains writes.
- Bulk runs are allowed only for explicitly read-only suites.
- gRPC requests target the interactive Bruno app - runner/CLI support is version-dependent.

## 5. Environments and targets

- Real hostnames live in environments (`{{baseUrl}}`), never in request files.
- Internal/admin services stay off the public internet: loopback URLs reached through an SSH
  tunnel or on-box execution. Never open a backend port just to make testing convenient - add
  the tunnel instructions to the README instead.
- Tunnel docs give the **method** (script, `upstream.conf` lookup), not hardcoded ports - port
  mappings change per deploy.
- Distinguish environments clearly in their names (`vps-internal`, `staging-admin`); the wrong
  environment selected in the UI is the classic way to hit production.

## 6. Before handing a workspace to someone else

- [ ] `git log -p` (or a repo scan) shows no secret values in any committed revision you own.
- [ ] Environment files: every variable has a description; secrets are empty and marked.
- [ ] Side-effect requests are labelled and understood by someone who did not build them.
- [ ] The README covers auth order, environment choice, safety notes, and inventory.
- [ ] A clean machine can run the read-only suite after filling secrets - nothing relies on
      one developer's runtime variables.
