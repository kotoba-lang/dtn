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
| Tests | 84 assertions, all green |
| Operator console (UI/UX) | yes |
| Export (CSV/JSON) | yes |
| Shared CSS design system | yes (css.core/operator-theme) |

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

## License

Apache License 2.0.

## Test

```bash
clojure -M:test
```
