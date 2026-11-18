(ns draft-test
  (:require [karute.draft :as dr] [karute.soap :as s] [karute.domain :as d] ["node:test" :refer [test]] ["node:assert/strict" :as a] ["node:fs" :as fs]
            ["../../vendor/awai-inference/client.js" :refer [createInferenceClient]]))

(def note (fs/readFileSync "test/fixtures/soap-complete.txt" "utf8"))
(def gaps (fs/readFileSync "test/fixtures/soap-gaps.txt" "utf8"))
(defn reason [f] (try (f) nil (catch :default e (:reason (ex-data e)))))
(defn bytes [x] (.-length (.encode (js/TextEncoder.) x)))

(test "the prompt carries the note, the gaps and an instruction not to add findings"
  (fn [] (let [p (dr/prompt note (s/check note))]
    (a/ok (.includes p "急性上気道炎"))
    (a/ok (.includes p "加えないでください"))
    (a/ok (.includes p "temperature 37.8")))
    (let [p (dr/prompt gaps (s/check gaps))]
      (a/ok (.includes p "記載がない見出し: A")))))

(test "prompt bounds stay under Murakumo's 8192-byte limit"
  (fn [] (let [long (str "S: " (apply str (repeat 1990 "あ")))]
    (a/ok (<= (bytes (dr/prompt long (s/check long))) dr/max-prompt-bytes))
    (a/ok (< dr/max-prompt-bytes 8192)))
    (a/equal (reason #(dr/prompt (str "S: " (apply str (repeat 2100 "あ"))) {})) "note-too-long-for-draft")
    (a/equal (reason #(dr/prompt "" {})) "note-empty")))

(test "readiness is fail-closed and never assumes a loaded model"
  (fn [] (a/equal (dr/readiness {:installed false :remote false}) "not-installed")
    (a/equal (dr/readiness {:installed true :remote true :status {:imported true :loaded true}}) "refused-remote")
    (a/equal (dr/readiness {:installed true}) "refused-remote")
    (a/equal (dr/readiness {:installed true :remote false}) "status-unknown")
    (a/equal (dr/readiness {:installed true :remote false :status {:imported false :loaded false}}) "model-not-imported")
    (a/equal (dr/readiness {:installed true :remote false :status {:imported true :loaded false}}) "model-not-loaded")
    (a/equal (dr/readiness {:installed true :remote false :status {:imported true :loaded true :busy true}}) "busy")
    (a/equal (dr/readiness {:installed true :remote false :status {:imported true :loaded true :busy false}}) "ready")))

(test "a draft must come from llama.cpp text; think blocks are dropped"
  (fn [] (let [ok {:schema "awai.inference.result.v1" :engine "llama.cpp" :modality "text" :text "<think>x</think>\n咽頭痛と鼻汁。"}]
    (a/equal (:text (dr/draft ok)) "咽頭痛と鼻汁。")
    (a/equal (reason #(dr/draft (assoc ok :engine "remote"))) "invalid-draft")
    (a/equal (reason #(dr/draft (assoc ok :modality "image"))) "invalid-draft")
    (a/equal (reason #(dr/draft (assoc ok :text "<think>only</think>"))) "empty-draft")
    (a/equal (count (:text (dr/draft (assoc ok :text (apply str (repeat 3000 "あ")))))) dr/max-draft-chars))))

(test "outside Android the pinned client refuses without any network fallback"
  (fn [] (let [c (createInferenceClient #js{:invoke nil})]
    (-> (.generate c #js{:prompt "x"}) (.then (fn [_] (a/fail "should not generate")) (fn [e] (a/match (.-message e) #"unavailable")))))))

(test "a denied native confirmation yields no draft"
  (fn [] (let [c (createInferenceClient #js{:invoke (fn [_ _] (js/Promise.reject (js/Error. "inference-denied-or-unavailable")))})]
    (-> (.generate c #js{:prompt (dr/prompt note (s/check note)) :maxTokens dr/max-tokens})
        (.then (fn [_] (a/fail "denial must not produce a draft")) (fn [e] (a/ok (dr/denied? (dr/error-text e)))))))))

(test "denial is read from a Tauri string and from an Error"
  (fn [] (a/equal (dr/denied? (dr/error-text "local-inference-denied-or-unavailable")) true)
    (a/equal (dr/denied? (dr/error-text (js/Error. "inference-denied-or-unavailable"))) true)
    (a/equal (dr/denied? (dr/error-text (js/Error. "busy"))) false)))

(test "inference adds no network operation, and the app has no js->clj"
  (fn [] (a/deepEqual (mapv :operation (:operations (d/descriptor))) ["read-assignments" "read-activity"])
    (a/ok (not (.includes (fs/readFileSync "src/karute/app.cljs" "utf8") "js->clj")))))
