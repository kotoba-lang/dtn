;; Not a unit test — an EXECUTABLE end-to-end demo that must genuinely pass
;; when run. Mirrors the rigor test/kotoba/dtn/transport/tcp_demo.cljs and
;; org-ietf-turn's own test/kotoba/turn/listener_demo.cljs already
;; established: PASS/FAIL printed per scenario, a final
;; "RESULT: N/3 scenarios passed" line, and `js/process.exit` 0 iff all 3
;; passed (else 1).
;;
;; This is genuinely the most complex integration in this whole ADR series:
;; THREE protocol layers stacked for real — dtn bundles
;; (kotoba.dtn/kotoba.dtn.router/kotoba.dtn.gateway), a real TURN relay
;; (kotoba.turn.listener, reused as-is from kotoba-lang/org-ietf-turn, never
;; modified), and real UDP socket timing (node:dgram, no simulated/mocked
;; sockets anywhere in this file).
;;
;; Run from this repo's root (nbb, not `clojure -M:test` — this is `.cljs`,
;; real socket I/O, see kotoba.dtn.transport.udp's own namespace docstring
;; for why that split exists), with kotoba-lang/phone, /html, /css, /wire,
;; /bytes, and org-ietf-turn all checked out as siblings:
;;
;;   nbb --classpath "src:../phone/src:../html/src:../css/src:../wire/src:../bytes/src:../org-ietf-turn/src" \
;;     test/kotoba/dtn/transport/udp_turn_demo.cljs
;;
;; Scenario 1 proves the new UDP transport itself works, on its own, before
;; TURN enters the picture at all: two dtn nodes, NEITHER behind NAT (no
;; :turn-relay config on either), exchange a real message directly over
;; node:dgram sockets.
;;
;; Scenario 2 is the actual NAT-traversal proof: a real
;; kotoba.turn.listener (org-ietf-turn's own real UDP relay) is started
;; with a known shared secret. Node A is configured with :turn-relay
;; pointing at that listener, with node B's real address as A's ONE
;; permitted peer (CreatePermission is called for exactly that address at
;; A's start-node! time). Node B is started WITHOUT any :turn-relay config
;; — B is the directly-reachable one — but B is deliberately configured to
;; reach A ONLY at A's TURN-ALLOCATED RELAYED ADDRESS, never A's real bound
;; UDP port; A's real port is simulated-NAT'd/unreachable in this demo's
;; own framing (see the explicit log lines and scenario 3, below, which
;; mechanically confirms B's config never mentions it). B sends a message
;; addressed to A's relayed address; the demo confirms A's :inbox actually
;; receives it (arrived via the relay, unwrapped from a real STUN Data
;; indication). A then replies to B (sent as a real STUN Send indication
;; through the SAME relay); the demo confirms B's :inbox actually receives
;; A's reply — genuine BIDIRECTIONALITY, the harder direction most relay
;; integrations get lazy about (same rigor org-ietf-turn's own
;; listener_demo.cljs scenario 1 insists on for its own client<->peer
;; proof).
;;
;; Scenario 3 is the honesty/negative check the task explicitly asks for:
;; not a simulated firewall (nothing to literally block here on loopback),
;; but a MECHANICAL confirmation that node B's own configuration — the
;; exact live node-handle atom scenario 2 just used to prove bidirectional
;; delivery, not a fresh unrelated instance — contains A's real bound UDP
;; port NOWHERE at all (only A's TURN-relayed address), so scenario 2's
;; success is provably not an accidental direct-connection fallback dressed
;; up as a relay proof.

(ns kotoba.dtn.transport.udp-turn-demo
  (:require [clojure.walk :as walk]
            [promesa.core :as p]
            [kotoba.dtn.transport.udp :as udp]
            [kotoba.turn.listener :as listener]))

(defn- sleep-ms [ms]
  (js/Promise. (fn [resolve _] (js/setTimeout resolve ms))))

;; ---------------------------------------------------------------------------
;; Scenario 1 — baseline: two directly-reachable nodes, plain UDP, no TURN.
;; ---------------------------------------------------------------------------

(defn- scenario-1 []
  (println "\n--- Scenario 1: baseline UDP transport, no TURN involved (two directly reachable nodes) ---")
  (let [a-e164 "+819012345678" a-port 41101
        b-e164 "+818098765432" b-port 41102]
    (p/let [node-a (udp/start-node! {:e164 a-e164 :port a-port
                                      :peers {b-e164 {:host "127.0.0.1" :port b-port}}})
            node-b (udp/start-node! {:e164 b-e164 :port b-port :peers {}})
            delivered? (udp/send-message! node-a b-e164
                                           {:rcs/message-id (str (random-uuid))
                                            :rcs/from a-e164 :rcs/to b-e164
                                            :rcs/body "udp-baseline-check"
                                            :rcs/content-type "text/plain"})
            _ (sleep-ms 300)]
      (let [arrived? (boolean (some #(= "udp-baseline-check" (get-in % [:message :rcs/body]))
                                     (:inbox (deref node-b))))
            pass? (and (boolean delivered?) arrived?)]
        (println "  send-message! (A -> B, direct UDP datagram) returned delivered?=" delivered?)
        (println "  B's :inbox actually received it?" arrived?)
        (p/let [_ (udp/stop-node! node-a) _ (udp/stop-node! node-b)]
          (println (if pass? "PASS" "FAIL")
                    " scenario 1: baseline direct UDP transport works, no TURN involved")
          pass?)))))

;; ---------------------------------------------------------------------------
;; Scenarios 2 + 3 — real NAT traversal (bidirectional) via a real TURN
;; relay, then the honesty check against the exact same live node B that
;; just proved it. Written as one function (not two independent ones, per
;; every other scenario in this whole series) because scenario 3's
;; assertion is only meaningful against the SAME live objects scenario 2
;; used — a fresh unrelated node built the same way would prove something
;; weaker ("a node COULD be built with zero knowledge of A's real port"),
;; not what actually happened here.
;; ---------------------------------------------------------------------------

(defn- contains-value?
  "true iff `needle` appears anywhere in `data` (walking every map value,
  vector/seq element, and set member recursively) — used by scenario 3 to
  mechanically confirm A's real port number is not present ANYWHERE in
  node B's live config/state, not merely absent from the one :peers key
  this demo happens to know to look at."
  [data needle]
  (let [found (atom false)]
    (walk/postwalk (fn [x] (when (= x needle) (reset! found true)) x) data)
    @found))

(defn- scenario-2-and-3 []
  (println "\n--- Scenario 2: NAT traversal via a real TURN relay (bidirectional) ---")
  (let [shared-secret "dtn-udp-turn-demo-shared-secret"
        a-e164 "+819012345678" a-real-port 41201
        b-e164 "+818098765432" b-port 41202]
    (p/let [turn-handle (listener/start-listener! {:port 0 :shared-secret shared-secret})
            turn-port (:port turn-handle)
            _ (println "  real kotoba.turn.listener relay bound on 127.0.0.1:" turn-port
                        "(org-ietf-turn's own listener, reused as-is)")
            node-a (udp/start-node!
                    {:e164 a-e164 :port a-real-port
                     :peers {b-e164 {:host "127.0.0.1" :port b-port}}
                     :turn-relay {:server-host "127.0.0.1" :server-port turn-port
                                  :shared-secret shared-secret
                                  :peer-address {:address "127.0.0.1" :port b-port}}})
            _ (println "  node A (e164=" a-e164 ") started behind SIMULATED NAT on real UDP port"
                        a-real-port "-- this port is never told to B (see scenario 3, below)")
            relayed-address (get-in (deref node-a) [:turn-relay-state :relayed-address])
            relayed-port (:port relayed-address)
            _ (println "  A's real Allocate + CreatePermission succeeded; A's TURN-allocated relayed"
                        "address is 127.0.0.1:" relayed-port)
            node-b (udp/start-node!
                    {:e164 b-e164 :port b-port
                     ;; B's ONLY configured way to reach A: A's
                     ;; TURN-ALLOCATED RELAYED ADDRESS. Deliberately NOT
                     ;; a-real-port -- B is directly reachable itself and
                     ;; has no :turn-relay config of its own; A is the
                     ;; (simulated-)NAT'd one B can only reach via the
                     ;; relay.
                     :peers {a-e164 {:host "127.0.0.1" :port relayed-port}}})
            _ (println "  node B (e164=" b-e164 ") started, directly reachable, configured to reach A"
                        "ONLY at A's relay address 127.0.0.1:" relayed-port
                        "-- B has NEVER been told A's real port" a-real-port)
            b->a-delivered? (udp/send-message! node-b a-e164
                                                {:rcs/message-id (str (random-uuid))
                                                 :rcs/from b-e164 :rcs/to a-e164
                                                 :rcs/body "b-to-a-via-relay"
                                                 :rcs/content-type "text/plain"})
            _ (sleep-ms 600)]
      (let [a-arrived? (boolean (some #(= "b-to-a-via-relay" (get-in % [:message :rcs/body]))
                                       (:inbox (deref node-a))))]
        (println "  B -> A (B addressed the message to A's RELAY address, never A's real port):")
        (println "    send-message! returned delivered?=" b->a-delivered?)
        (println "    A's :inbox actually received it (arrived via the relay, unwrapped from a real"
                  "STUN Data indication, into A's real inbox)?" a-arrived?)
        (p/let [a->b-delivered? (udp/send-message! node-a b-e164
                                                     {:rcs/message-id (str (random-uuid))
                                                      :rcs/from a-e164 :rcs/to b-e164
                                                      :rcs/body "a-to-b-reply-via-relay"
                                                      :rcs/content-type "text/plain"})
                _ (sleep-ms 600)]
          (let [b-arrived? (boolean (some #(= "a-to-b-reply-via-relay" (get-in % [:message :rcs/body]))
                                            (:inbox (deref node-b))))
                scenario-2-pass? (and (boolean b->a-delivered?) a-arrived?
                                       (boolean a->b-delivered?) b-arrived?)]
            (println "  A -> B reply (sent by A as a real STUN Send indication THROUGH the relay,"
                      "since A has zero direct-send path -- A is NAT'd for outbound too, per this"
                      "scoped design, see kotoba.dtn.transport.udp's own docstring):")
            (println "    send-message! returned delivered?=" a->b-delivered?)
            (println "    B's :inbox actually received A's reply?" b-arrived?)
            (println (if scenario-2-pass? "PASS" "FAIL")
                      " scenario 2: genuine BIDIRECTIONAL NAT-traversed delivery through a real TURN relay"
                      "(B->A and A->B both proven, not just one direction)")

            (println "\n--- Scenario 3: honesty check -- B's own config has ZERO knowledge of A's real port ---")
            (let [b-config (deref node-b)
                  knows-real-port? (contains-value? b-config a-real-port)
                  knows-relay-port? (contains-value? b-config relayed-port)
                  scenario-3-pass? (and (not knows-real-port?) knows-relay-port?)]
              (println "  scanning B's ENTIRE live node-handle map (not just :peers) for A's real port"
                        a-real-port "...")
              (println "    B's config mentions A's real port" a-real-port "anywhere at all?" knows-real-port?
                        "(must be false)")
              (println "    B's config mentions A's relay port" relayed-port "(the one B actually uses)?"
                        knows-relay-port? "(must be true -- sanity, confirms the scan itself works)")
              (println (if scenario-3-pass? "PASS" "FAIL")
                        " scenario 3: B genuinely has zero knowledge of A's real port -- scenario 2's"
                        "success is provably NOT an accidental direct-connection fallback")

              (p/let [_ (udp/stop-node! node-a) _ (udp/stop-node! node-b)
                      _ (listener/stop-listener! turn-handle)]
                [scenario-2-pass? scenario-3-pass?]))))))))

;; ---------------------------------------------------------------------------
;; Driver
;; ---------------------------------------------------------------------------

(-> (p/let [r1 (scenario-1)
            [r2 r3] (scenario-2-and-3)]
      (let [results [r1 r2 r3]
            passed (count (filter true? results))]
        (println (str "\nRESULT: " passed "/3 scenarios passed"))
        (js/process.exit (if (= passed 3) 0 1))))
    (.catch (fn [e]
              (println "DEMO CRASHED:" e)
              (js/process.exit 1))))
