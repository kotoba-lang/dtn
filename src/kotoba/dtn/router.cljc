(ns kotoba.dtn.router
  "Pure store-and-forward routing decision for a DTN bundle.

  This is a **direct-neighbor-or-store heuristic**: given a bundle and the
  set of links currently known, either forward over a directly reachable
  link to the bundle's destination, or hold the bundle in store awaiting a
  future contact opportunity (custody-free store-and-carry). It is NOT full
  Contact Graph Routing (CGR, RFC 9174) — no future-contact-schedule
  lookahead, no multi-hop path search over a contact graph — and it is NOT
  epidemic / PRoPHET-style multi-hop relay routing — no forwarding to a
  non-destination neighbor on the probabilistic bet that they'll carry the
  bundle closer to its destination. An honestly-scoped subset, the same way
  kotoba-lang/apn documents its RWA solver as a single-link-deviation
  heuristic rather than full k-shortest-paths: this namespace does not
  claim multi-hop routing capability the code doesn't have.

  Portable (.cljc) across JVM / ClojureScript / SCI / GraalVM."
  (:require [kotoba.dtn :as dtn]))

(def default-priority
  "Default transport-kind preference order when route-decision isn't given
  an explicit priority: cheapest/fastest first. Callers running
  resilience-first (e.g. internet is known down) should pass their own
  ordering — see route-decision's :priority option."
  [:internet-overlay :mesh-radio :satellite])

(defn- priority-rank [priority]
  (into {} (map-indexed (fn [i k] [k i]) priority)))

(defn route-decision
  "Decide what to do with bundle given the currently known links.

  Finds links in links whose :dtn/neighbor equals the bundle's
  :dtn/destination AND whose :dtn/reachable? is true (a direct-neighbor
  match only — no relay-through-a-third-party consideration). Among
  matches, prefers the one whose :dtn/transport-kind is earliest in
  priority (an ordered vector of transport-kinds; defaults to
  default-priority, but MUST be overridable — a resilience-first caller
  might pass [:mesh-radio :satellite :internet-overlay] when internet is
  down). A transport-kind absent from priority ranks after every listed
  kind.

  Returns {:dtn/action :forward :dtn/via <link>} when a reachable direct
  link to the destination is found, else {:dtn/action :store}."
  [bundle links & {:keys [priority]}]
  (let [priority   (or priority default-priority)
        rank       (priority-rank priority)
        fallback   (count priority)
        dest       (:dtn/destination bundle)
        candidates (filter #(and (= (:dtn/neighbor %) dest) (:dtn/reachable? %)) links)
        best       (when (seq candidates)
                     (apply min-key #(get rank (:dtn/transport-kind %) fallback) candidates))]
    (if best
      {:dtn/action :forward :dtn/via best}
      {:dtn/action :store})))

(defn expire-store
  "Partition bundles into kept/expired per kotoba.dtn/expired? at now-ms.
  Returns {:kept [...] :expired [...]} — the store-and-forward eviction
  pass a node runs to drop bundles whose retention constraint has elapsed."
  [bundles now-ms]
  (let [{expired true kept false} (group-by #(dtn/expired? % now-ms) bundles)]
    {:kept (vec (or kept [])) :expired (vec (or expired []))}))
