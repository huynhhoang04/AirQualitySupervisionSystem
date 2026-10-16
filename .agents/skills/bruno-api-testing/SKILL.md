---
name: bruno-api-testing
description: Bruno API testing workspace conventions - workspace.yml and OpenCollection YAML collections, native .bru collections for gRPC, environment and secret handling, HMAC and bearer auth scripting, token capture, webhook signing, test assertions, naming/numbering, and side-effect safety. Use when creating or extending a Bruno workspace, adding requests/collections/environments, writing Bruno request scripts or tests, or testing a REST/gRPC API with Bruno.
license: MIT
metadata:
  package: bruno-skills
  version: "1.0"
---

# Bruno API Testing

Conventions for building and maintaining a **Bruno** workspace that other people (and coding
agents) can pick up cold. The rule that outranks everything else:

> **A workspace is only useful if someone else can run it safely - secrets stay out of git,
> side effects are labelled, and naming never lies.**

Authoritative detail lives in [references/workspace-layout.md](references/workspace-layout.md)
(formats and environments), [references/scripting-recipes.md](references/scripting-recipes.md)
(scripts), and [references/safety-checklist.md](references/safety-checklist.md) (safety).

## 1. Workspace layout

```
<workspace>/
├─ workspace.yml                 # OpenCollection 1.0.0 manifest - names and paths
├─ README.md                     # how to import, auth flow, safety notes, inventory
├─ .gitignore                    # .env*, *.local.yml, *.local.bru, node_modules
├─ collections/
│  ├─ 01-<domain>/               # OpenCollection YAML collection (REST)
│  │  ├─ opencollection.yml
│  │  ├─ 01-<verb>-<name>.yml    # one request per file, zero-padded seq
│  │  └─ environments/           # optional collection-level environments
│  └─ 02-<domain>/               # native .bru collection (gRPC or script-heavy)
│     ├─ bruno.json
│     ├─ 01-<rpc>.bru
│     ├─ proto/                  # .proto files referenced by protoPath
│     └─ environments/<env>.bru
└─ environments/
   └─ <env>.yml                  # workspace-level environments
```

Collections are numbered because the number **is the demo/test order**. Requests inside a
collection are numbered by the order an operator should run them (auth first).

## 2. Choose one format per collection - never mix

| Format | Use for | Files |
|---|---|---|
| OpenCollection YAML | REST/HTTP collections | `opencollection.yml` + `<NN>-<name>.yml` |
| Native `.bru` | gRPC, or requests where `.bru` ergonomics matter | `bruno.json` + `<NN>-<name>.bru` |

Bruno does not allow mixing the two formats in one collection. When a project needs both REST
and gRPC, ship two collections and say so in the workspace README.

## 3. Naming and numbering

- Collection folders: `NN-<kebab-domain>`, name in `workspace.yml` as `NN - Human Name`.
- Request files: `NN-<kebab-name>.yml`/`.bru`, where `NN` is a **stable** sequence number.
  Never renumber existing files when inserting - leave gaps (05, 10, 15) or use the next free
  number, and set `seq` to the file's number.
- `info.name` is `NN - Human Name` (matches the filename so grep and the UI agree).
- `info.tags` carries the collection tag (e.g. `public-rest`, `internal-rest`, `admin`).

## 4. Environments and secrets

- One environment file per target (`local`, `staging`, `vps-...`), schema:
  `name` + `variables: [{name, value, description, secret?}]`. See
  [references/workspace-layout.md](references/workspace-layout.md) section 3.
- **Every variable gets a `description`** - where the value comes from and who provides it.
- Anything sensitive (`*Secret`, `*Token`, `password`, API keys) is marked `secret: true` and
  left with an empty `value` in the file. The value is entered in Bruno's **Secret Variables**
  tab, stored encrypted on that machine, and never committed.
- Requests reference variables as `{{name}}` and read them in scripts via `bru.getEnvVar()`
  with a `bru.getSecretVar()` fallback. Scripts **throw** when a required secret is missing
  instead of sending an unsigned request.
