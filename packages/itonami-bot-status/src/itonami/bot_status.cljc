(ns itonami.bot-status
  "Shared bot-state reads for the Itonami family apps (Karute, Rodo).

  Pure, fail-closed status reads shared by the family end-user apps so the
  two codebases stop carrying near-identical copies (AWAI-3389). Runs in the
  browser (Squint) and on a JVM host. No I/O here.

  Nothing here names an app: callers pass their own blueprint id, and keep
  their own descriptor, consent operations and profile checks. PHI never
  reaches these functions; the only data categories the callers declare for
  the underlying reads are request-ip and bot-id."
  (:require [clojure.string :as str]))

(def bots-origin "https://bots.itonami.cloud")
(def assignments-path "/v1/grok-bots/businesses")
(def activity-prefix "/v1/grok-bots/bots/")
(def max-response-bytes 262144)
(def max-events 20)

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

;; ---- assignment list -------------------------------------------------------

(defn assignment
  "Read the public assignment list. Absent blueprint => not launched.
  Any shape we cannot verify is refused, never shown as launched or healthy."
  [body blueprint-id]
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
        (refuse "assignment-duplicate")))))

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
