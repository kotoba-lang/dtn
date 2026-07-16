(ns kotoba.dtn.transport.tcp
  "A real, working transport for the internet-overlay link kind that
  kotoba.dtn.link already models — plain TCP, Node.js core `node:net`
  only (no npm deps). This is the first namespace in kotoba-dtn that
  actually moves bytes between OS processes; everything else in this
  library (kotoba.dtn, kotoba.dtn.link, kotoba.dtn.router,
  kotoba.dtn.gateway) is deliberately pure/I/O-free data-model code, and
  stays that way — this namespace is a consumer of those namespaces, not
  a replacement for their purity.

  .cljs, NOT .cljc: it requires real `node:net` socket I/O, so it only
  runs under a Node.js-hosted ClojureScript runtime (nbb in this repo).
  The JVM `clojure -M:test` suite does not — and cannot — load `.cljs`
  files, so this namespace can never regress the existing 84-assertion
  pure test suite.

  SCOPE — direct internet-overlay transport only: a peer's IP:host and
  port must already be known (passed in via `:peers` to `start-node!`).
  There is no peer discovery, no NAT traversal, and no gossip/relay
  routing here. Real multi-hop composition — discovering peers and
  routing around NATs via `kotoba-lang/net` (gossip) and
  `kotoba-lang/turn` (relay) — remains explicitly future work, same as
  the deferral this namespace closes only the 'does any transport
  actually move bytes' gap, not the 'production-grade P2P overlay' gap.

  Mesh-radio and satellite `:dtn/transport-kind`s are NOT implemented
  here — no such hardware exists in this dev environment to drive. Only
  `:internet-overlay` gets a real transport in this namespace;
  `kotoba.dtn.router/route-decision` itself stays transport-agnostic and
  will happily rank a (caller-supplied) mesh-radio or satellite link
  too, this namespace just doesn't provide one.

  Wire framing (deliberately the simplest thing that works, no external
  framing library): each message on the socket is a 4-byte big-endian
  length prefix followed by that many bytes of UTF-8 `pr-str`'d EDN — the
  bundle map as produced by `kotoba.dtn/bundle`. Decoded on the reading
  side with `clojure.edn/read-string` (verified empirically to work
  under nbb; `cljs.reader/read-string` also works but `clojure.edn` is
  used here since it round-trips this repo's `:dtn/*` / `:rcs/*`
  namespaced-keyword maps identically and is the more portable idiom)."
  (:require ["node:net" :as net]
            [clojure.edn :as edn]
            [promesa.core :as p]
            [kotoba.dtn :as dtn]
            [kotoba.dtn.link :as link]
            [kotoba.dtn.router :as router]
            [kotoba.dtn.gateway :as gateway]))

;; ---------------------------------------------------------------------------
;; Wire framing — 4-byte big-endian length prefix + UTF-8 pr-str EDN payload
;; ---------------------------------------------------------------------------