- Machine-specific overrides use `*.local.yml` / `*.local.bru` files - gitignored by design.
- Real hostnames belong in environment variables (`{{baseUrl}}`), never hardcoded in a request
  file. Loopback (`127.0.0.1`) is fine for tunnel-only targets.

## 5. Request scripting

Full copy-paste code: [references/scripting-recipes.md](references/scripting-recipes.md).

- `before-request` - sign or mutate the outgoing request: HMAC request signing
  (`METHOD\nPATH\nCANONICAL_QUERY\nTIMESTAMP\nSHA256(body)`), webhook body signatures,
  fresh idempotency keys.
- `after-response` - capture tokens/IDs into runtime variables with `bru.setVar()`; never write
  them back into environment files.
- `tests` - assert status first, then the one invariant that matters (e.g. a write returned an
  id, a balance moved by the expected amount).
- Scripts never `console.log` a secret or a full signature. A missing variable raises a clear
  error naming the variable and the environment tab to set it in.

## 6. Auth patterns

| Pattern | Where it is set | Recipe |
|---|---|---|
| Bearer JWT | `Authorization: Bearer {{accessToken}}`, token captured at login | scripting-recipes §2 |
| HMAC request signing | `X-Timestamp` + `X-Signature` headers, per request | scripting-recipes §3 |
| Internal token | static header (e.g. `X-Internal-Token: {{internalToken}}`) | - |
| Webhook signature | signature over the **raw body**, sent as the provider's header | scripting-recipes §4 |
| Audit/admin | separate base URL and token from the public gateway | - |

Auth endpoints that skip JWT still get whatever request-level signing the gateway requires -
record that fact in the request's `docs:` field.

## 7. Side-effect safety

Every request that mutates state is identified **in the file**, not in someone's head:

- Its `docs:` field states the side effect and any idempotency behavior.
- Writes carry an `{{idempotencyKey}}` (or the API's equivalent): a fresh UUID per logical
  operation, **kept identical on retry of the same operation**, never reused for a different
  payload.
- Never run Collection Runner over a workspace containing writes. Read-only suites may be
  runnable in bulk; writes are sent one at a time from the Bruno app.
- Destructive or demo-only endpoints (simulate callbacks, forced reconciliation, test
  notifications) are named as such in the filename and disabled on production targets.

Details: [references/safety-checklist.md](references/safety-checklist.md).

## 8. gRPC collections

- `bruno.json` declares the collection name and ignores (`.git`, `node_modules`).
- Each request sets `grpc.url: {{grpcHost}}`, `method: /<package>.<Service>/<Rpc>`,
  `methodType: unary|server-streaming|...`, and `protoPath: proto/<file>.proto`.
- Imported protos live under `proto/` next to the collection and are added in Collection
  Settings; the README lists every proto to register.
- The gRPC environment is a native `<env>.bru` (`vars { key: value }`) inside
  `collections/<grpc-collection>/environments/`.
- Bruno's script/runner/CLI support for gRPC varies by version - gRPC requests target the
  interactive app; state that in the README.

## 9. The workspace README

A workspace is not done until its README covers: how to open it in Bruno, which environment
each collection uses, the auth flow in execution order, how to reach non-public targets
(tunnels, not opened ports), a **safety notes** section, and an inventory (request count per
collection). The template at `templates/README.md` in this package is the starting point.

## 10. Definition of done for a workspace change

- [ ] Request file name, `info.name`, and `seq` agree and follow the numbering convention.
- [ ] Every variable used has a `description`; every secret is `secret: true` with an empty
      committed value.
- [ ] No secret, token, or real personal data in any committed file or script log.
- [ ] Auth works end-to-end from a clean environment (login -> capture -> authenticated call).
- [ ] Scripts fail loudly when a required variable is missing.
- [ ] Mutating requests have `docs:` side-effect notes and idempotency handling.
- [ ] `tests` assert the status code and the one invariant that matters.
- [ ] Workspace README updated (inventory, env usage, safety notes).
