# Workspace Layout and File Formats

Reference for the `bruno-api-testing` skill. Describes every file that a workspace contains and
the exact schema of each format.

## 1. `workspace.yml` (OpenCollection 1.0.0)

```yaml
opencollection: 1.0.0
info:
  name: "My Project API"
  type: workspace

collections:
  - name: "01 - Public REST (Gateway)"
    path: "collections/01-public-rest"
  - name: "02 - Internal REST (Tunnel only)"
    path: "collections/02-internal-rest"

specs:

docs: "README.md"
```

- `collections[].path` must match the folder on disk; the numeric prefix keeps order stable.
- `docs` points at the workspace README.

## 2. REST collection - OpenCollection YAML

`opencollection.yml`:

```yaml
opencollection: 1.0.0
info:
  name: My Project API - Public REST
config:
  proxy:
    inherit: true
    config:
      protocol: http
      hostname: ''
      port: ''
      auth:
        username: ''
        password: ''
      bypassProxy: ''
bundled: false
extensions:
  bruno:
    ignore:
    - .git
    - node_modules
```

Request file (`NN-name.yml`) anatomy - all keys Bruno reads:

```yaml
info:
  name: 02 - Auth - Login          # "NN - Human Name", matches the filename
  type: http
  seq: 2                           # equals the file's NN
  tags: [public-rest]
http:
  method: POST
  url: "{{baseUrl}}/api/v1/auth/login"   # host always comes from a variable
  headers:
    - name: Content-Type
      value: application/json
  auth: inherit
  body:
    type: json                     # json | text | none | ...
    data: |                        # raw body; scripts may overwrite with req.setBody()
      {
        "username": "{{demoUser}}",
        "password": "{{demoPassword}}"
      }
runtime:
  scripts:
    - type: before-request         # mutate/sign the outgoing request
      code: |
        // see references/scripting-recipes.md
    - type: after-response         # capture tokens/IDs
      code: |
        if (res.body && res.body.accessToken) bru.setVar("accessToken", res.body.accessToken);
    - type: tests                  # assertions
      code: |
        test("HTTP 200 expected", function () {
          expect(res.status).to.equal(200);
        });
settings:
  encodeUrl: true
  timeout: 30000
  followRedirects: true
  maxRedirects: 5
docs: >                            # REQUIRED for mutating requests: side effects,
  Side effect: creates a session.  # idempotency behavior, warnings
```

## 3. Environment files

Workspace level: `environments/<env>.yml` (OpenCollection schema):

```yaml
name: local
variables:
  - name: baseUrl
    value: http://127.0.0.1:8080
    description: API base URL. Local = loopback, remote = env-specific URL from the deploy docs.
  - name: demoUser
    value: ""
    description: Username of the demo account (provided by the operator).
  - name: hmacSecret
    value: ""
    secret: true                   # value is entered in Bruno's Secret Variables tab
    description: HMAC signing key. Never commit a value; rotate if it was ever exposed.
  - name: idempotencyKey
    value: ""
    description: Fresh UUID per logical write; keep unchanged when retrying the same operation.
```

Collection level: `collections/<collection>/environments/<env>.yml` - same schema, used when a
collection needs its own hosts/tokens (e.g. internal or admin services).

Native gRPC collection: `collections/<collection>/environments/<env>.bru`:

```
vars {
  grpcHost: 127.0.0.1:9090
  accountId: 00000000-0000-0000-0000-000000000000
}
```

Rules:
- Every variable has a `description` saying where the value comes from.
- `secret: true` for anything sensitive; the committed `value` stays empty.
- Overrides for one machine go in `*.local.yml` / `*.local.bru` (gitignored).

## 4. Native `.bru` collection (gRPC)

`bruno.json`:

```json
{
  "version": "1",
  "name": "My Project - gRPC (Tunnel only)",
  "type": "collection",
  "ignore": [".git", "node_modules"]
}
```

Request (`NN-name.bru`):

```
meta {
  name: GetBalance
  type: grpc
  seq: 4
}

grpc {
  url: {{grpcHost}}
  method: /ledger.v1.LedgerService/GetBalance
  body: grpc
  protoPath: proto/ledger.proto
  auth: inherit
  methodType: unary
}

body:grpc {
  name: message 1
  content: '''
    {
      "accountId": "{{accountId}}"
    }
  '''
}
```

- `proto/` sits next to the request files; imported protos are registered in Collection
  Settings and listed in the workspace README.
- JSON and text-proto bodies both work; pick one per collection and stay consistent.

## 5. Numbering gaps

Prefer inserting with a free number over renumbering history: `05, 10, 11` is fine. The
filename number, `info.seq`, and `info.name` always agree. Renumbering breaks links in docs
and runbooks that refer to "request 12".
