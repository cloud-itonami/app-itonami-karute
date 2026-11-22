(ns domain-test
  (:require [karute.domain :as d] ["node:test" :refer [test]] ["node:assert/strict" :as a] ["node:fs" :as fs]
            ["../../vendor/assets/kotoba-client-runtime.js" :as runtime]))

(defn fixture [f] (js/JSON.parse (fs/readFileSync (str "test/fixtures/" f) "utf8")))
(def businesses (fixture "businesses-20261118.json"))
(def feed-7310 (fixture "activity-7310-20261118.json"))
(def profiles {"cloud-itonami-isic-8620" (fixture "../../profiles/business-bot-profile-8620.json")
               "iryo" (fixture "../../profiles/business-bot-profile-iryo.json")})
(def now (js/Date.parse "2026-11-18T01:30:00Z"))
(defn clone [x] (js/JSON.parse (js/JSON.stringify x)))
(defn reason [f] (try (f) nil (catch :default e (:reason (ex-data e)))))
(defn as-ours [body id]
  (let [b (clone body)] (aset (aget (aget (.-data b) 0) "business") "id" id) b))

(test "both bundled medical profiles verify and are registry copies"
  (fn [] (doseq [id d/blueprints]
           (let [p (get profiles id)]
             (a/deepEqual (d/profile-problems p id) [])
             (a/equal (get-in p [:provenance :registry_state]) "registered")
             (a/ok (.includes (get-in p [:profile :phi :rule]) "PHI"))))))

(test "negative controls: a changed mission, family, registry state or service is refused"
  (fn [] (let [base (get profiles "cloud-itonami-isic-8620") id "cloud-itonami-isic-8620"]
    (let [p (clone base)] (aset (aget p "mission") "statement" "x") (a/ok (some #{"mission"} (d/profile-problems p id))))
    (let [p (clone base)] (aset (aget (aget p "profile") "mission") "domain_family" "labor") (a/ok (some #{"domain-family"} (d/profile-problems p id))))
    (let [p (clone base)] (aset (aget p "provenance") "registry_state" "launched") (a/ok (some #{"registry-state"} (d/profile-problems p id))))
    (let [p (clone base)] (aset (aget (aget p "profile") "money") "allowed_services" #js["openai"]) (a/ok (some #{"allowed-services"} (d/profile-problems p id))))
    (a/ok (some #{"profile-id"} (d/profile-problems base "iryo"))))))

(test "descriptor passes the canonical runtime and derives connect-src for one origin"
  (fn [] (a/deepEqual (vec (runtime/clientRuntimeProblems (d/descriptor) #js[d/capability] #js["tauri://localhost"])) [])
    (a/equal (runtime/clientRuntimeConnectSrc (d/descriptor) #js[d/capability] #js[] #js["tauri://localhost"])
             "connect-src https://bots.itonami.cloud")
    (let [csp (str "connect-src " d/bots-origin " ipc: http://ipc.localhost")]
      (a/ok (.includes (fs/readFileSync "src-tauri/tauri.conf.json" "utf8") csp))
      (a/ok (.includes (fs/readFileSync "public/index.html" "utf8") csp)))))

(test "only declared URLs match; patient data has no route"
  (fn [] (let [[assign act] d/operations]
    (a/deepEqual (mapv :operation d/operations) ["read-assignments" "read-activity"])
    (a/ok (runtime/clientEndpointAllowsRequest (:endpoint assign) (d/assignments-url) "GET"))
    (a/equal (runtime/clientEndpointAllowsRequest (:endpoint assign) (str (d/assignments-url) "?patient=1") "GET") false)
    (a/equal (runtime/clientEndpointAllowsRequest (:endpoint assign) (d/assignments-url) "POST") false)
    (a/equal (runtime/clientEndpointAllowsRequest (:endpoint act) "https://bots.itonami.cloud/v1/grok-bots/businesses" "GET") false)
    (a/equal (reason #(d/activity-url "../businesses")) "unsafe-bot-id")
    (doseq [o d/operations] (a/ok (every? #{"request-ip" "bot-id"} (:data-categories o)))))))

(test "live list (2026-11-18): neither medical bot is launched"
  (fn [] (doseq [id d/blueprints]
    (let [x (d/assignment businesses id)]
      (a/equal (:state x) "not-launched") (a/equal (:others x) 5)
      (a/deepEqual (d/health x nil now) {:verdict "not-launched" :reasons ["bot-not-launched"]})))))

(test "real held bot relabelled as 8620 is unhealthy, never healthy"
  (fn [] (let [id "cloud-itonami-isic-8620" x (d/assignment (as-ours businesses id) id) ev (d/activity feed-7310 (:bot-id x)) h (d/health x ev now)]
    (a/equal (:state x) "launched")
    (a/equal (:verdict h) "unhealthy")
    (a/ok (some #{"status-held"} (:reasons h)))
    (a/equal (:verdict (d/health x nil now)) "unhealthy"))))

(test "review fix: a profile without the PHI rule is refused"
  (fn [] (let [p (clone (get profiles "iryo"))] (aset (aget p "profile") "phi" nil)
    (a/ok (some #{"phi-rule"} (d/profile-problems p "iryo"))))))
