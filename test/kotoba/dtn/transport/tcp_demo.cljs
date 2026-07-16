;; Not a unit test — an EXECUTABLE end-to-end demo that must genuinely
;; pass when run. It proves kotoba.dtn.transport.tcp actually moves bytes
;; between real OS processes over a real socket, and that the
;; store-and-forward resilience property the whole design exists for is
;; real. Run from this repo's root:
;;
;;   nbb --classpath "src:../phone/src:../html/src:../css/src" \
;;     test/kotoba/dtn/transport/tcp_demo.cljs
;;
;; (relative --classpath entries mean this must run with cwd at this
;; repo's root, with kotoba-lang/phone, /html, /css checked out as
;; siblings — same layout deps.edn already assumes. Scenario 1 also
;; `spawn`s a second real `nbb` process, found via $PATH, running
;; bin/dtn_node.cljs with the same --classpath.)
;;
;; Prints PASS/FAIL per scenario, a final "RESULT: N/3 scenarios passed"
;; line, and exits 0 iff all 3 passed (else 1).

(ns kotoba.dtn.transport.tcp-demo
  (:require ["node:child_process" :as cp]
            ["node:net" :as net]
            [clojure.string :as str]
            [promesa.core :as p]
            [kotoba.dtn :as dtn]
            [kotoba.dtn.link :as link]
            [kotoba.dtn.router :as router]
            [kotoba.dtn.transport.tcp :as tcp]))

(def classpath "src:../phone/src:../html/src:../css/src")

(defn- sleep-ms [ms]
  (js/Promise. (fn [resolve _] (js/setTimeout resolve ms))))

