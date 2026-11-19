(ns shared-status-test
  (:require [itonami.bot-status :as bs] ["node:test" :refer [test]] ["node:assert/strict" :as a] ["node:fs" :as fs]))

(defn fixture [f] (js/JSON.parse (fs/readFileSync (str "test/fixtures/" f) "utf8")))
(def businesses (fixture "businesses-20261118.json"))
(def feed-7310 (fixture "activity-7310-20261118.json"))
(def now (js/Date.parse "2026-11-18T01:30:00Z"))
(defn clone [x] (js/JSON.parse (js/JSON.stringify x)))
(defn reason [f] (try (f) nil (catch :default e (:reason (ex-data e)))))

(test "the shared reads serve any blueprint id, not just Karute's"
  (fn [] (let [x (bs/assignment businesses "cloud-itonami-isic-8620")]
    (a/equal (:state x) "not-launched"))
    (let [mine (clone businesses) rows (.-data mine)]
      (aset (aget (aget rows 0) "business") "id" "example-bot")
      (let [x (bs/assignment mine "example-bot")]
        (a/equal (:state x) "launched")
        (a/ok (bs/safe-id? (:bot-id x)))))))

(test "negative controls: unsafe ids, duplicates and bad shapes refuse"
  (fn [] (a/equal (reason #(bs/activity-url "../businesses")) "unsafe-bot-id")
    (a/equal (reason #(bs/activity-url "__proto__")) "unsafe-bot-id")
    (a/equal (reason #(bs/assignment (clone businesses) "cloud-itonami-isic-8620")) nil)
    (let [dup (clone businesses)]
      (aset (aget (aget (.-data dup) 1) "business") "id" "cloud-itonami-isic-8620")
      (aset (aget (aget (.-data dup) 0) "business") "id" "cloud-itonami-isic-8620")
      (a/equal (reason #(bs/assignment dup "cloud-itonami-isic-8620")) "assignment-duplicate"))
    (a/equal (reason #(bs/activity {:schema "x"} "b")) "activity-feed-shape")
    (a/equal (reason #(bs/assignment {:object "list"} "b")) "assignment-list-shape")))

(test "health stays fail-closed through the shared namespace"
  (fn [] (let [x (bs/assignment businesses "cloud-itonami-isic-8620")]
    (a/deepEqual (bs/health x nil now) {:verdict "not-launched" :reasons ["bot-not-launched"]}))
    ;; A running bot whose feed was never read is unknown, never healthy.
    (a/deepEqual (bs/health {:state "launched" :status "running" :interval-ms 3600000 :last-checkpoint-at now} nil now)
                 {:verdict "unknown" :reasons ["activity-not-read"]})
    (let [mine (clone businesses) rows (.-data mine)]
      (aset (aget (aget rows 0) "business") "id" "example-bot")
      (let [x (bs/assignment mine "example-bot") ev (bs/activity feed-7310 (:bot-id x))]
        (a/equal (:verdict (bs/health x ev now)) "unhealthy")))))
