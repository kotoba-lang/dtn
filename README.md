# kotoba-dtn

[![CI](https://github.com/kotoba-lang/dtn/actions/workflows/ci.yml/badge.svg)](https://github.com/kotoba-lang/dtn/actions/workflows/ci.yml)

**RFC 9171 Bundle Protocol Version 7 (Delay/Disruption-Tolerant Networking)
primary-block fields, transport-agnostic store-and-forward routing, and
SMS/RCS interop translation — in pure Clojure.** A
[kotoba-lang](https://github.com/kotoba-lang) capability library modeling
the records a DTN store-and-carry node keeps: endpoint identifiers (EIDs)
built on [`kotoba-lang/phone`](https://github.com/kotoba-lang/phone)'s
E.164 numbering, RFC 9171 §4.3.1 primary-block fields, direct-neighbor
routing decisions, and the structural seam that lets a DTN-delivered
payload interoperate with real carrier SMS/RCS.

No network, no I/O. This models primary-block *fields* as EDN, not the
CBOR wire format — it does NOT implement CBOR encoding, block CRC,
fragmentation/reassembly, or BPSec (RFC 9172) security blocks. Routing is
an honestly-scoped **direct-neighbor-or-store heuristic**, not full Contact
Graph Routing (CGR, RFC 9174) or epidemic/PRoPHET multi-hop relay. Portable
`.cljc` across JVM / ClojureScript / SCI / GraalVM.

## Maturity

| | |
|---|---|
| Role | capability |
| Tests | 84 assertions, all green (`clojure -M:test`, pure `.cljc` only) |
| Operator console (UI/UX) | yes |
| Export (CSV/JSON) | yes |
| Shared CSS design system | yes (css.core/operator-theme) |
| Internet-overlay transport (real I/O) | yes — plain TCP via nbb, see below; E2E demo green |
| Mesh-radio / satellite transport | no (no hardware in scope; routing logic only, see demo scenario 3) |

## Contract

```clojure
(require '[kotoba.dtn :as dtn])
(require '[kotoba.dtn.link :as link])
(require '[kotoba.dtn.router :as router])
(require '[kotoba.dtn.gateway :as gateway])

(dtn/eid "+819012345678")                          ; => "dtn:+819012345678"

(dtn/bundle "+819012345678" "+12125550199" {:body "hi"})
;; => {:dtn/version 7 :dtn/source "dtn:+819012345678"
;;     :dtn/destination "dtn:+12125550199" :dtn/lifetime-ms 86400000 ...}

(def b (dtn/bundle "+819012345678" "+12125550199" {:body "hi"}))
(def l (link/link "L1" "+12125550199" :mesh-radio :reachable? true))

(router/route-decision b [l])
;; => {:dtn/action :forward :dtn/via {...L1...}}

;; Resilience-first: prefer mesh/satellite over internet when it's down.
(router/route-decision b [l] :priority [:mesh-radio :satellite :internet-overlay])

(gateway/bundle->sms b)                            ; unwrap iff SMS-shaped payload
```

## Operator console (UI/UX)

A read-only HTML dashboard renders bundles currently in store and link
reachability, for a node operator. Built on
[`kotoba-lang/html`](https://github.com/kotoba-lang/html) (Hiccup→HTML) +
[`kotoba-lang/css`](https://github.com/kotoba-lang/css) (EDN→CSS). Pure
data → markup; the console never exposes a write surface (no
`<form>`/`<button>`).

```clojure
(require '[kotoba.dtn.ui :as ui])

(ui/dashboard
  {:bundles [(dtn/bundle "+819012345678" "+12125550199" {:body "hi"})]
   :links [(link/link "L1" "+12125550199" :mesh-radio :reachable? true)]})
;; => "<html>...DTN Store-and-Forward — Operator Console...</html>"
```

## Export (CSV / JSON)

Audit-grade CSV (RFC-4180 quoting) and JSON (every RFC 8259 control
character escaped) for bundles-in-store and link reachability.

```clojure
(require '[kotoba.dtn.export :as ex])

(ex/bundles->csv bundles)
(ex/links->csv links)
(ex/bundles->json bundles)
```

## Why

A message shaped like SMS or RCS should be able to egress over whichever
transport link is actually available — internet-overlay P2P, mesh radio,
or satellite — without depending on any single one of them being up, while
staying wire-compatible with real carrier SMS/RCS at the interop seam.
`kotoba-dtn` is that transport-agnostic layer: `kotoba.dtn` models the RFC
9171 envelope a store-and-carry node reasons over, `kotoba.dtn.router`
decides forward-vs-store from whatever links are currently reachable
(with an overridable transport priority, so a resilience-first caller can
deprioritize internet when it's down), and `kotoba.dtn.gateway` is the
structural seam where a `kotoba.phone/sms` record — or an RCS-shaped
message from the independently-built `kotoba-lang/rcs` sibling library —
unwraps from (or wraps into) a bundle payload with no translation step,
because the payload IS that record shape. `kotoba.dtn.gateway` deliberately
never depends on `kotoba-lang/rcs`: RCS interop is checked structurally
(duck-typed on `:rcs/*` keys) so the two libraries stay independently
buildable.

## Internet-overlay transport (real I/O)

Everything above is intentionally I/O-free — pure `.cljc` data and pure
decisions. `kotoba.dtn.transport.tcp` (`src/kotoba/dtn/transport/tcp.cljs`)
is the first namespace in this library that actually moves bytes between
real OS processes: a plain TCP transport for the `:internet-overlay`
`:dtn/transport-kind` that `kotoba.dtn.link` already models, using only
Node's core `node:net` (no npm dependencies). It's `.cljs`, not `.cljc` —
it only runs under a Node-hosted ClojureScript runtime ([`nbb`](https://github.com/babashka/nbb)
in this repo) and is never loaded by the JVM `clojure -M:test` suite, so it
cannot regress the 84 pure-data assertions above.

**Scope.** Direct internet-overlay transport only: a peer's host:port must
already be known (passed to `start-node!` as `:peers`). No discovery, no
NAT traversal, no gossip routing — that composition, via
[`kotoba-lang/net`](https://github.com/kotoba-lang/net) (gossip) and
[`kotoba-lang/turn`](https://github.com/kotoba-lang/turn) (relay), is
explicitly future work, same as this repo's original transport-deferral
ADR. Mesh-radio and satellite transports are **not implemented** — no such
hardware exists in this dev environment — but `kotoba.dtn.router`'s
priority ordering over transport kinds is still real and tested against a
simulated mesh-radio link (see the demo below, scenario 3).

**Wire framing.** Deliberately the simplest thing that works: each message
on the socket is a 4-byte big-endian length prefix followed by that many
bytes of UTF-8 `pr-str`'d EDN (the bundle map `kotoba.dtn/bundle`
produces), decoded on the read side with `clojure.edn/read-string`.

**Resilience.** `route-and-send!` runs the pure `router/route-decision`,
then actually attempts delivery; if the peer refuses the connection right
now (down, unreachable) — which the pure decision has no way to know in
advance — the bundle falls back to the node's `:store` instead of being
lost. `retry-store!` re-attempts every stored bundle (after dropping
anything past its RFC 9171 lifetime) — this is the actual store-and-carry
property the whole design exists for, exercised for real in the demo.

### CLI (`bin/dtn_node.cljs`)

A minimal demo/dev tool — no config file, no systemd, no TLS:

```bash
# Terminal 1 — long-running node, logs every received message + retry pass
nbb --classpath "src:../phone/src:../html/src:../css/src" bin/dtn_node.cljs \
  listen --e164 +818098765432 --port 5100

# Terminal 2 — send one kotoba.rcs-shaped chat message, then exit
nbb --classpath "src:../phone/src:../html/src:../css/src" bin/dtn_node.cljs \
  send --e164 +819012345678 --port 5101 \
  --peer +818098765432:localhost:5100 \
  --to +818098765432 --body "hello"
```

(`--classpath` mirrors `deps.edn`'s sibling `:local/root` layout — this
repo checked out next to `kotoba-lang/phone`, `/html`, `/css`.)

### E2E demo (`test/kotoba/dtn/transport/tcp_demo.cljs`)

An executable proof, not a unit test — run it and read the output:

```bash
nbb --classpath "src:../phone/src:../html/src:../css/src" \
  test/kotoba/dtn/transport/tcp_demo.cljs
```

Three scenarios, printing `PASS`/`FAIL` per scenario and a final
`RESULT: N/3 scenarios passed` line (exit 0 iff 3/3):

1. **Real cross-process delivery** — spawns a real second `nbb` OS process
   running `bin/dtn_node.cljs listen`, sends to its real bound port, and
   confirms delivery by grepping that child process's own stdout for its
   `DTN-RECV` log line (not just checking a local return value).
2. **Store-and-forward across a disconnect** — stops a node's server,
   sends to it (falls into `:store`, not delivered), restarts the server
   on the same port, calls `retry-store!`, and confirms the message now
   arrives and `:store` is empty again.
3. **Multi-transport-kind priority** — pure `router/route-decision` only,
   no real radio: proves the router genuinely picks `:internet-overlay`
   by default and would pick a (simulated) `:mesh-radio` link first under
   a resilience-first `:priority` override.

## License

Apache License 2.0.

## Test

```bash
clojure -M:test
```
