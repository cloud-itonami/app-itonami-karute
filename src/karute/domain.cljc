(ns karute.domain
  "Pure, fail-closed domain for the medical-family Itonami business bots
  (電子カルテ and レセプト). Status reads only; the clinical and billing work is
  in karute.soap and karute.rezept. Runs in the browser (Squint) and on a JVM
  host. No I/O here."
  (:require [clojure.string :as str]))

(def blueprint-id "cloud-itonami-isic-8620")
;; Medical-family blueprints this app carries a bundled profile for (AWAI-3381):
;; 8620 clinical practice (電子カルテ) and iryo (レセプト).
(def blueprints ["cloud-itonami-isic-8620" "iryo"])
(def registry-states #{"registered" "not-registered"})
(def bots-origin "https://bots.itonami.cloud")
(def assignments-path "/v1/grok-bots/businesses")
(def activity-prefix "/v1/grok-bots/bots/")
(def capability "itonami-bot-read")
(def service "itonami-grok-bots")
(def max-response-bytes 262144)
(def max-events 20)

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

(defn- match? [re s] (and (string? s) (some? (re-matches re s))))
(defn- int-value? [x] #?(:clj (integer? x) :cljs (js/Number.isSafeInteger x)))
(defn- array-value? [x] #?(:clj (sequential? x) :cljs (js/Array.isArray x)))
(defn- nat-int? [x] (and (int-value? x) (>= x 0)))
(defn- pos-int? [x] (and (int-value? x) (> x 0)))
(defn- short-text? [x n] (and (string? x) (not (str/blank? x)) (<= (count x) n)))

(defn safe-id? [x]
  (and (match? #"[A-Za-z0-9][A-Za-z0-9._-]{0,127}" x)
       (not (contains? #{"__proto__" "constructor" "prototype"} x))))

(defn assignments-url [] (str bots-origin assignments-path))

(defn activity-url [bot-id]
  (when-not (safe-id? bot-id) (throw (ex-info "unsafe bot id" {:reason "unsafe-bot-id"})))
  (str bots-origin activity-prefix bot-id "/activity"))

(defn parse-time [s]
  (when (match? #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z" s)
    #?(:clj (.toEpochMilli (java.time.Instant/parse s))
       :cljs (let [t (js/Date.parse s)] (when (js/Number.isFinite t) t)))))

(defn- refuse [reason] (throw (ex-info reason {:reason reason})))

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

;; ---- assignment list -------------------------------------------------------

(defn assignment
  "Read the public assignment list. Absent blueprint => not launched.
  Any shape we cannot verify is refused, never shown as launched or healthy."
  ([body] (assignment body blueprint-id))
  ([body blueprint-id]
  (let [rows (:data body)]
    (when-not (and (= "list" (:object body)) (array-value? rows) (<= (count rows) 500))
      (refuse "assignment-list-shape"))
    (let [mine (filterv #(= blueprint-id (get-in % [:business :id])) rows)]
      (case (count mine)
        0 {:state "not-launched" :others (count rows)}
        1 (let [row (first mine) bot (:bot row) pos (:position row)]
            (when-not (and (true? (:configured bot)) (safe-id? (:bot_id bot))
                           (short-text? (:status bot) 40) (pos-int? (:interval_ms bot))
                           (nat-int? (:tick_count bot))
                           (or (nil? (:last_checkpoint_at bot)) (nat-int? (:last_checkpoint_at bot))))
              (refuse "assignment-bot-shape"))
            (when-not (and (= (:bot_id bot) (:bot_id pos)) (false? (:transferable pos)) (false? (:onchain_token pos))
                           (nat-int? (:credit_limit_micros pos)) (nat-int? (:debt_micros pos)))
              (refuse "assignment-position-shape"))
            {:state "launched" :others (dec (count rows))
             :bot-id (:bot_id bot) :status (:status bot) :tick-count (:tick_count bot)
             :interval-ms (:interval_ms bot) :last-checkpoint-at (:last_checkpoint_at bot)
             :credit-limit-micros (:credit_limit_micros pos) :debt-micros (:debt_micros pos)})
        (refuse "assignment-duplicate"))))))

;; ---- activity feed ---------------------------------------------------------

(defn- event-view [bot-id e]
  (let [at (parse-time (:occurred_at e))
        err (get-in e [:event :error])]
    (when-not (and (= "itonami.bot-activity.v1" (:schema e)) (= bot-id (:bot_id e))
                   (short-text? (:activity_id e) 200) (short-text? (:type e) 80) at
                   (= "sha256" (get-in e [:integrity :algorithm]))
                   (match? #"[0-9a-f]{64}" (get-in e [:integrity :digest]))
                   (or (nil? err) (string? err)))
      (refuse "activity-event-shape"))
    {:id (:activity_id e) :type (:type e) :occurred-at (:occurred_at e) :at at
     :error (when (and (string? err) (not (str/blank? err))) (subs err 0 (min 300 (count err))))
     :digest (get-in e [:integrity :digest])}))

(defn activity [body bot-id]
  (when-not (safe-id? bot-id) (refuse "unsafe-bot-id"))
  (when-not (and (= "itonami.bot-activity-feed.v1" (:schema body)) (= bot-id (:bot_id body))
                 (true? (:public_by_design body)) (array-value? (:data body)) (<= (count (:data body)) 500))
    (refuse "activity-feed-shape"))
  (->> (:data body) (mapv #(event-view bot-id %)) (sort-by :at) reverse (take max-events) vec))

;; ---- health ----------------------------------------------------------------

(defn health
  "Fail closed. `running` alone is never healthy: the checkpoint must be recent
  and the newest public event must carry no error. events = nil means the feed
  has not been read, which is unknown rather than healthy."
  [a events now-ms]
  (if (not= "launched" (:state a))
    {:verdict "not-launched" :reasons ["bot-not-launched"]}
    (let [cp (:last-checkpoint-at a)
          latest (first events)
          reasons (cond-> []
                    (not= "running" (:status a)) (conj (str "status-" (:status a)))
                    (nil? cp) (conj "no-checkpoint")
                    (and cp (> (- now-ms cp) (* 2 (:interval-ms a)))) (conj "checkpoint-stale")
                    (nil? events) (conj "activity-not-read")
                    (and (some? events) (empty? events)) (conj "no-activity")
                    (:error latest) (conj "latest-event-error"))]
      {:verdict (cond (empty? reasons) "healthy"
                      (and (= 1 (count reasons)) (= "activity-not-read" (first reasons))) "unknown"
                      :else "unhealthy")
       :reasons reasons})))
