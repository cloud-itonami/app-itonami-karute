(ns karute.member-keys
  "Member public-key set for karute → iryo hand-off verification (AWAI-3389).

  This is the contract the owner fills once cloud-itonami/karute MATURITY
  #8/#13 lands: which member keys may sign a hand-off envelope, and how a
  presented signature is checked against karute.handoff/canonical with a real
  Ed25519 verifier the caller supplies. No keys ship here — the set is
  validated when configured, and every failure (unknown member, wrong scope,
  bad signature, stale envelope) refuses instead of falling back. Until a key
  set is configured the app keeps the unsigned in-memory path and says so."
  (:require [clojure.string :as str] [karute.handoff :as handoff]))

(def schema "itonami.member-keys.v1")
(def max-keys 50)
(def max-member-chars 200)

(defn- refuse [reason] (throw (ex-info reason {:reason reason})))
(defn- seq-of-keys? [x]
  #?(:clj (sequential? x)
     :cljs (or (sequential? x) (js/Array.isArray x))))

(defn- key-problem [k]
  (or (when-not (and (map? k) (string? (:member k))
                     (not (str/blank? (:member k))) (<= (count (:member k)) max-member-chars))
        "member-key")
      (when-not (and (string? (:pubkey k)) (re-matches #"[0-9a-f]{64}" (:pubkey k)))
        "member-key")
      (when-not (and (string? (:scope k)) (not (str/blank? (:scope k))))
        "member-key")))

(defn keyset-problem
  "nil when the configured key set may be used, else the reason it must not be."
  [doc]
  (cond
    (not (map? doc)) "member-keys-schema"
    (not= schema (:schema doc)) "member-keys-schema"
    (not (seq-of-keys? (:keys doc))) "member-keys-shape"
    (or (empty? (:keys doc)) (> (count (:keys doc)) max-keys)) "member-keys-shape"
    :else (or (some key-problem (:keys doc))
              (let [members (mapv :member (:keys doc))
                    pubs (mapv :pubkey (:keys doc))]
                (when (or (not= (count members) (count (distinct members)))
                                (not= (count pubs) (count (distinct pubs))))
                  "member-duplicate"))
              nil)))

(defn find-key
  "The configured key for one member id, or nil."
  [keyset member-id]
  (if (keyset-problem keyset)
    nil
    (or (first (filterv #(= member-id (:member %)) (:keys keyset))) nil)))

(defn verify-member
  "Check a member-signed hand-off envelope. verify-fn receives the canonical
  string, the signature hex and the member's public-key hex, and returns
  truthy only for a real Ed25519 member signature. The member must be
  configured, its key must cover this envelope's scope, and the envelope must
  be fresh: anything else refuses before or without calling the verifier."
  [env member-id sig-hex keyset verify-fn now-ms]
  (when-let [p (keyset-problem keyset)] (refuse p))
  (let [k (find-key keyset member-id)]
    (when-not k (refuse "member-unknown"))
    (when-not (= handoff/scope (:scope k)) (refuse "member-scope"))
    (handoff/verify-with env (:pubkey k) sig-hex verify-fn now-ms)))
