(ns kotoba.dtn.transport.udp
  "A UDP-native sibling to kotoba.dtn.transport.tcp for the internet-overlay
  link kind kotoba.dtn.link already models — plain node:dgram UDP, plus an
  OPTIONAL, genuinely-scoped-down NAT-traversal path via a real
  kotoba-lang/org-ietf-turn TURN relay. This is the Phase 6 gap every prior
  ADR in this series named and deliberately deferred: kotoba.dtn.transport.tcp's
  own README says NAT traversal is 'a materially different, larger problem
  than gossip-based discovery' because TURN relays UDP and dtn's existing
  transport was TCP. This namespace does not paper over that mismatch — it
  adds a real UDP transport FIRST (so there is finally a UDP leg for TURN to
  actually attach to), then attaches TURN to it.

  .cljs, NOT .cljc — real node:dgram socket I/O, same split
  kotoba.dtn.transport.tcp already establishes: only runs under a
  Node-hosted ClojureScript runtime (nbb in this repo), never loaded by the
  JVM `clojure -M:test` suite, so this file can never regress the existing
  pure `.cljc` test suite (see kotoba.dtn.transport.turn-relay for the pure
  helper functions this namespace DOES get real `clojure -M:test` coverage
  for).

  DELIBERATELY SIMPLER THAN kotoba.dtn.transport.tcp'S WIRE FORMAT for the
  non-relayed case: UDP preserves datagram boundaries, so unlike TCP there
  is no stream to frame/defragment at all — kotoba.wire's 4-byte
  length-prefix framing (built for exactly the problem TCP has and UDP
  doesn't) is not used here. A whole `pr-str`'d EDN bundle, UTF-8 encoded,
  IS one datagram, sent and received as one `dgram` `.send`/'message' event.

  ROUTING DECISION / BUNDLE HANDLING LOGIC IS DUPLICATED FROM
  kotoba.dtn.transport.tcp, NOT SHARED. kotoba.dtn.router / kotoba.dtn.auth
  / kotoba.dtn.store / kotoba.dtn.gateway / kotoba.dtn itself are required
  and called for real, exactly as kotoba.dtn.transport.tcp already does —
  this namespace does not reimplement routing, HMAC signing/verification,
  replay-protection decisions, or store-and-forward disk persistence. But
  the transport-layer ORCHESTRATION functions that glue those pure
  namespaces to a socket (handle-inbound-bundle!, links-for, route-and-send!,
  store-bundle!, next-sequence-number!, send-message!, retry-store!) are
  this namespace's OWN copies, not a shared dependency on
  kotoba.dtn.transport.tcp — per this task's own constraint, tcp.cljs is not
  to be touched at all (not even to extract a shared parent namespace out of
  it), so duplicating this ~150-line glue layer (near-identical to tcp.cljs's
  own, only the actual bytes-on-the-wire send/receive mechanics differ) was
  the honest choice available, not an oversight. A future
  kotoba.dtn.transport.common extraction (once both siblings exist and can
  be refactored together) is a reasonable follow-up, out of scope here.

  =====================================================================
  NAT TRAVERSAL VIA kotoba-lang/org-ietf-turn — WHAT THIS ADDS, AND WHAT
  IT DELIBERATELY DOES NOT.
  =====================================================================

  WHAT'S REAL: when `:turn-relay` is configured on a node, that node
  performs a REAL RFC 8656 Allocate against a REAL, already-running
  kotoba.turn.listener (org-ietf-turn's own real UDP relay — reused
  as-is, never modified or reimplemented here), then a REAL
  CreatePermission for one configured peer address, using
  kotoba.dtn.transport.turn-relay's pure STUN-message-construction helpers
  (adapted from org-ietf-turn's own test/kotoba/turn/listener_demo.cljs
  client-side STUN-building sequence — see that namespace's docstring).
  From then on, every outbound bundle this node sends is wrapped in a real
  STUN Send indication and relayed through the TURN server to the
  permitted peer; every inbound datagram on this node's socket is
  classified (kotoba.dtn.transport.turn-relay/classify-inbound-datagram)
  and, when it's a real STUN Data indication, unwrapped and delivered into
  this node's normal bundle-handling pipeline exactly as if it had arrived
  directly. THE REAL CAPABILITY THIS ADDS: a dtn node that is NOT directly
  reachable (simulated here as a node whose real UDP port is never told to
  its peer — see the E2E demo) can still have its bundles relayed through a
  TURN server to a peer that only ever sends to that node's
  TURN-ALLOCATED relayed address, and can still reply back through the
  same relay — genuine bidirectional NAT-traversed delivery, not simulated.

  WHAT THIS IS NOT — same 'claim exactly what was built' discipline every
  namespace in this whole ADR series uses:

    - NOT full ICE (RFC 8445). No STUN Binding-request-based own-NAT-type
      detection, no candidate gathering (host/server-reflexive/relayed
      candidate pairs), no connectivity-check exchange between peers to
      pick the best of several candidate pairs. This namespace has exactly
      ONE candidate for reaching a NAT'd peer — its TURN-relayed address —
      supplied directly by the caller (see the E2E demo's own explicit
      'B only knows A's relay address, never A's real port' framing),
      never discovered or negotiated.
    - NOT automatic permission/allocation refresh. RFC 8656's default
      allocation lifetime is 600s and a permission's is 300s (see
      kotoba.turn.allocation's own defaults) — this namespace calls
      Allocate + CreatePermission exactly ONCE, at start-node! time, and
      never calls Refresh again. A node whose :turn-relay stays up past
      ~5 minutes will have its permission silently expire server-side
      (org-ietf-turn's own periodic sweep drops it), after which relayed
      sends TO that peer, and the peer's relayed replies, both start being
      silently dropped by the TURN server with zero warning from this
      namespace. A production implementation needs a scheduled Refresh
      well before both the 300s permission and 600s allocation expire —
      not implemented here.
    - NOT relay-unreachable fallback. If the TURN server itself goes down,
      or a Send indication is simply lost (UDP, no retransmission), this
      namespace does not fall back to a direct send, does not retry the
      relay, and does not detect the failure at all beyond
      attempt-forward!'s normal 'no ack, so store-and-forward's usual
      route-and-send! failure path treats it as undelivered' behavior —
      there is no ICE-style 'try the next candidate' logic because there is
      only ever the one candidate.
    - NOT multi-peer relaying from one node. CreatePermission is called
      exactly once, for the single `:peer-address` given in `:turn-relay`'s
      config. A node wanting relayed reachability to more than one peer
      through the same allocation would need more CreatePermission calls —
      not implemented; this scoped version is single-peer only, matching
      the E2E demo's own two-node shape.
    - NOT the ChannelBind fast path. Every relayed message pays full STUN
      Send-indication / Data-indication framing overhead (org-ietf-turn's
      listener itself supports the lighter post-ChannelBind ChannelData
      format server-side — see its own README — this client just never
      requests one).
    - Same IPv4-only / ephemeral-short-term-credential-only /
      no-quotas/DoS-limits scope kotoba-lang/org-ietf-turn's own listener
      already discloses (this namespace inherits those limits by
      construction, it doesn't add new ones).

  Reuses kotoba.dtn.router, kotoba.dtn.auth, kotoba.dtn.store,
  kotoba.dtn.gateway, and kotoba.dtn exactly as kotoba.dtn.transport.tcp
  already does — see that namespace's own docstring for what each of those
  provides (durable :store-path, :peer-secrets HMAC-SHA256 signing/
  verification, :dtn/sequence-number replay protection, :routes static
  relay). All of that is transport-agnostic and unmodified here; this
  namespace only changes HOW bytes reach the wire (and, when :turn-relay is
  configured, HOW they detour through a relay first)."
  (:require ["node:dgram" :as dgram]
            [clojure.edn :as edn]
            [promesa.core :as p]
            [kotoba.bytes :as b]
            [kotoba.turn.stun :as stun]
            [kotoba.turn.credential :as cred]
            [kotoba.dtn :as dtn]
            [kotoba.dtn.link :as link]
            [kotoba.dtn.router :as router]
            [kotoba.dtn.gateway :as gateway]
            [kotoba.dtn.auth :as auth]
            [kotoba.dtn.store :as store]
            [kotoba.dtn.transport.turn-relay :as tr]))

;; Forward declarations, mirroring kotoba.dtn.transport.tcp's own: routing
;; and inbound handling are mutually referential (a relayed inbound bundle
;; is handed BACK to route-and-send!, see handle-inbound-bundle! below).
(declare route-and-send! links-for)

;; ---------------------------------------------------------------------------
;; Small Node/byte-vector plumbing this file needs, deliberately
;; re-implemented locally rather than required from kotoba.turn.listener
;; (.cljs-only, real socket I/O, not a reusable library surface) — same
;; 'small enough to duplicate rather than couple to a sibling repo's
;; internal .cljs namespace' choice org-ietf-turn's own
;; test/kotoba/turn/listener_demo.cljs makes for the identical helpers.
;; ---------------------------------------------------------------------------

(defn- vec->buf
  "kotoba.bytes-convention byte vector -> Node Buffer, ready for a dgram
  socket's `.send`."
  [v]
  (js/Buffer.from (clj->js v)))

(defn- rand-txid
  "A fresh random 12-byte STUN transaction id for a request this node
  originates."
  []
  (vec (repeatedly 12 #(rand-int 256))))

;; ---------------------------------------------------------------------------
;; Bundle <-> datagram encode/decode — no length-prefix framing (see
;; namespace docstring): the whole pr-str'd EDN bundle, UTF-8 encoded, IS
;; one UDP datagram.
;; ---------------------------------------------------------------------------

(defn encode-bundle
  "bundle -> a Node Buffer: the exact UTF-8 pr-str'd EDN bytes this
  namespace sends as one UDP datagram for the non-relayed case. Public
  (not `defn-`), mirroring kotoba.dtn.transport.tcp/encode-frame's own
  'public so a demo/test can build a raw datagram directly' rationale."
  [bundle]
  (js/Buffer.from (pr-str bundle) "utf8"))

(defn- log!
  [node-handle-atom & parts]
  (println (str "[" (:e164 (deref node-handle-atom)) "] " (apply str parts))))

(defn- decode-bundle-str
  "s (a UTF-8 string, already decoded off the wire) -> the bundle map it
  encodes, or nil when it doesn't parse as EDN, or parses to something
  other than a map (a bundle is always a map)."
  [s]
  (let [parsed (edn/read-string s)]
    (when (map? parsed) parsed)))

(defn- try-decode-raw-bundle!
  "buf (a Node Buffer, or anything `.toString \"utf8\"` works on) -> the
  bundle it decodes to, or nil (logged) on ANY parse failure — a
  malformed or unrelated datagram arriving on this node's socket is an
  expected, routine occurrence a UDP listener must handle every packet,
  never a crash (same discipline kotoba.turn.listener's own
  handle-datagram! try/catch uses for the identical reason)."
  [node-handle-atom buf]
  (try
    (decode-bundle-str (.toString buf "utf8"))
    (catch :default e
      (log! node-handle-atom "udp: dropping unparseable datagram (" (.-message e) ")")
      nil)))

;; ---------------------------------------------------------------------------
;; Inbound handling — near-identical to kotoba.dtn.transport.tcp's own (see
;; namespace docstring: duplicated, not shared, because tcp.cljs is not to
;; be touched).
;; ---------------------------------------------------------------------------

(defn- peer-secret
  [node-handle bundle]
  (get (:peer-secrets node-handle) (dtn/eid->e164 (:dtn/source bundle))))

(defn- verified?
  [node-handle bundle]
  (let [secret (peer-secret node-handle bundle)]
    (or (nil? secret) (auth/verify-bundle bundle secret))))

(defn- handle-inbound-bundle!
  "Identical decision logic to kotoba.dtn.transport.tcp/handle-inbound-bundle!
  — verify (kotoba.dtn.auth), replay-check (kotoba.dtn.auth), drop expired,
  match-vs-relay on :dtn/destination, append to :inbox on a real match. See
  that namespace's docstring for the full rationale; not re-derived here."
  [node-handle-atom bundle]
  (let [now-ms         (js/Date.now)
        expired?       (dtn/expired? bundle now-ms)
        own-eid        (dtn/eid (:e164 (deref node-handle-atom)))
        mismatch?      (not= (:dtn/destination bundle) own-eid)
        node-handle    (deref node-handle-atom)
        secret         (peer-secret node-handle bundle)
        authenticated? (some? secret)
        sender-e164    (dtn/eid->e164 (:dtn/source bundle))
        prior-hwm      (get (:replay-high-water-marks node-handle) sender-e164)
        replayed?      (and authenticated? (auth/replay? bundle prior-hwm))]
    (cond
      (not (verified? node-handle bundle))
      (log! node-handle-atom "udp: REJECTED inbound bundle claiming source=" (:dtn/source bundle)
            " — signature missing or invalid (dropped, not stored/retried) "
            (:dtn/bundle-id bundle))

      replayed?
      (log! node-handle-atom "udp: REJECTED inbound bundle claiming source=" (:dtn/source bundle)
            " — DTN-REPLAY-REJECTED sequence-number=" (:dtn/sequence-number bundle)
            " not greater than already-accepted high-water-mark=" prior-hwm
            " for this source (replay or reordering; dropped, not stored/retried/relayed) "
            (:dtn/bundle-id bundle))

      :else
      (do
        (when authenticated?
          (swap! node-handle-atom update :replay-high-water-marks
                 auth/update-high-water-mark sender-e164 (or (:dtn/sequence-number bundle) 0))
          (when-let [path (:replay-state-path (deref node-handle-atom))]
            (auth/save-high-water-marks! path (:replay-high-water-marks (deref node-handle-atom)))))
        (cond
          expired?
          (log! node-handle-atom "udp: dropping expired inbound bundle " (:dtn/bundle-id bundle))

          (and mismatch? (contains? (:seen-bundle-ids (deref node-handle-atom)) (:dtn/bundle-id bundle)))
          (log! node-handle-atom "udp: dropping duplicate relay of already-seen bundle "
                (:dtn/bundle-id bundle) " (seen-bundle-id suppression, not full loop detection)")

          mismatch?
          (let [relay-bundle (update bundle :dtn/hop-count (fnil inc 0))]
            (swap! node-handle-atom update :seen-bundle-ids conj (:dtn/bundle-id bundle))
            (let [{:keys [routes] :as node-handle} (deref node-handle-atom)
                  preview (router/route-decision relay-bundle (links-for node-handle)
                                                  :routes routes
                                                  :hop-count (:dtn/hop-count relay-bundle))
                  via-eid (get-in preview [:dtn/via :dtn/neighbor])]
              (log! node-handle-atom "DTN-RELAY from=" (:dtn/source bundle)
                    " to=" (:dtn/destination bundle)
                    " via=" (or via-eid (name (:dtn/action preview)))
                    " hop-count=" (:dtn/hop-count relay-bundle)
                    " (not addressed to this node — relaying onward, not added to :inbox)")
              (route-and-send! node-handle-atom relay-bundle)))

          :else
          (let [decoded (or (gateway/bundle->rcs-shaped bundle)
                             (gateway/bundle->sms bundle))]
            (swap! node-handle-atom update :inbox conj {:message decoded :bundle bundle})
            (if decoded
              (log! node-handle-atom "DTN-RECV from=" (:dtn/source bundle)
                    " body=" (or (:rcs/body decoded) (:phone/body decoded)))
              (log! node-handle-atom "udp: received bundle with unrecognized payload shape "
                    (:dtn/bundle-id bundle)))))))))

;; ---------------------------------------------------------------------------
;; Inbound datagram dispatch — the one place non-relayed and TURN-relayed
;; nodes genuinely differ on the wire. A non-relayed node's socket only
;; ever sees directly-addressed raw bundle datagrams (see start-node!'s
;; docstring for why: a relayed node is, by this scoped design's own
;; definition, not directly reachable, so it never receives one). A
;; relayed node's socket sees real STUN traffic (its own Allocate/
;; CreatePermission responses during startup, consumed by
;; turn-allocate-and-permit!'s own send-and-wait-once! BEFORE this ongoing
;; handler is even installed — see start-node! — plus ongoing Data
;; indications after that), classified via
;; kotoba.dtn.transport.turn-relay/classify-inbound-datagram.
;; ---------------------------------------------------------------------------

(defn- handle-relayed-datagram!
  [node-handle-atom msg]
  (let [raw (vec msg)]
    (case (tr/classify-inbound-datagram raw)
      :data-indication
      (let [{:keys [attrs]} (tr/parse-stun-message raw)
            payload (tr/data-indication-payload attrs)]
        (if-not payload
          (log! node-handle-atom "udp: STUN Data indication with no DATA attribute, dropping")
          (when-let [bundle (try-decode-raw-bundle! node-handle-atom (vec->buf payload))]
            (log! node-handle-atom "udp: inbound bundle arrived via TURN relay (Data indication), "
                  (count payload) " payload byte(s)")
            (handle-inbound-bundle! node-handle-atom bundle))))

      :other-stun
      (log! node-handle-atom "udp: unexpected STUN message on relay-configured node's socket "
            "(not a Data indication) — dropping")

      :raw-bundle
      (when-let [bundle (try-decode-raw-bundle! node-handle-atom msg)]
        (handle-inbound-bundle! node-handle-atom bundle)))))

(defn- handle-direct-datagram!
  [node-handle-atom msg]
  (when-let [bundle (try-decode-raw-bundle! node-handle-atom msg)]
    (handle-inbound-bundle! node-handle-atom bundle)))

;; ---------------------------------------------------------------------------
;; TURN relay client — Allocate + CreatePermission, once, at startup.
;; ---------------------------------------------------------------------------

(def ^:private turn-handshake-timeout-ms
  "How long to wait for the TURN server's response to each of Allocate and
  CreatePermission before giving up on that request. Generous for a
  same-host/loopback demo/dev deployment; a real deployment reaching a
  remote TURN server over a real network may need a longer value or a
  caller-configurable one — not exposed as an option here (scoped-down,
  see namespace docstring)."
  1500)

(defn- send-and-wait-once!
  "Send `data` (a byte vector) from `sock` to host:port over UDP, then wait
  for the very next datagram received on `sock` (assumed to be the reply
  to this exact request — valid ONLY while used sequentially during the
  startup handshake below, before any OTHER 'message' listener is
  installed on this socket). Registers the 'message' listener BEFORE
  sending: a real race exists here — a fast local reply (e.g. against a
  TURN server on loopback) can arrive before a naively-later-registered
  listener would catch it. org-ietf-turn's own
  test/kotoba/turn/listener_demo.cljs documents this exact race
  empirically (its own send-and-wait!/wait-for-message! docstrings: 'a
  promesa.core/p/let binding of a not-yet-registered listener promise
  deadlocks exactly this way') — this function avoids it the same way,
  doing listener-registration and send synchronously, in that order,
  inside one Promise executor. Resolves [byte-vector rinfo], or nil if
  nothing arrives within timeout-ms."
  [sock data host port timeout-ms]
  (js/Promise.
   (fn [resolve _reject]
     (let [done (atom false)
           finish! (fn [v] (when-not @done (reset! done true) (resolve v)))
           timer (js/setTimeout #(finish! nil) timeout-ms)]
       (.once sock "message"
              (fn [msg _rinfo]
                (js/clearTimeout timer)
                (finish! [(vec msg) nil])))
       (.send sock (vec->buf data) port host (fn [err] (when err (finish! nil))))))))

(defn- turn-allocate-and-permit!
  "Perform a real Allocate (RFC 8656 §7.2) then a real CreatePermission
  (§9) against `turn-relay`'s configured TURN server, over `sock` — the
  SAME socket this node uses for all its ongoing dtn traffic once this
  returns (see start-node!, which only installs the ongoing 'message'
  handler AFTER this resolves, so there is no listener conflict with the
  sequential .once-based exchanges here). Returns a
  Promise<{:relayed-address {:ip [o1 o2 o3 o4] :port n} :username ..
  :credential ..} | nil> — nil on ANY failure (timeout, an error response,
  a malformed response), logged here; start-node! decides what a nil
  result means for the whole node's startup (see there — it rejects the
  overall start-node! Promise, since a node explicitly configured with
  :turn-relay that can't actually establish one has failed to start as
  configured, not silently degraded to a working-but-unrelayed node)."
  [node-handle-atom sock {:keys [server-host server-port shared-secret peer-address]}]
  (let [e164 (:e164 (deref node-handle-atom))
        now-s (quot (js/Date.now) 1000)
        {:keys [username credential]} (cred/mint-credential shared-secret e164 600 now-s)
        allocate-req (tr/build-allocate-request username credential (rand-txid))]
    (log! node-handle-atom "udp: TURN Allocate -> " server-host ":" server-port)
    (p/let [alloc-result (send-and-wait-once! sock allocate-req server-host server-port turn-handshake-timeout-ms)]
      (if-not alloc-result
        (do (log! node-handle-atom "udp: TURN Allocate timed out, no response from "
                  server-host ":" server-port)
            nil)
        (let [[alloc-raw _] alloc-result
              parsed (tr/parse-stun-message alloc-raw)
              header (:header parsed)
              relayed (tr/allocate-response-relayed-address (:attrs parsed))]
          (if-not (and header (= (:typ header) stun/allocate-response) relayed)
            (do (log! node-handle-atom "udp: TURN Allocate rejected or malformed response (type="
                      (:typ header) ", want " stun/allocate-response ")")
                nil)
            (do
              (log! node-handle-atom "udp: TURN Allocate succeeded, relayed-address="
                    (tr/ip-vec->str (:ip relayed)) ":" (:port relayed))
              (let [peer-ip (tr/ip-str->vec (:address peer-address))
                    peer-port (:port peer-address)
                    perm-req (tr/build-create-permission-request username credential peer-ip peer-port (rand-txid))]
                (log! node-handle-atom "udp: TURN CreatePermission for peer " (:address peer-address)
                      ":" peer-port)
                (p/let [perm-result (send-and-wait-once! sock perm-req server-host server-port
                                                          turn-handshake-timeout-ms)]
                  (if-not perm-result
                    (do (log! node-handle-atom "udp: TURN CreatePermission timed out") nil)
                    (let [[perm-raw _] perm-result
                          perm-parsed (tr/parse-stun-message perm-raw)
                          perm-header (:header perm-parsed)]
                      (if-not (and perm-header (= (:typ perm-header) stun/create-permission-response))
                        (do (log! node-handle-atom "udp: TURN CreatePermission rejected or malformed response (type="
                                  (:typ perm-header) ", want " stun/create-permission-response ")")
                            nil)
                        (do
                          (log! node-handle-atom "udp: TURN CreatePermission succeeded")
                          {:relayed-address relayed :username username :credential credential})))))))))))))

;; ---------------------------------------------------------------------------
;; Node lifecycle
;; ---------------------------------------------------------------------------

(defn start-node!
  "Start a DTN node listening for internet-overlay UDP datagrams.
  Returns a `Promise<node-handle-atom>` — a deliberate API difference from
  kotoba.dtn.transport.tcp/start-node! (which returns the atom
  synchronously): binding a UDP socket is inherently asynchronous, and
  when `:turn-relay` is configured this ALSO has to complete a real
  multi-round-trip Allocate+CreatePermission handshake before the node is
  actually ready to send/receive, so there is no honest synchronous return
  here. Callers `p/let`/`.then` it — the exact same pattern this repo's
  E2E demos already use for kotoba.turn.listener/start-listener! (also
  Promise-returning, for the identical 'binding a socket is async' reason).

  opts: {:e164 <this node's own E.164 number>
         :port <UDP port to bind>
         :peers {e164 {:host \"...\" :port N} ...}   ; known peers, optional
         :store-path <path>                          ; optional, see kotoba.dtn.transport.tcp
         :peer-secrets {e164 secret-string ...}       ; optional, see kotoba.dtn.transport.tcp
         :routes [{:dtn/destination eid :dtn/next-hop eid} ...]   ; optional, see kotoba.dtn.transport.tcp
         :replay-state-path <path>                    ; optional, see kotoba.dtn.transport.tcp
         :turn-relay {:server-host \"...\" :server-port N
                       :shared-secret \"...\"
                       :peer-address {:address \"...\" :port N}}}  ; optional, NEW, see below

  :store-path / :peer-secrets / :routes / :replay-state-path have IDENTICAL
  semantics to kotoba.dtn.transport.tcp's own options of the same name —
  see that namespace's docstring; not re-derived here.

  :turn-relay (optional, NEW — see this namespace's docstring for the full,
  honest scope disclosure) — when present, THIS node is treated as NAT'd:
  it is not directly reachable at its own :port by any peer, and instead
  relays ALL of its outbound bundle sends through the configured TURN
  server, wrapped as STUN Send indications (RFC 8656 §10.3), addressed to
  :peer-address (the ONE peer this node has established a TURN permission
  for — see CreatePermission, RFC 8656 §9). Its inbound bundles likewise
  only ever arrive via the TURN server, as STUN Data indications (§10.4),
  unwrapped by this node's own socket handler. When omitted (the common,
  non-NAT'd case — get this path fully correct first, per this namespace's
  own build order), behavior is IDENTICAL to a plain UDP transport with no
  TURN awareness at all: datagrams are sent/received directly to/from
  peers' configured host:port, exactly like kotoba.dtn.transport.tcp's
  bytes-on-the-wire but over UDP instead of TCP.

  When :turn-relay is configured, start-node! performs a REAL Allocate +
  CreatePermission against the configured server BEFORE the returned
  Promise resolves (see turn-allocate-and-permit!) — if that handshake
  fails for any reason (server unreachable, wrong :shared-secret,
  malformed response), the returned Promise REJECTS (this node explicitly
  asked to be NAT-traversal-capable and could not become so; that is a
  startup failure, not a silent degrade to unrelayed behavior).

  Returns (once resolved) an atom (the 'node handle') holding:
    {:e164 e164 :port port :peers peers
     :store-path store-path :peer-secrets {...} :routes [...]
     :replay-state-path replay-state-path :replay-high-water-marks {...}
     :next-sequence-number <int> :store [] :inbox [] :seen-bundle-ids #{}
     :socket <node:dgram socket>
     :turn-relay <the :turn-relay opts given, or nil>
     :turn-relay-state {:relayed-address {:ip [..] :port n} ...}  ; nil
       unless :turn-relay was configured and the handshake succeeded"
  [{:keys [e164 port peers store-path peer-secrets routes replay-state-path turn-relay]}]
  (let [node-handle-atom (atom {:e164 e164 :port port :peers (or peers {})
                                 :store-path store-path
                                 :peer-secrets (or peer-secrets {})
                                 :routes (or routes [])
                                 :replay-state-path replay-state-path
                                 :replay-high-water-marks (if replay-state-path
                                                             (auth/load-high-water-marks replay-state-path)
                                                             {})
                                 :next-sequence-number (js/Date.now)
                                 :store (if store-path (store/load-store store-path) [])
                                 :inbox []
                                 :seen-bundle-ids #{}
                                 :socket nil
                                 :turn-relay turn-relay
                                 :turn-relay-state nil})]
    (js/Promise.
     (fn [resolve reject]
       (let [socket (dgram/createSocket "udp4")]
         (.on socket "error" (fn [e] (log! node-handle-atom "udp: socket error " (.-message e))))
         (.bind socket port
                (fn []
                  (swap! node-handle-atom assoc :socket socket)
                  (log! node-handle-atom "udp: listening on port " port)
                  (if turn-relay
                    (-> (turn-allocate-and-permit! node-handle-atom socket turn-relay)
                        (.then (fn [result]
                                 (if-not result
                                   (reject (ex-info
                                            (str "kotoba.dtn.transport.udp/start-node!: TURN Allocate/"
                                                 "CreatePermission failed for " e164)
                                            {:e164 e164 :turn-relay turn-relay}))
                                   (do
                                     (swap! node-handle-atom assoc :turn-relay-state result)
                                     (.on socket "message"
                                          (fn [msg _rinfo] (handle-relayed-datagram! node-handle-atom msg)))
                                     (resolve node-handle-atom)))))
                        (.catch reject))
                    (do
                      (.on socket "message"
                           (fn [msg _rinfo] (handle-direct-datagram! node-handle-atom msg)))
                      (resolve node-handle-atom))))))))))

(defn stop-node!
  "Close node-handle-atom's UDP socket. Returns a Promise resolved once the
  socket has actually closed (so callers can safely re-start-node! on the
  same port right after) — mirrors
  kotoba.dtn.transport.tcp/stop-node!'s own contract. No automatic Refresh
  or Allocate-teardown is sent to a configured :turn-relay server before
  closing (see namespace docstring — no permission-refresh scheduling
  means no permission-TEARDOWN messaging either, in this scoped version);
  the allocation simply expires server-side on its own 600s timer."
  [node-handle-atom]
  (js/Promise.
   (fn [resolve _reject]
     (let [{:keys [socket]} (deref node-handle-atom)]
       (if socket
         (.close socket (fn [] (swap! node-handle-atom assoc :socket nil) (resolve true)))
         (resolve true))))))

;; ---------------------------------------------------------------------------
;; Links derived from configured peers — identical to
;; kotoba.dtn.transport.tcp/links-for.
;; ---------------------------------------------------------------------------

(defn links-for
  [node-handle]
  (into []
        (map (fn [[peer-e164 _peer]]
               (link/link (str "udp:" peer-e164) peer-e164 :internet-overlay :reachable? true)))
        (:peers node-handle)))

;; ---------------------------------------------------------------------------
;; Outbound send
;; ---------------------------------------------------------------------------

(defn attempt-forward!
  "Try to actually deliver bundle to dest-link's peer right now.

  When this node has NO :turn-relay configured: sends the framed bundle
  (encode-bundle) as one raw UDP datagram directly to the peer's
  configured host:port — the straightforward, non-NAT'd case.

  When this node HAS :turn-relay configured: ignores dest-link/the
  peer-map's own host:port entirely (this node's only path to ANY peer is
  through the relay — see namespace docstring) and instead wraps the
  bundle in a real STUN Send indication (RFC 8656 §10.3) addressed to
  :turn-relay's configured :peer-address, sent to the TURN server. This is
  only correct because a permission was already established for exactly
  that peer address at start-node! time (turn-allocate-and-permit!) — a
  Send indication to any OTHER address would simply be silently dropped
  by the TURN server (no permission), which is why this scoped version is
  single-peer-per-node (see namespace docstring).

  Either way, when this node has a :peer-secrets entry for the destination
  peer, the bundle is kotoba.dtn.auth/sign-bundle'd BEFORE sending —
  identical to kotoba.dtn.transport.tcp's own :peer-secrets behavior.

  Returns a Promise resolving true on a successful `.send` (no delivery
  ACK exists at the UDP layer — 'true' means 'handed to the OS/relay
  without an immediate send error', not 'the peer definitely received
  it' — same honest caveat any UDP-based transport has, unlike TCP's
  connection-oriented write success), or false on any send error or when
  no peer is configured for this destination at all."
  [node-handle-atom bundle dest-link]
  (js/Promise.
   (fn [resolve _reject]
     (let [{:keys [peers peer-secrets turn-relay socket]} (deref node-handle-atom)
           peer-e164 (dtn/eid->e164 (:dtn/neighbor dest-link))
           peer (get peers peer-e164)]
       (if-not peer
         (resolve false)
         (let [secret (get peer-secrets peer-e164)
               out-bundle (if secret (auth/sign-bundle bundle secret) bundle)]
           (if turn-relay
             (let [{:keys [server-host server-port peer-address]} turn-relay
                   peer-ip (tr/ip-str->vec (:address peer-address))
                   peer-port (:port peer-address)
                   payload (b/utf8-encode (pr-str out-bundle))
                   ind (tr/build-send-indication peer-ip peer-port payload (rand-txid))]
               (.send socket (vec->buf ind) server-port server-host
                      (fn [err] (resolve (not err)))))
             (.send socket (encode-bundle out-bundle) (:port peer) (:host peer)
                    (fn [err] (resolve (not err)))))))))))

(defn- store-bundle!
  [node-handle-atom bundle]
  (swap! node-handle-atom update :store conj bundle)
  (when-let [path (:store-path (deref node-handle-atom))]
    (store/append-bundle! path bundle)))

(defn route-and-send!
  "Identical decision structure to
  kotoba.dtn.transport.tcp/route-and-send! — run the pure
  kotoba.dtn.router/route-decision, then attempt-forward! (this
  namespace's own, above) or store-bundle! on failure/no-route. See
  kotoba.dtn.transport.tcp's docstring for the full rationale."
  [node-handle-atom bundle]
  (let [{:keys [routes] :as node-handle} (deref node-handle-atom)
        decision (router/route-decision bundle (links-for node-handle)
                                         :routes routes
                                         :hop-count (or (:dtn/hop-count bundle) 0))]
    (case (:dtn/action decision)
      (:forward :relay)
      (p/let [delivered? (attempt-forward! node-handle-atom bundle (:dtn/via decision))]
        (when-not delivered?
          (store-bundle! node-handle-atom bundle))
        delivered?)

      :store
      (do (store-bundle! node-handle-atom bundle)
          (p/resolved false)))))

(defn- next-sequence-number!
  [node-handle-atom]
  (:next-sequence-number (swap! node-handle-atom update :next-sequence-number inc)))

(defn send-message!
  "Identical contract to kotoba.dtn.transport.tcp/send-message! — wraps an
  :rcs/*-shaped message as a bundle from this node to dest-e164 and
  route-and-send!s it, stamping a real wall-clock :dtn/creation-timestamp
  and a fresh :dtn/sequence-number."
  [node-handle-atom dest-e164 rcs-chat-message]
  (let [source-e164 (:e164 (deref node-handle-atom))
        seq-num (next-sequence-number! node-handle-atom)
        bundle (-> (gateway/rcs-shaped->bundle rcs-chat-message dest-e164 source-e164)
                   (assoc :dtn/creation-timestamp (js/Date.now))
                   (assoc :dtn/sequence-number seq-num))]
    (route-and-send! node-handle-atom bundle)))

(defn retry-store!
  "Identical contract to kotoba.dtn.transport.tcp/retry-store! — re-attempt
  route-and-send! for everything in :store, after dropping anything past
  its RFC 9171 lifetime."
  [node-handle-atom]
  (let [now-ms (js/Date.now)
        {:keys [kept expired]} (router/expire-store (:store (deref node-handle-atom)) now-ms)
        store-path (:store-path (deref node-handle-atom))]
    (doseq [b expired]
      (log! node-handle-atom "udp: retry-store! dropping expired bundle " (:dtn/bundle-id b)))
    (log! node-handle-atom "udp: retry-store! attempting " (count kept) " stored bundle(s)")
    (swap! node-handle-atom assoc :store [])
    (p/let [results (p/all (map (fn [b] (route-and-send! node-handle-atom b)) kept))]
      (when store-path
        (store/rewrite-store! store-path (:store (deref node-handle-atom))))
      results)))
