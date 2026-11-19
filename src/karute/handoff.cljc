(ns karute.handoff
  "karute → iryo consent hand-off envelope (AWAI-3389).

  Carrying the A-section problems to the claim input is a handoff of PHI, so
  it is wrapped in an explicit consent record: the exact names, the scope, and
  a short validity window. The envelope is created in page memory, shown to the
  person, and applied only after their confirmation and only while it is fresh
  and unchanged.

  Member-signed verification (cloud-itonami/karute MATURITY #8/#13, Ed25519)
  plugs in through verify-with: the canonical bytes below are what a member
  key signs, and verify-with checks a presented signature with a caller
  supplied verifier. Until member keys exist the app carries unsigned
  in-memory envelopes only, and says so on the confirmation screen. Nothing
  here sends or stores the envelope."
  (:require [clojure.string :as str]))

(def schema "itonami.karute-handoff.v1")
(def scope "karute-diagnoses-to-iryo")
(def validity-ms 300000)
(def max-diagnoses 20)

(defn- utf8-bytes [s]
  #?(:clj (count (.getBytes ^String s "UTF-8"))
     :cljs (.-length (.encode (js/TextEncoder.) s))))

(defn- int-value? [x] #?(:clj (integer? x) :cljs (js/Number.isSafeInteger x)))
(defn- refuse [reason] (throw (ex-info reason {:reason reason})))

(defn- seq-of-diagnoses? [x]
  #?(:clj (sequential? x)
     :cljs (or (sequential? x) (js/Array.isArray x))))

(defn- diagnosis-problem [d]
  (or (when-not (and (map? d) (string? (:name d)) (<= 1 (count (:name d)) 60))
        "handoff-diagnoses")
      ;; Names must not smuggle line breaks into the claim input or the
      ;; canonical bytes.
      (when (or (str/includes? (:name d) "\n") (str/includes? (:name d) "\r")
                (str/includes? (:name d) "\t"))
        "handoff-diagnoses")))

(defn canonical
  "Deterministic bytes a member key signs. Field order and the diagnosis
  order are fixed; names carry no separators that could collide."
  [env]
  (str schema "\n" scope "\n" (:created-at env) "\n" (:expires-at env) "\n"
       (str/join "\n" (map (fn [d] (str (:name d) "\t"
                                        (if (:main d) "1" "0")
                                        (if (:suspected d) "1" "0")))
                           (:diagnoses env)))))

(defn make
  "Consent record for carrying diagnoses to the claim input. now-ms is the
  host clock at the moment the person asked to carry."
  [diagnoses now-ms]
  (when-not (int-value? now-ms) (refuse "handoff-time"))
  ;; On the browser vectors arrive as arrays; accept both without I/O.
  (when-not (seq-of-diagnoses? diagnoses) (refuse "handoff-diagnoses"))
  (when-not (let [n (count diagnoses)] (and (pos? n) (<= n max-diagnoses))) (refuse "handoff-diagnoses"))
  (let [dx (mapv (fn [d] {:name (:name d) :main (boolean (:main d)) :suspected (boolean (:suspected d))}) diagnoses)]
    (when (some diagnosis-problem dx) (refuse "handoff-diagnoses"))
    {:schema schema :scope scope :diagnoses dx
     :created-at now-ms :expires-at (+ now-ms validity-ms)}))

(defn problem
  "nil when the envelope may be applied now, else the reason it must not be."
  [env now-ms]
  (cond
    (not (map? env)) "handoff-empty"
    (not= schema (:schema env)) "handoff-schema"
    (not= scope (:scope env)) "handoff-scope"
    (not (int-value? now-ms)) "handoff-time"
    (not (int-value? (:created-at env))) "handoff-time"
    (not (int-value? (:expires-at env))) "handoff-time"
    (> (:created-at env) now-ms) "handoff-not-yet-valid"
    (>= now-ms (:expires-at env)) "handoff-expired"
    (not= (- (:expires-at env) (:created-at env)) validity-ms) "handoff-window"
    (not (seq-of-diagnoses? (:diagnoses env))) "handoff-diagnoses"
    :else (or (some diagnosis-problem (:diagnoses env))
              (when (or (empty? (:diagnoses env)) (> (count (:diagnoses env)) max-diagnoses)) "handoff-diagnoses")
              nil)))

(defn matches?
  "True when the envelope still names exactly the current A-section problems."
  [env diagnoses]
  (and (nil? (problem env  (:created-at env)))
       (= (mapv (fn [d] [(:name d) (boolean (:main d)) (boolean (:suspected d))]) (:diagnoses env))
          (mapv (fn [d] [(:name d) (boolean (:main d)) (boolean (:suspected d))]) diagnoses))))

(defn verify-with
  "Check a member-signed envelope. verify-fn receives the canonical string,
  the signature hex and the public-key hex, and returns truthy only for a
  real Ed25519 member signature. Refuses on any shape, freshness or
  verification failure: an unverifiable signature never applies."
  [env pubkey-hex sig-hex verify-fn now-ms]
  (when-let [p (problem env now-ms)] (refuse p))
  (when-not (and (string? pubkey-hex) (re-matches #"[0-9a-f]{64}" pubkey-hex)) (refuse "handoff-pubkey-shape"))
  (when-not (and (string? sig-hex) (re-matches #"[0-9a-f]{128}" sig-hex)) (refuse "handoff-signature-shape"))
  (when-not (fn? verify-fn) (refuse "handoff-verify-missing"))
  (let [ok (try (verify-fn (canonical env) sig-hex pubkey-hex) (catch :default _ false))]
    (when-not ok (refuse "handoff-bad-signature"))
    env))

(defn envelope-text
  "One-line summary for the confirmation screen. Names are joined with 、 so
  the screen shows exactly what will be written."
  [env]
  (str/join "、" (map (fn [d] (str (:name d) (when (:main d) "（主）") (when (:suspected d) "（疑い）")))
                      (:diagnoses env))))
