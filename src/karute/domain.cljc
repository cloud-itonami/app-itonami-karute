(ns karute.domain
  "Karute's view of the medical-family Itonami business bots
  (電子カルテ and レセプト).

  The bot-state reads (assignment list, activity feed, health) are the
  shared package itonami.bot-status (packages/itonami-bot-status), used by
  the family apps so Karute and Rodo stop carrying near-identical copies.
  This namespace keeps Karute's own blueprint list, consent operations,
  descriptor and bundled-profile checks, and delegates the shared reads.
  Runs in the browser (Squint) and on a JVM host. No I/O here."
  (:require [clojure.string :as str] [itonami.bot-status :as bs]))

(def blueprint-id "cloud-itonami-isic-8620")
;; Medical-family blueprints this app carries a bundled profile for (AWAI-3381):
;; 8620 clinical practice (電子カルテ) and iryo (レセプト).
(def blueprints ["cloud-itonami-isic-8620" "iryo"])
(def registry-states #{"registered" "not-registered"})
(def capability "itonami-bot-read")
(def service "itonami-grok-bots")

;; Shared bot-state surface, re-exported so existing callers keep working.
(def bots-origin bs/bots-origin)
(def assignments-path bs/assignments-path)
(def activity-prefix bs/activity-prefix)
(def max-response-bytes bs/max-response-bytes)
(def max-events bs/max-events)

;; Every request is its own operation and needs its own trusted user choice.
;; The activity route is a bounded prefix; activity-url builds the only shape
;; this app sends, and only for a bot id read from the assignment list.
(def operations
  [{:capability capability :service service :operation "read-assignments" :consent "each-operation"
    :data-categories ["request-ip"]
    :endpoint {:origin bots-origin :routes [{:method "GET" :path assignments-path :query-params []}]}}
   {:capability capability :service service :operation "read-activity" :consent "each-operation"
    :data-categories ["request-ip" "bot-id"]
    :endpoint {:origin bots-origin :routes [{:method "GET" :path-prefix activity-prefix :query-params []}]}}])

(defn descriptor []
  {:schema "kotoba.app.client-runtime.v1" :target "browser" :profile "trusted-page-v1" :entry "index.html"
   :local-state {:adapter "memory" :schema "itonami.karute.local.v1" :version 1}
   :workers [] :operations operations})

(defn safe-id? [x] (bs/safe-id? x))
(defn assignments-url [] (bs/assignments-url))
(defn activity-url [bot-id] (bs/activity-url bot-id))
(defn parse-time [s] (bs/parse-time s))

(defn assignment
  ([body] (assignment body blueprint-id))
  ([body blueprint-id] (bs/assignment body blueprint-id)))

(defn activity [body bot-id] (bs/activity body bot-id))
(defn health [a events now-ms] (bs/health a events now-ms))

(defn- match? [re s] (and (string? s) (some? (re-matches re s))))
(defn- int-value? [x] #?(:clj (integer? x) :cljs (js/Number.isSafeInteger x)))
(defn- array-value? [x] #?(:clj (sequential? x) :cljs (js/Array.isArray x)))

;; ---- bundled profile -------------------------------------------------------

(defn profile-problems
  ([doc] (profile-problems doc blueprint-id))
  ([doc blueprint-id]
  (let [p (:profile doc) bp (:business_blueprint p) money (:money p)]
    (cond-> []
      (not= "itonami.business-bot-profile.v1" (:schema doc)) (conj "schema")
      (not (match? #"[0-9a-f]{40}" (get-in doc [:provenance :revision]))) (conj "provenance")
      (not= (str "bot-profile:" blueprint-id) (:profile_id p)) (conj "profile-id")
      (not= blueprint-id (:id bp)) (conj "blueprint")
      (not (str/includes? (str (get-in doc [:mission :statement])) "労働から解放")) (conj "mission")
      (not (contains? registry-states (get-in doc [:provenance :registry_state]))) (conj "registry-state")
      (not= "health" (get-in p [:mission :domain_family])) (conj "domain-family")
      (not (str/includes? (str (get-in p [:phi :rule])) "PHI")) (conj "phi-rule")
      (not (and (array-value? (:allowed_services money)) (every? #(contains? #{"murakumo" "kotobase"} %) (:allowed_services money)))) (conj "allowed-services")
      (not (true? (get-in p [:health_rule :fail_closed]))) (conj "health-rule")))))