(defn- try-connect-once [host port]
  (js/Promise.
   (fn [resolve _]
     (let [sock (net/createConnection #js {:host host :port port})]
       (.on sock "connect" (fn [] (.destroy sock) (resolve true)))
       (.on sock "error" (fn [_e] (.destroy sock) (resolve false)))))))

(defn- wait-for-port
  "Poll host:port with real connection attempts (not a blind sleep) until
  one succeeds or attempts run out. Returns a Promise<boolean>."
  [host port attempts interval-ms]
  (p/let [ok? (try-connect-once host port)]
    (cond
      ok? true
      (<= attempts 0) false
      :else (p/let [_ (sleep-ms interval-ms)]
              (wait-for-port host port (dec attempts) interval-ms)))))

;; ---------------------------------------------------------------------------
;; Scenario 1 — real cross-process delivery
;; ---------------------------------------------------------------------------

(defn- scenario-1 []
  (println "\n--- Scenario 1: real cross-process delivery (spawned child `listen` process) ---")
  (let [b-e164 "+818098765432" b-port 5202
        a-e164 "+819012345678" a-port 5201
        out-chunks (atom [])
        err-chunks (atom [])
        child (cp/spawn "nbb" #js ["--classpath" classpath "bin/dtn_node.cljs" "listen"
                                    "--e164" b-e164 "--port" (str b-port)]
                         #js {:cwd (js/process.cwd)})]
    (.on (.-stdout child) "data" (fn [chunk] (swap! out-chunks conj (str chunk))))
    (.on (.-stderr child) "data" (fn [chunk] (swap! err-chunks conj (str chunk))))
    (p/let [up? (wait-for-port "127.0.0.1" b-port 50 100)]
      (if-not up?
        (do (println "FAIL scenario 1: child `listen` process never bound port" b-port)
            (.kill child)
            false)
        (p/let [node-a (tcp/start-node! {:e164 a-e164 :port a-port
                                          :peers {b-e164 {:host "127.0.0.1" :port b-port}}})
                delivered? (tcp/send-message! node-a b-e164
                                               {:rcs/message-id (str (random-uuid))
                                                :rcs/from a-e164 :rcs/to b-e164
                                                :rcs/body "hello-scenario-1"
                                                :rcs/content-type "text/plain"})
                _ (sleep-ms 400) ;; let the child's own event loop print + update :inbox
                _ (tcp/stop-node! node-a)]
          (.kill child)
          (let [child-stdout (str/join "" @out-chunks)
                arrived? (and (str/includes? child-stdout "DTN-RECV")
                               (str/includes? child-stdout "hello-scenario-1"))
                pass? (and delivered? arrived?)]
            (println "  send-message! returned delivered?=" delivered?)
            (println "  child (node B, separate OS process) stdout:")
            (doseq [line (str/split-lines child-stdout)]
              (when (seq (str/trim line)) (println "   >" line)))
            (println (if pass? "PASS" "FAIL")
                      " scenario 1: cross-process TCP delivery, verified via child process's own stdout+inbox")
            pass?))))))

;; ---------------------------------------------------------------------------
;; Scenario 2 — store-and-forward across a disconnect (in-process)
;; ---------------------------------------------------------------------------

(defn- scenario-2 []
  (println "\n--- Scenario 2: store-and-forward across a disconnect (in-process) ---")
  (let [a-e164 "+819012345678" a-port 5301
        b-e164 "+818098765432" b-port 5302]
    (p/let [node-a (tcp/start-node! {:e164 a-e164 :port a-port
                                      :peers {b-e164 {:host "127.0.0.1" :port b-port}}})
            node-b (tcp/start-node! {:e164 b-e164 :port b-port :peers {}})
            _ (tcp/stop-node! node-b) ;; simulate B going offline before A ever sends
            delivered-1? (tcp/send-message! node-a b-e164
                                             {:rcs/message-id (str (random-uuid))
                                              :rcs/from a-e164 :rcs/to b-e164
                                              :rcs/body "resilience-check"
                                              :rcs/content-type "text/plain"})]
      (let [stored-while-down? (and (not delivered-1?) (= 1 (count (:store (deref node-a)))))]
        (println "  while B is down: delivered?=" delivered-1?
                  " A's :store size=" (count (:store (deref node-a))))
        (p/let [node-b2 (tcp/start-node! {:e164 b-e164 :port b-port :peers {}}) ;; B restarts, same port
                _ (tcp/retry-store! node-a)
                _ (sleep-ms 300)]
          (let [store-empty? (empty? (:store (deref node-a)))
                arrived? (some #(= "resilience-check" (get-in % [:message :rcs/body]))
                                (:inbox (deref node-b2)))
                pass? (and stored-while-down? store-empty? (boolean arrived?))]
            (println "  after B restarts + retry-store!: A's :store size="
                      (count (:store (deref node-a))) " B's :inbox has message?" (boolean arrived?))
            (p/let [_ (tcp/stop-node! node-a) _ (tcp/stop-node! node-b2)]
              (println (if pass? "PASS" "FAIL")
                        " scenario 2: message survived B's downtime via store, delivered on retry-store!")
              pass?)))))))

;; ---------------------------------------------------------------------------
;; Scenario 3 — multi-transport-kind priority (pure router only)
;; ---------------------------------------------------------------------------

(defn- scenario-3 []
  (println "\n--- Scenario 3: multi-transport-kind priority (pure router.route-decision) ---")
  (println "  NOTE: no real mesh-radio hardware exists in this environment. The")
  (println "  mesh-radio link below is a hand-built, simulated link record used ONLY")
  (println "  to prove kotoba.dtn.router's priority ordering is real and honors an")
  (println "  overridden :priority — it does not exercise any real mesh-radio I/O.")
  (let [b (dtn/bundle "+819012345678" "+818098765432" {:body "multi-transport-check"})
        internet-link (link/link "internet-link" "+818098765432" :internet-overlay :reachable? true)
        ;; simulated — no real mesh-radio hardware exists in this environment;
        ;; this link exists only to prove kotoba.dtn.router's priority ordering
        ;; picks internet-overlay first by default, and would pick mesh-radio
        ;; first if the caller passed a resilience-first :priority override.
        mesh-link (link/link "simulated-mesh-link" "+818098765432" :mesh-radio :reachable? true)
        default-decision (router/route-decision b [internet-link mesh-link])
        resilience-decision (router/route-decision b [internet-link mesh-link]
                                                     :priority [:mesh-radio :satellite :internet-overlay])
        default-ok? (and (= :forward (:dtn/action default-decision))
                          (= :internet-overlay (get-in default-decision [:dtn/via :dtn/transport-kind])))
        resilience-ok? (and (= :forward (:dtn/action resilience-decision))
                             (= :mesh-radio (get-in resilience-decision [:dtn/via :dtn/transport-kind])))
        pass? (and default-ok? resilience-ok?)]
    (println "  default priority picked:" (get-in default-decision [:dtn/via :dtn/transport-kind]))
    (println "  resilience-first priority ([:mesh-radio :satellite :internet-overlay]) picked:"
              (get-in resilience-decision [:dtn/via :dtn/transport-kind]))
    (println (if pass? "PASS" "FAIL")
              " scenario 3: router priority ordering honored (routing logic only, no real radio)")
    pass?))

;; ---------------------------------------------------------------------------
;; Driver
;; ---------------------------------------------------------------------------

(-> (p/let [r1 (scenario-1)
            r2 (scenario-2)
            r3 (scenario-3)]
      (let [results [r1 r2 r3]
            passed (count (filter true? results))]
        (println (str "\nRESULT: " passed "/3 scenarios passed"))
        (js/process.exit (if (= passed 3) 0 1))))
    (.catch (fn [e]
              (println "DEMO CRASHED:" e)
              (js/process.exit 1))))
