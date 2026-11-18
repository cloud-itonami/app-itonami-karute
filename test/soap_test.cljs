(ns soap-test
  (:require [karute.soap :as s] [karute.rezept :as rz] [karute.domain :as d] ["node:test" :refer [test]] ["node:assert/strict" :as a] ["node:fs" :as fs]))

(defn fx [f] (fs/readFileSync (str "test/fixtures/" f) "utf8"))
(defn reason [f] (try (f) nil (catch :default e (:reason (ex-data e)))))
(defn by-id [vs id] (first (filter #(= id (:id %)) vs)))

(test "a complete note has all four sections, vitals and two problems"
  (fn [] (let [r (s/check (fx "soap-complete.txt"))]
    (a/deepEqual (:missing r) [])
    (a/deepEqual (mapv :present (:sections r)) [true true true true])
    (a/equal (:status (by-id (:vitals r) "temperature")) "outside-reference")
    (a/equal (:value (by-id (:vitals r) "temperature")) 37.8)
    (a/equal (:status (by-id (:vitals r) "pulse")) "within-reference")
    (a/equal (:text (by-id (:vitals r) "blood-pressure")) "128/76")
    (a/equal (:status (by-id (:vitals r) "blood-pressure")) "within-reference")
    (a/equal (:status (by-id (:vitals r) "spo2")) "within-reference")
    (a/deepEqual (mapv :name (:diagnoses r)) ["急性上気道炎" "高血圧症"])
    (a/deepEqual (mapv :main (:diagnoses r)) [true false])
    (a/deepEqual (:observations r) ["vital-outside-reference"]))))

(test "full-width headers and digits are read; gaps and a typo are reported"
  (fn [] (let [r (s/check (fx "soap-gaps.txt"))]
    (a/deepEqual (:missing r) ["A"])
    (a/equal (:status (by-id (:vitals r) "blood-pressure")) "outside-reference")
    (a/equal (:status (by-id (:vitals r) "pulse")) "outside-reference")
    (a/equal (:status (by-id (:vitals r) "temperature")) "implausible")
    (a/deepEqual (:observations r) ["section-missing" "vital-implausible" "vital-outside-reference" "plan-without-assessment"]))))

(test "negative controls: a note without headers is all missing, an impossible BP is implausible"
  (fn [] (a/deepEqual (:missing (s/check "咽頭痛あり")) ["S" "O" "A" "P"])
    (a/equal (:status (first (s/vitals "BP 70/90"))) "implausible")
    (a/deepEqual (s/vitals "特記なし") [])
    (a/ok (:suspected (first (s/diagnoses "肺炎の疑い"))))))

(test "inputs are bounded and empty input refused"
  (fn [] (a/equal (reason #(s/check "")) "note-empty")
    (a/equal (reason #(s/check (apply str (repeat 2700 "あ")))) "note-too-long")
    (a/equal (count (s/diagnoses (apply str (repeat 30 "#1 感冒 ")))) s/max-diagnoses)))

(test "A problems carry to the claim input and parse there (on device)"
  (fn [] (let [lines (rz/diagnosis-lines (:diagnoses (s/check (fx "soap-complete.txt"))))
               items (rz/parse lines)]
    (a/equal lines "病名 急性上気道炎 主\n病名 高血圧症")
    (a/deepEqual (mapv :main items) [true false]))))

(test "the clinical check adds no network operation"
  (fn [] (a/deepEqual (mapv :operation (:operations (d/descriptor))) ["read-assignments" "read-activity"])))