(defn- encode-frame
  "bundle -> a single Buffer: [4-byte BE length][UTF-8 pr-str EDN payload]."
  [bundle]
  (let [payload (js/Buffer.from (pr-str bundle) "utf8")
        len-buf (js/Buffer.alloc 4)]
    (.writeUInt32BE len-buf (.-length payload) 0)
    (js/Buffer.concat #js [len-buf payload])))

(defn- frame-write
  "Write bundle, framed, to socket. callback (may be nil) is Node's usual
  socket.write callback: called with an Error on failure, or no args (nil)
  on success once the data is flushed to the OS."
  [socket bundle callback]
  (.write socket (encode-frame bundle) callback))

(defn- make-frame-reader
  "Returns a function suitable as a socket 'data' listener: accumulates
  chunks in a private buffer, slices out every complete length-prefixed
  frame as chunks arrive (frames may span multiple TCP packets, or a
  single packet may contain several frames — both are handled), EDN-decodes
  each payload, and calls (on-bundle decoded-bundle) once per decoded
  frame, in order."
  [on-bundle]
  (let [buf-atom (atom (js/Buffer.alloc 0))]
    (fn [chunk]
      (swap! buf-atom (fn [b] (js/Buffer.concat #js [b chunk])))
      (loop []
        (let [buf (deref buf-atom)]
          (when (>= (.-length buf) 4)
            (let [frame-len (.readUInt32BE buf 0)]
              (when (>= (.-length buf) (+ 4 frame-len))
                (let [payload-str (.toString (.subarray buf 4 (+ 4 frame-len)) "utf8")
                      rest-buf    (.subarray buf (+ 4 frame-len))]
                  (reset! buf-atom rest-buf)
                  (on-bundle (edn/read-string payload-str))
                  (recur))))))))))

;; ---------------------------------------------------------------------------
;; Inbound handling
;; ---------------------------------------------------------------------------

(defn- log!
  [node-handle-atom & parts]
  (println (str "[" (:e164 (deref node-handle-atom)) "] " (apply str parts))))

(defn- handle-inbound-bundle!
  "Called once per decoded bundle arriving on an accepted server
  connection. Drops expired bundles outright (RFC 9171 retention
  constraint elapsed). Otherwise tries to shape the payload as an
  RCS-shaped or SMS-shaped message (kotoba.dtn.gateway) purely for
  demo/operator visibility, and appends {:message <decoded-or-nil>
  :bundle bundle} onto the node's :inbox so an operator (or this repo's
  E2E demo) can observe what actually arrived."
  [node-handle-atom bundle]
  (let [now-ms   (js/Date.now)
        expired? (dtn/expired? bundle now-ms)]
    (cond
      expired?
      (log! node-handle-atom "tcp: dropping expired inbound bundle " (:dtn/bundle-id bundle))

      :else
      (let [decoded (or (gateway/bundle->rcs-shaped bundle)
                         (gateway/bundle->sms bundle))]
        (swap! node-handle-atom update :inbox conj {:message decoded :bundle bundle})
        (if decoded
          (log! node-handle-atom "DTN-RECV from=" (:dtn/source bundle)
                " body=" (or (:rcs/body decoded) (:phone/body decoded)))
          (log! node-handle-atom "tcp: received bundle with unrecognized payload shape "
                (:dtn/bundle-id bundle)))))))

;; ---------------------------------------------------------------------------
;; Node lifecycle
;; ---------------------------------------------------------------------------

(defn start-node!
  "Start a DTN node listening for internet-overlay TCP connections.

  opts: {:e164 <this node's own E.164 number>
         :port <TCP port to bind>
         :peers {e164 {:host \"...\" :port N} ...}}  ; known peers, optional

  Returns an atom (the 'node handle') holding:
    {:e164 e164 :port port :peers peers
     :store []      ; bundles not yet delivered (store-and-forward)
     :inbox []      ; {:message ... :bundle ...} this node has received
     :server <net/Server>
     :sockets {}}   ; e164 -> open outbound net.Socket, lazily connected

  (plus an internal :accepted bookkeeping set used by stop-node! to close
  inbound connections promptly — not part of the documented contract)."
  [{:keys [e164 port peers]}]
  (let [node-handle-atom (atom {:e164 e164 :port port :peers (or peers {})
                                 :store [] :inbox []
                                 :server nil :sockets {} :accepted #{}})
        server (net/createServer
                (fn [socket]
                  (swap! node-handle-atom update :accepted conj socket)
                  (.on socket "data" (make-frame-reader
                                       (fn [bundle] (handle-inbound-bundle! node-handle-atom bundle))))
                  (.on socket "error" (fn [_e] nil)) ;; a peer dropping mid-write is routine, not fatal
                  (.on socket "close" (fn [] (swap! node-handle-atom update :accepted disj socket)))))]
    (.on server "error" (fn [e] (log! node-handle-atom "tcp: server error " (.-message e))))
    (.listen server port
             (fn [] (log! node-handle-atom "tcp: listening on port " port)))
    (swap! node-handle-atom assoc :server server)
    node-handle-atom))

(defn stop-node!
  "Close node-handle-atom's server (stop accepting new connections, and
  proactively close any already-accepted inbound sockets so the server's
  'close' event fires promptly rather than waiting on remote peers) and
  destroy any cached outbound sockets. Returns a Promise resolved once the
  server has actually closed (so callers can safely re-`start-node!` on
  the same port right after)."
  [node-handle-atom]
  (js/Promise.
   (fn [resolve _reject]
     (let [{:keys [server sockets accepted]} (deref node-handle-atom)]
       (doseq [[_e164 sock] sockets] (.destroy sock))
       (doseq [sock accepted] (.destroy sock))
       (swap! node-handle-atom assoc :sockets {} :accepted #{})
       (if server
         (.close server (fn [_err]
                           (swap! node-handle-atom assoc :server nil)
                           (resolve true)))
         (resolve true))))))

;; ---------------------------------------------------------------------------
;; Links derived from configured peers
;; ---------------------------------------------------------------------------

(defn links-for
  "Derive kotoba.dtn.link/link records from a node handle's :peers, one
  per configured peer, :dtn/transport-kind :internet-overlay.

  Deliberately optimistic: every configured peer is marked
  :reachable? true. A probe-then-send approach has an inherent race
  (a peer can go down between the probe and the send) so this namespace
  doesn't bother probing separately — real reachability is decided by
  whether attempt-forward! actually succeeds at send time, and a failed
  send falls back to store (see route-and-send!)."
  [node-handle]
  (into []
        (map (fn [[peer-e164 _peer]]
               (link/link (str "tcp:" peer-e164) peer-e164 :internet-overlay :reachable? true)))
        (:peers node-handle)))

;; ---------------------------------------------------------------------------
;; Outbound send
;; ---------------------------------------------------------------------------

(defn- get-or-create-socket!
  "Reuse node-handle-atom's cached outbound socket to peer-e164 if one is
  already open; otherwise open a new one and cache it. The socket is
  uncached again (on 'error' or 'close') so a dead connection is never
  reused — the next send attempt will open a fresh one."
  [node-handle-atom peer-e164 host port]
  (or (get (:sockets (deref node-handle-atom)) peer-e164)
      (let [sock (net/createConnection #js {:host host :port port})]
        (.on sock "error" (fn [_e] (swap! node-handle-atom update :sockets dissoc peer-e164)))
        (.on sock "close" (fn [] (swap! node-handle-atom update :sockets dissoc peer-e164)))
        (swap! node-handle-atom update :sockets assoc peer-e164 sock)
        sock)))

(defn attempt-forward!
  "Try to actually deliver bundle to dest-link's peer right now: get or
  open a net.Socket to that peer's host:port and write the framed bundle.

  Returns a Promise resolving true on a successful write, or false on any
  connection/write error — a peer being down is an expected, routine
  condition here, never thrown as an exception."
  [node-handle-atom bundle dest-link]
  (js/Promise.
   (fn [resolve _reject]
     (let [peer-e164 (dtn/eid->e164 (:dtn/neighbor dest-link))
           peer      (get (:peers (deref node-handle-atom)) peer-e164)]
       (if-not peer
         (resolve false)
         (let [sock (get-or-create-socket! node-handle-atom peer-e164 (:host peer) (:port peer))]
           (frame-write sock bundle (fn [err] (resolve (not err))))))))))

(defn route-and-send!
  "Run the pure kotoba.dtn.router/route-decision against this node's
  current links (derived from :peers), then actually act on it:
    :forward -> attempt-forward!; on failure (the real-world case the
                pure decision can't know about — a configured link
                doesn't mean the peer process is actually up right now)
                fall back to appending bundle onto :store.
    :store   -> append bundle onto :store directly.
  Always returns a Promise<boolean> — true iff the bundle was actually
  delivered over the wire just now."
  [node-handle-atom bundle]
  (let [decision (router/route-decision bundle (links-for (deref node-handle-atom)))]
    (case (:dtn/action decision)
      :forward
      (p/let [delivered? (attempt-forward! node-handle-atom bundle (:dtn/via decision))]
        (when-not delivered?
          (swap! node-handle-atom update :store conj bundle))
        delivered?)

      :store
      (do (swap! node-handle-atom update :store conj bundle)
          (p/resolved false)))))

(defn send-message!
  "Wrap rcs-chat-message (an :rcs/*-shaped map, see kotoba.dtn.gateway) as
  a DTN bundle from this node to dest-e164 via
  kotoba.dtn.gateway/rcs-shaped->bundle, then route-and-send! it.

  kotoba.dtn/bundle's :creation-ts intentionally defaults to 0 (per its
  own docstring: 'the caller's own clock; this namespace does not read
  wall-clock time') because the pure data-model layer has no clock of its
  own — gateway/rcs-shaped->bundle inherits that default. This transport
  DOES have a real clock, and the receiving side's expired? check
  (handle-inbound-bundle!, above) compares against js/Date.now — so this
  is the correct seam to stamp a real wall-clock :dtn/creation-timestamp
  onto the bundle, rather than leaving every bundle look permanently
  1970-epoch-old (and therefore immediately :dtn/expired? at any real
  receiver) by construction."
  [node-handle-atom dest-e164 rcs-chat-message]
  (let [source-e164 (:e164 (deref node-handle-atom))
        bundle (-> (gateway/rcs-shaped->bundle rcs-chat-message dest-e164 source-e164)
                   (assoc :dtn/creation-timestamp (js/Date.now)))]
    (route-and-send! node-handle-atom bundle)))

(defn retry-store!
  "Store-and-forward retry pass: first expire anything past its RFC 9171
  lifetime (logging what got dropped), then re-attempt route-and-send! for
  everything still kept. :store is cleared up front and rebuilt purely
  from this pass's failures, so a bundle is never duplicated across
  retries. Idempotent-ish by construction: re-running this against an
  unreachable peer just re-stores the same bundles again, which is fine
  for this repo's demo/CLI use — no additional dedup bookkeeping here.
  Returns a Promise resolved once every retry attempt has settled."
  [node-handle-atom]
  (let [now-ms (js/Date.now)
        {:keys [kept expired]} (router/expire-store (:store (deref node-handle-atom)) now-ms)]
    (doseq [b expired]
      (log! node-handle-atom "tcp: retry-store! dropping expired bundle " (:dtn/bundle-id b)))
    (log! node-handle-atom "tcp: retry-store! attempting " (count kept) " stored bundle(s)")
    (swap! node-handle-atom assoc :store [])
    (p/all (map (fn [b] (route-and-send! node-handle-atom b)) kept))))
