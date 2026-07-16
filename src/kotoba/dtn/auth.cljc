(ns kotoba.dtn.auth
  "Origin authentication + tamper-evidence for a DTN bundle, via a
  pre-shared symmetric secret per peer and HMAC-SHA256 — NOT full BPSec
  (RFC 9172) security blocks, NOT a PKI/certificate-issuance system, NOT
  TLS. Closes a real gap in `kotoba.dtn.transport.tcp`: that transport
  previously accepted any bundle from any TCP client with zero
  authentication, so any client could connect and inject a bundle
  claiming to be from an arbitrary :dtn/source E.164.

  MECHANISM. Each pair of peers that wants integrity/origin-checking
  shares one secret string out of band (this namespace does not
  distribute or negotiate secrets — see kotoba.dtn.transport.tcp's
  :peer-secrets option, a plain {e164 secret} map the operator
  configures). sign-bundle computes HMAC-SHA256 over the bundle's
  canonical `pr-str` representation — with the :dtn/signature field
  itself stripped out FIRST (dissoc'd), so the signature is never
  computed over a self-referential structure that includes itself — and
  assocs the resulting hex digest back in as :dtn/signature. verify-bundle
  strips :dtn/signature the same way, recomputes, and compares.

  This is an HONEST SUBSET, same 'claim exactly what you built'
  discipline as kotoba.dtn.router's direct-neighbor-or-store heuristic
  (not full CGR) and kotoba.dtn's primary-block fields (not a CBOR
  codec). Explicitly, this mechanism:

    DOES:     - detect a bundle whose body was altered in transit or by
                an intermediary after signing (tamper-evidence)
              - reject a bundle claiming a :dtn/source this node has no
                shared secret for, or one signed with the wrong secret
                (origin authentication within the small pre-configured
                peer set)

    DOES NOT: - provide a PKI or certificate-issuance/revocation system
                (deferred, same as this repo's original BPSec deferral —
                a shared-secret scheme doesn't need one, but also can't
                scale past a small pre-configured peer set the way a PKI
                would)
              - provide TLS / transport-layer confidentiality (bundle
                bodies still travel in cleartext over the TCP transport;
                this only lets the receiver detect forgery/tampering, it
                does not hide the payload from an eavesdropper)
              - provide forward secrecy (a single leaked shared secret
                compromises every past and future bundle signed with it
                until the operator rotates it)
              - protect a peer relationship whose secret has actually
                leaked (a holder of the secret can forge bundles
                indistinguishable from genuine ones — this is
                origin-authentication-within-trust-boundary, not a
                defense against a compromised peer)
              - provide REPLAY protection. There is no nonce or sequence
                number dedup here: a captured, validly-signed bundle can
                be resent verbatim and will re-verify and be re-accepted.
                This is a real, disclosed gap, not silently out of scope
                — closing it would need a further mechanism (e.g. a
                monotonic per-source sequence counter the receiver
                tracks and rejects replays against) that this namespace
                does not implement.

  An honest tamper-evidence/origin-check for a small, pre-configured peer
  set — not a general Internet-security solution.

  CRYPTO IMPLEMENTATION. The bundle-shape/canonicalization logic
  (sign-bundle, verify-bundle, canonical-string below) is pure .cljc and
  identical on both platforms. The actual HMAC-SHA256 computation is
  platform-specific — #?(:clj ...) uses javax.crypto.Mac (JDK standard
  library, no external Maven dependency, which is what makes this whole
  namespace, including sign/verify, testable under plain `clojure
  -M:test`), #?(:cljs ...) uses Node's core node:crypto module (no npm
  dependency, matching kotoba.dtn.transport.tcp's existing node:net/node:fs
  convention). Both platforms are exercised for real in this repo: the
  JVM path by test/kotoba/dtn/auth_test.cljc, the Node path by
  kotoba.dtn.transport.tcp wiring bundles signed and verified live over a
  real TCP socket (test/kotoba/dtn/transport/tcp_demo.cljs scenario 4b).
  Sign and verify always run on the SAME runtime for a given
  send/receive pair in this repo's actual usage (kotoba.dtn.transport.tcp
  is Node/.cljs-only), so cross-platform pr-str byte-for-byte parity
  between JVM Clojure and ClojureScript is not required for correctness
  here — only that pr-str is deterministic for a given map value WITHIN
  one runtime process, which it is.

  Portable (.cljc) across JVM / ClojureScript / SCI / GraalVM."
  #?(:cljs (:require ["node:crypto" :as crypto]))
  #?(:clj (:import [javax.crypto Mac]
                    [javax.crypto.spec SecretKeySpec])))

;; ---------------------------------------------------------------------------
;; Platform-specific HMAC-SHA256 -> lowercase hex digest
;; ---------------------------------------------------------------------------

#?(:clj
   (defn- hmac-sha256-hex
     "HMAC-SHA256(message, secret) -> lowercase hex digest string, via
     javax.crypto.Mac (JDK standard library — no external dependency)."
     [message secret]
     (let [mac (Mac/getInstance "HmacSHA256")
           key-spec (SecretKeySpec. (.getBytes ^String secret "UTF-8") "HmacSHA256")]
       (.init mac key-spec)
       (let [digest (.doFinal mac (.getBytes ^String message "UTF-8"))]
         (apply str (map #(format "%02x" (bit-and (int %) 0xff)) digest))))))

#?(:cljs
   (defn- hmac-sha256-hex
     "HMAC-SHA256(message, secret) -> lowercase hex digest string, via
     Node's core node:crypto module (no npm dependency)."
     [message secret]
     (-> (crypto/createHmac "sha256" secret)
         (.update message "utf8")
         (.digest "hex"))))

;; ---------------------------------------------------------------------------
;; Canonicalization + sign/verify — pure, identical on both platforms
;; ---------------------------------------------------------------------------

(defn- canonical-string
  "The exact string signed/verified: bundle's `pr-str`, with any existing
  :dtn/signature stripped first. Stripping first is what makes this safe
  to call from BOTH sign-bundle (bundle never had a signature yet) and
  verify-bundle (bundle already carries one, which must not be part of
  what it's being verified against — signing a self-referential
  structure that includes its own signature is never correct)."
  [bundle]
  (pr-str (dissoc bundle :dtn/signature)))

(defn sign-bundle
  "Compute an HMAC-SHA256 signature over bundle (see canonical-string)
  keyed by secret, and return bundle with that hex digest assoc'd in as
  :dtn/signature. secret is a pre-shared secret string associated with
  bundle's claimed :dtn/source (the caller, e.g.
  kotoba.dtn.transport.tcp, is responsible for looking up the right
  secret for the destination peer — this function just signs with
  whatever secret it's given)."
  [bundle secret]
  (assoc bundle :dtn/signature (hmac-sha256-hex (canonical-string bundle) secret)))

(defn verify-bundle
  "true iff bundle carries a :dtn/signature that matches
  HMAC-SHA256(canonical-string(bundle), secret) — i.e. bundle was signed
  with this exact secret and has not been altered since. false when
  :dtn/signature is missing, or does not match (wrong secret, or the
  bundle body was tampered with after signing)."
  [bundle secret]
  (let [claimed (:dtn/signature bundle)]
    (boolean
     (and (string? claimed)
          (= claimed (hmac-sha256-hex (canonical-string bundle) secret))))))
