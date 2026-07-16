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
fragmentation/reassembly, or BPSec (RFC 9172) security blocks (the
`kotoba.dtn.auth` pre-shared-secret HMAC layer described below is a
narrower, honestly-scoped alternative used only by the TCP transport —
not an implementation of BPSec). Routing is an honestly-scoped
**direct-neighbor-or-store heuristic, plus an optional static
single-hop relay via a caller-configured next-hop table** (`:routes`,
see below) — not full Contact Graph Routing (CGR, RFC 9174), not
automatic route discovery/advertisement, and not epidemic/PRoPHET
multi-hop relay. Portable `.cljc`
across JVM / ClojureScript / SCI / GraalVM.

## Maturity

| | |
|---|---|
| Role | capability |
| Tests | 147 assertions, all green (`clojure -M:test`, pure `.cljc` only) |
| Operator console (UI/UX) | yes |
| Export (CSV/JSON) | yes |
| Shared CSS design system | yes (css.core/operator-theme) |
| Internet-overlay transport (real I/O) | yes — plain TCP via nbb, see below; E2E demo green (7/7 scenarios) |
| Durable store (`:store-path`) | yes — disk-backed, survives process restart; not crash-atomic on the rewrite step, see below |
| Bundle integrity (`:peer-secrets`) | yes — pre-shared-secret HMAC-SHA256, tamper-evidence + origin check; NOT a PKI, NOT TLS, see below |
| Replay protection (`:dtn/sequence-number` high-water mark) | yes — per-source monotonic sequence tracking, layered on `:peer-secrets`; **only applies to authenticated peers**; high-water marks persist to disk when `:replay-state-path` is configured (in-memory only, resets on restart, otherwise), see below |
| Multi-hop relay routing (`:routes`) | yes — static/pre-configured single-hop relay via a caller-supplied next-hop table (`router/route-decision`'s `:relay` action), hop-count/`max-hops` loop prevention; NOT automatic route discovery/advertisement, NOT full Contact Graph Routing (RFC 9174), see below |
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

;; No direct link to the destination, but a caller-configured static route
;; through a reachable next-hop -> :relay (see "Internet-overlay transport" below).
(def b2 (dtn/bundle "+819012345678" "+12125550199" {:body "hi"}))
(def via-relay (link/link "L2" "+447700900123" :internet-overlay :reachable? true))
(router/route-decision b2 [via-relay]
                        :routes [{:dtn/destination "dtn:+12125550199"
                                  :dtn/next-hop "dtn:+447700900123"}])
;; => {:dtn/action :relay :dtn/via {...L2...} :dtn/next-hop "dtn:+447700900123"}

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
`:dtn/transport-kind` that `kotoba.dtn.link` already models. It's `.cljs`,
not `.cljc` — it only runs under a Node-hosted ClojureScript runtime
([`nbb`](https://github.com/babashka/nbb) in this repo) and is never
loaded by the JVM `clojure -M:test` suite, so it cannot regress the 147
pure-data assertions above. (`kotoba.dtn.store` and `kotoba.dtn.auth`,
below, keep the same split: their format/canonicalization logic
(including `kotoba.dtn.auth`'s replay-protection decision functions,
`replay?` / `update-high-water-mark`, and its high-water-mark
serialize/deserialize pair) is portable `.cljc` and covered by
`clojure -M:test`, only the actual disk I/O / HMAC-vs-platform-crypto-module
calls are Node-`.cljs`-specific, behind reader conditionals.)

**Wire framing and socket-pool plumbing now come from
[`kotoba-lang/wire`](https://github.com/kotoba-lang/wire).** This
namespace used to hand-roll its own length-prefix framing directly on
Node `Buffer`s, its own per-connection buffer-accumulation/defragmentation
logic, and its own ad-hoc outbound socket pool — none of it actually
DTN-specific. That generic "move EDN maps over a TCP byte stream"
plumbing has been extracted into `kotoba-lang/wire` (built on
[`kotoba-lang/bytes`](https://github.com/kotoba-lang/bytes)'s portable
byte-vector primitives), so `kotoba-lang/turn`'s future relay I/O and
`kotoba-lang/net`'s future gossip I/O can share it too instead of
re-implementing the same mechanics. This namespace now calls
`kotoba.wire.tcp/start-server!` / `connect-or-reuse!` / `send-framed!` /
`close-all!` for the actual socket I/O. The wire FORMAT itself is
unchanged (still a 4-byte big-endian length prefix + UTF-8 `pr-str`'d EDN
payload — see `kotoba-lang/wire`'s README for the format spec), so this
refactor is provably wire-compatible with everything below — every
DTN-specific behavior (store-and-forward, relay, auth, replay checking)
is untouched; only the low-level "how do bytes get framed and pushed down
a socket" mechanics moved out.

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
produces), decoded on the read side with `clojure.edn/read-string`. As of
the `kotoba-lang/wire` extraction (above), the actual framing/encoding
implementation lives in `kotoba.wire.edn` + `kotoba.wire.framing` — this
namespace only calls it (`encode-frame`, `kotoba.wire.tcp/send-framed!`)
rather than implementing it inline; the format on the wire is identical.

**Resilience.** `route-and-send!` runs the pure `router/route-decision`,
then actually attempts delivery; if the peer refuses the connection right
now (down, unreachable) — which the pure decision has no way to know in
advance — the bundle falls back to the node's `:store` instead of being
lost. `retry-store!` re-attempts every stored bundle (after dropping
anything past its RFC 9171 lifetime) — this is the actual store-and-carry
property the whole design exists for, exercised for real in the demo.

**Durable store (`:store-path`, `kotoba.dtn.store`).** Until recently
`:store` was an in-memory-only atom: a node process crash or restart
silently lost every undelivered bundle, which directly contradicted this
library's own disaster/carrier-outage resilience purpose. `start-node!`
now accepts an optional `:store-path <file>`. When set:

- On startup, `:store` is seeded from that file (`kotoba.dtn.store/load-store`)
  — a restarted node picks up exactly where it left off.
- Every bundle that falls back into `:store` (a failed forward, or no
  reachable link at all) is also durably `append-bundle!`'d to the same
  file, synchronously, in the same step as the in-memory `swap!`.
- After a `retry-store!` pass settles, the file is `rewrite-store!`'d to
  match the node's resulting in-memory `:store` — so delivered/expired
  entries drop out and the log doesn't grow forever.

**Format**: newline-delimited `pr-str`'d EDN, one bundle per line —
human-diffable, and a truncated last line from a crash mid-append can
only ever corrupt that last line (`deserialize-line` returns `nil` for a
blank/malformed line rather than throwing, so it doesn't take down reads
of everything before it).

**What "restart-safe" does and does NOT mean.** `append-bundle!` uses
`fs.appendFileSync`, so a crash mid-append can, at worst, leave one
truncated trailing line — every previously-flushed line survives intact.
`rewrite-store!` (used after a successful `retry-store!` pass) uses
`fs.writeFileSync`, which truncates-then-writes the whole file: **this
one step is NOT crash-atomic** — a crash during that specific call can
leave the file empty or partially written, losing bundles that were
already durably appended earlier in that same rewrite pass (though never
bundles from a completed prior append/rewrite). This is a disclosed
limitation, not a full write-ahead-log with fsync+rename durability —
see `kotoba.dtn.store`'s docstring. When `:store-path` is omitted,
behavior is unchanged from before this option existed (in-memory only).

**Bundle integrity (`:peer-secrets`, `kotoba.dtn.auth`).** The TCP
transport previously had zero authentication: any TCP client could
connect and inject a bundle claiming to be from any `:dtn/source` E.164,
with no way for the receiver to know it wasn't forged. `start-node!` now
accepts an optional `:peer-secrets {e164 secret-string ...}` map — a
pre-shared symmetric secret per peer. When a secret is configured for a
peer:

- Outbound bundles to that peer are `kotoba.dtn.auth/sign-bundle`'d
  (HMAC-SHA256 over the bundle's canonical `pr-str`, with any prior
  `:dtn/signature` stripped before computing) before the frame is
  written to the socket.
- Inbound bundles claiming that peer as `:dtn/source` are
  `verify-bundle`'d before being accepted into `:inbox`. A
  missing/invalid signature is logged and the bundle is **dropped
  outright** — never added to `:inbox`, and never added to `:store`
  either (a rejected forgery is a security event, not a delivery to
  retry later).

When no secret is configured for a given peer — the common case, and the
only case for the existing CLI/demo scenarios 1-3 — behavior is
unchanged from before this option existed: bundles are sent/accepted
unsigned, exactly as before.

**What this does and does NOT protect against.** This is **shared-secret
HMAC-SHA256 per peer, not a PKI, not TLS**:

- DOES detect a bundle whose body was altered after signing
  (tamper-evidence), and reject a bundle claiming a source this node has
  no secret for or one signed with the wrong secret (origin
  authentication within a small pre-configured peer set).
- Does NOT provide a PKI or certificate issuance/revocation system, does
  NOT provide transport-layer confidentiality (bundle bodies still
  travel in cleartext — this only detects forgery, it doesn't hide the
  payload), and is NOT forward-secret (one leaked shared secret
  compromises every bundle signed with it, past and future, until
  rotated).
- Does **NOT** protect against a peer whose secret has actually leaked —
  a holder of the secret can forge indistinguishable bundles.
- **DOES now provide replay protection — but ONLY for authenticated
  peers** (a `:peer-secrets` entry configured for that source). See the
  next section.

An honest tamper-evidence/origin-check for a small pre-configured peer
set — not a general Internet-security solution.

**Replay protection (`kotoba.dtn.auth/replay?` +
`update-high-water-mark`, wired in by `kotoba.dtn.transport.tcp`).** The
bundle-integrity work above closed forgery/tampering but explicitly
disclosed a real, still-open gap: a legitimately-signed bundle, captured
off the wire, could be re-sent later and would be accepted again as if
it were new — the signature is still valid; nothing checked "have I seen
this exact bundle, or an older one from this source, before." This is
now closed, reusing a field `kotoba.dtn/bundle` already had rather than
inventing a new one: `:dtn/sequence-number` (previously always defaulted
to 0 and never actually incremented by any caller). Because
`sign-bundle` signs the ENTIRE bundle map, `:dtn/sequence-number` is
already covered by the signature once `:peer-secrets` is configured for
a peer — an attacker without the shared secret cannot forge a higher
sequence number.

- **Sending side.** Every `start-node!`'d node keeps its own monotonic
  outbound `:next-sequence-number` counter, seeded from `js/Date.now()`
  (wall-clock milliseconds) at startup. Every LOCALLY-ORIGINATED
  `send-message!` call stamps the next value from that counter onto
  `:dtn/sequence-number` before the bundle is signed. Relaying an inbound
  bundle onward (the destination-mismatch path) does **NOT** touch
  `:dtn/sequence-number` — only `:dtn/hop-count` is incremented — because
  a relay node is not the bundle's source; the original sender's sequence
  number passes through unchanged.
- **Receiving side.** Once a bundle has passed HMAC verification for a
  source this node has a `:peer-secrets` entry for, `handle-inbound-bundle!`
  additionally checks whether `:dtn/sequence-number` is **strictly
  greater** than the highest one already accepted from that exact source
  (a per-source high-water mark kept on the node handle as
  `:replay-high-water-marks`). If not — an exact resend, or an older
  bundle arriving out of order — the bundle is **REJECTED**: logged
  distinctly (`DTN-REPLAY-REJECTED`), never added to `:inbox`, never
  stored, never relayed onward — the same "security event, not a
  legitimate delivery" treatment the existing signature-rejection path
  already gets.
- **Unauthenticated sources are NOT covered.** When no `:peer-secrets`
  entry is configured for a source, replay checking does not apply at
  all — this is a property of authenticated peers only, not a general
  property of the transport. Without a real signature backing it, a
  claimed sequence number proves nothing: an unauthenticated attacker
  could just as easily forge a high one.

**Persistence decision (high-water-mark durability across a restart).**
An in-memory-only high-water-mark map would reset to empty on every node
restart — meaning an attacker who captured an old, legitimately-signed
bundle before the restart could replay it successfully right after,
silently defeating the protection above. This is handled, not left
silent: `start-node!` accepts an optional `:replay-state-path <file>`
(mirrors `:store-path`'s pattern exactly, via new
`kotoba.dtn.auth/load-high-water-marks` /
`kotoba.dtn.auth/save-high-water-marks!` functions, same pure-format/impure-I/O
split as `kotoba.dtn.store`). When set, `:replay-high-water-marks` is
seeded from that file at startup and durably rewritten every time a new
high-water mark is accepted from an authenticated peer — verified for
real (not just in isolated unit tests): a node was started with
`:replay-state-path`, sent one authenticated message, "restarted"
(`stop-node!` + a fresh `start-node!` with the same path), and its
reloaded `:replay-high-water-marks` correctly recovered the prior
high-water mark. **When `:replay-state-path` is omitted (as in this
repo's own demo/CLI usage), the high-water-mark map is in-memory only —
a restart resets it, and a captured bundle from before that restart
could be replayed successfully right after it.** This is a disclosed
limitation of the unconfigured case, not a silently-shipped gap: combine
replay protection with `:replay-state-path` (ideally alongside
`:store-path`, so both durable-state files persist together) whenever a
node's replay protection genuinely needs to survive a restart; a node
that doesn't use `:store-path` either is no worse off than it already
was — in-memory-only replay tracking is consistent with that node's own
already-volatile `:store`/`:inbox`.

**Multi-hop relay routing (`:routes`, `kotoba.dtn.router`'s `:relay`
action).** `route-decision` previously only ever checked whether a
bundle's destination was a *directly* reachable neighbor — if not, its
only other option was `:store`, with no way for a message to travel
through an intermediate node when no direct link to the final
destination existed. `start-node!` now accepts an optional `:routes`
coll (entries shaped `{:dtn/destination eid :dtn/next-hop eid}` — "to
reach `:dtn/destination`, forward to `:dtn/next-hop` instead"),
consulted only when there's no direct link to the bundle's destination:

- If a `routes` entry matches the destination AND a link to that entry's
  next-hop is currently reachable, `route-decision` returns
  `{:dtn/action :relay :dtn/via <link> :dtn/next-hop <eid>}` — a
  distinct action from `:forward` (a relay hop forwards the bundle one
  step closer via a configured intermediate, not a final-hop delivery)
  even though the actual socket write is identical either way. `:routes`
  is consulted by `route-and-send!` for both this node's own
  locally-originated sends and when this node relays an inbound bundle
  addressed to someone else (below).
- If no route entry matches, or the matched next-hop isn't currently
  reachable, behavior is unchanged: falls through to `:store`.

**Scope: STATIC, pre-configured relay only.** `:routes` is a table the
caller supplies (e.g. a node operator's own config) — there is **no
automatic route discovery, no route advertisement/gossip between
nodes, and no dynamic topology learning** here. This is NOT full Contact
Graph Routing (CGR, RFC 9174) and NOT epidemic/PRoPHET-style multi-hop
relay — it's "single-hop-relay-via-a-configured-next-hop", one step
beyond the prior direct-neighbor-or-store heuristic, not a general
multi-hop routing protocol. Automatic route discovery/advertisement
remains explicitly future work, same as the discovery/NAT-traversal/gossip
composition (`kotoba-lang/net`, `kotoba-lang/turn`) already deferred
above.

**Loop prevention (`:dtn/hop-count`, `max-hops`).** Rather than adding a
`:dtn/hop-count` field to `kotoba.dtn/bundle`'s pure constructor, hop
count is tracked here, at the transport layer: a bundle's `:dtn/hop-count`
(missing/nil treated as 0) is incremented by `handle-inbound-bundle!`
each time this node relays an inbound bundle onward, and passed through
to `route-decision`'s `:hop-count`/`:max-hops` (default
`router/default-max-hops`, currently 8) guard — once hop-count reaches
`max-hops`, `route-decision` refuses to return `:relay` regardless of
`routes`, falling through to `:store` instead, so a bundle that's hopped
too many times without reaching its destination gets held, not endlessly
bounced. This is a simple, honest loop-prevention heuristic — **NOT**
proper DTN routing-loop detection (no per-node "seen this bundle-id
before" dedup is required for it). As a light additional safeguard, each
node also keeps its own `:seen-bundle-ids` set and will not re-relay the
exact same `:dtn/bundle-id` through itself twice — again, node-local
suppression, not cross-node loop detection.

**The destination-mismatch fix.** Before relaying existed, the TCP
transport's inbound handler accepted ANY successfully-decoded (and, when
configured, signature-verified) bundle straight into its own `:inbox`,
regardless of whether the bundle's `:dtn/destination` actually matched
the receiving node's own EID — harmless while every message happened to
be sent directly to its intended recipient (the only usage pattern before
relaying), but wrong once relaying exists: a relay node receiving a
bundle addressed to someone else must not silently absorb it. This is
fixed: `handle-inbound-bundle!` now checks that match. On a match,
behavior is unchanged (added to `:inbox`, `DTN-RECV` logged). On a
mismatch, the bundle is never added to `:inbox` — it's re-routed through
this node's own `route-and-send!` (the same function used for this
node's own locally-originated sends), so it either forwards/relays
onward for real, or falls back to this node's own `:store` if it, too,
has no path right now. This case is logged distinctly as `DTN-RELAY`
(vs. the existing `DTN-RECV`) so logs make the difference legible. See
the E2E demo's scenario 5, below, for a real 3-node A→B→C proof over
actual TCP sockets, including confirmation that the intermediate relay
node's own `:inbox` never absorbs the message.

### CLI (`bin/dtn_node.cljs`)

A minimal demo/dev tool — no config file, no systemd, no TLS:

```bash
# Terminal 1 — long-running node, logs every received message + retry pass
nbb --classpath "src:../phone/src:../html/src:../css/src:../wire/src:../bytes/src" bin/dtn_node.cljs \
  listen --e164 +818098765432 --port 5100

# Terminal 2 — send one kotoba.rcs-shaped chat message, then exit
nbb --classpath "src:../phone/src:../html/src:../css/src:../wire/src:../bytes/src" bin/dtn_node.cljs \
  send --e164 +819012345678 --port 5101 \
  --peer +818098765432:localhost:5100 \
  --to +818098765432 --body "hello"
```

(`--classpath` mirrors `deps.edn`'s sibling `:local/root` layout — this
repo checked out next to `kotoba-lang/phone`, `/html`, `/css`, `/wire`,
`/bytes`. `/wire` and `/bytes` are needed because
`kotoba.dtn.transport.tcp` delegates its wire framing/socket-pool
mechanics to `kotoba.wire` (`kotoba-lang/wire`, built on
`kotoba-lang/bytes`) — see "Internet-overlay transport" above.)

### E2E demo (`test/kotoba/dtn/transport/tcp_demo.cljs`)

An executable proof, not a unit test — run it and read the output:

```bash
nbb --classpath "src:../phone/src:../html/src:../css/src:../wire/src:../bytes/src" \
  test/kotoba/dtn/transport/tcp_demo.cljs
```

Seven scenarios, printing `PASS`/`FAIL` per scenario and a final
`RESULT: N/7 scenarios passed` line (exit 0 iff 7/7):

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
4. **(4a) Durable store survives a process restart** — sends to an
   unreachable peer with `:store-path` configured (the bundle lands in
   `:store` AND is appended to disk), `stop-node!`s the node, then
   `start-node!`s a brand-new node handle with the SAME `:store-path` and
   confirms the reloaded `:store` actually contains the bundle — a real
   restart round-trip through the file, not just the pure
   serialize/deserialize functions checked in isolation.
5. **(4b) Bundle integrity is enforced** — two nodes configured with a
   shared `:peer-secrets` entry exchange a real signed message over TCP
   and confirm it's accepted; then a bundle signed with the WRONG secret
   is written directly to a raw socket via `tcp/encode-frame` (bypassing
   `send-message!`'s normal signing path entirely) and the demo confirms
   it does NOT appear in the receiving node's `:inbox` — rejected, with a
   logged `tcp: REJECTED inbound bundle ...` line.
6. **Static multi-hop relay routing (real TCP, 3 nodes)** — node A is
   configured with NO direct peer/link entry for node C at all, only for
   node B, plus a `:routes` entry saying "to reach C, go via B"; node B
   has a genuine direct peer entry for C. A sends a message to C over
   real TCP. The demo confirms the message does NOT appear via any
   nonexistent A→C link (there isn't one), that it actually flows
   A→B→C (C's `:inbox` receives it for real), that B's `:inbox` does
   **NOT** contain it (B correctly relayed rather than absorbing it —
   directly proving the destination-mismatch fix above), and that B's
   relay was a real routing decision — not an out-of-band cheat — by
   capturing B's own `DTN-RELAY from=... to=... via=...` log line.
7. **Replay protection** — two nodes share a `:peer-secrets` entry; node
   A sends one real, legitimately-signed message to node B over TCP
   (confirmed accepted). The demo captures the EXACT bundle B actually
   decoded off the wire from B's own `:inbox` entry (not a hand-rolled
   reconstruction) and resends those exact same signed bytes a second
   time over a fresh raw socket, bypassing `send-message!`'s normal
   fresh-sequence-number stamping entirely. The demo confirms B's
   `:inbox` still has exactly ONE copy of that message (not two) and
   that a `DTN-REPLAY-REJECTED` line was logged. It then sends a
   genuinely NEW message (a fresh sequence number, via a normal
   `send-message!` call) from A to B and confirms THAT one is accepted
   normally — proving replay rejection doesn't wrongly block legitimate
   subsequent traffic from the same authenticated source.

## License

Apache License 2.0.

## Test

```bash
clojure -M:test
```
