(ns rezept-test
  (:require [karute.rezept :as r] ["node:test" :refer [test]] ["node:assert/strict" :as a] ["node:fs" :as fs]))

(defn reason [f] (try (f) nil (catch :default e (:reason (ex-data e)))))
(defn calc [text patient] (r/compute (r/parse text) patient))

;; Expected values are iryo's own tests at 18cf0f8 (test_rezept, test_compute_drug_ten,
;; test_kogaku, test_insurance), with the seed master values typed in as input.
(test "yakka-to-ten: ≤15 円 is 1 点, then 五捨五超入"
  (fn [] (a/deepEqual (mapv r/yakka-to-ten [15 5.9 10.1 21 25 26 56.4 193]) [1 1 1 2 2 3 6 19])))

(test "一部負担金 rounds to 10 円 (四捨五入)"
  (fn [] (a/deepEqual (mapv r/round-ichibu-futan [873 875 1080 1084 1086]) [870 880 1080 1080 1090])))

(test "outpatient basic: 361 点, 3610 円, 1080 円 (iryo test-compute-outpatient-basic)"
  (fn [] (let [x (calc (fs/readFileSync "test/fixtures/rezept-outpatient.txt" "utf8") {:futan-wari 0.3})]
    (a/equal (:total-ten x) 361)
    (a/deepEqual (:kubun-totals x) [{:kubun "初診" :ten 291} {:kubun "検査" :ten 70}])
    (a/equal (:total-iryohi-yen x) 3610) (a/equal (:ichibu-futan-yen x) 1080)
    (a/equal (:kogaku-applied x) false) (a/equal (:patient-pay-yen x) 1080)
    (a/deepEqual (:observations x) []))))

(test "drug: カロナール 5.9 円 × 3 錠 = 17.7 円/日 → 2 点 × 5 日 = 10 点"
  (fn [] (let [x (calc "薬 21 カロナール錠200 17.7 5\n病名 発熱 主" {:futan-wari 0.3}) l (first (:lines x))]
    (a/equal (:unit-ten l) 2) (a/equal (:count l) 5) (a/equal (:ten l) 10)
    (a/deepEqual (:kubun-totals x) [{:kubun "投薬" :ten 10}]))
  ;; 100×2 + 250×1 = 450 円/日 → 45 点 × 3 日; 12 円 → 1 点 × 2 日
  (a/equal (:total-ten (calc "薬 21 A 450 3\n病名 x 主" {})) 135)
  (a/equal (:total-ten (calc "薬 21 Low 12 2\n病名 x 主" {})) 2)
  (a/equal (:total-ten (calc "薬 21 One 100 0\n病名 x 主" {})) 10)))

(test "act count multiplies, material is 薬価-converted"
  (fn [] (a/equal (:total-ten (calc "行為 70 胸部単純X線 210 2\n病名 x 主" {})) 420)
    (let [x (calc "材 膀胱留置カテーテル 561\n病名 x 主" {})]
      (a/equal (:ten (first (:lines x))) 56) (a/deepEqual (:kubun-totals x) [{:kubun "処置" :ten 56}]))))

(test "高額療養費 オ caps 37800 円 at 35400 円; under the cap nothing changes"
  (fn [] (let [x (calc "行為 70 胸部単純X線 210 60\n病名 x 主" {:futan-wari 0.3 :kogaku-kubun "オ"})]
    (a/equal (:total-iryohi-yen x) 126000) (a/equal (:ichibu-futan-yen x) 37800)
    (a/equal (:kogaku-limit-yen x) 35400) (a/equal (:kogaku-applied x) true) (a/equal (:patient-pay-yen x) 35400))
  (let [x (calc "行為 11 初診料 291\n病名 x 主" {:futan-wari 0.3 :kogaku-kubun "ウ"})]
    (a/equal (:kogaku-applied x) false) (a/equal (:patient-pay-yen x) (:ichibu-futan-yen x)))))

(test "kogaku limits match iryo test_kogaku"
  (fn [] (a/equal (r/kogaku-limit 900000 "ア" nil false) (+ 252600 580))
    (a/equal (r/kogaku-limit 1000000 "ウ" 45 false) 87430)
    (a/equal (r/kogaku-limit 5000000 "エ" nil false) 57600)
    (a/equal (r/kogaku-limit 900000 "現役1" nil false) (+ 80100 6330))
    (a/equal (r/kogaku-limit 500000 "一般" 80 true) 18000)
    (a/equal (r/kogaku-limit 500000 "一般" nil false) 57600)
    (a/equal (r/kogaku-limit 500000 "低所得Ⅱ" nil true) 8000)
    (a/equal (r/kogaku-limit 500000 "低1" nil false) 15000)
    (a/ok (nil? (r/kogaku-limit 100000 "Z" nil false)))
    (a/ok (nil? (r/kogaku-limit 100000 nil 45 false)))))

(test "負担割合 from age matches iryo test_insurance; 0 割 means nothing to pay"
  (fn [] (a/deepEqual (mapv r/futan-wari [3 40 72 80]) [0.2 0.3 0.2 0.1])
    (a/equal (r/futan-wari 72 true false) 0.3) (a/equal (r/futan-wari 80 false true) 0.2) (a/equal (r/futan-wari 80 true false) 0.3)
    (a/equal (:futan-wari (calc "行為 12 再診料 75\n病名 x 主" {:age 80})) 0.1)
    (let [x (calc "行為 11 初診料 291\n病名 x 主" {:futan-wari 0})] (a/equal (:patient-pay-yen x) 0))))

(test "observations: 病名なし投薬, 主病名なし, 空レセプト, unknown 区分 (observed, never adjudicated)"
  (fn [] (a/deepEqual (:observations (calc "薬 21 A 100 1" {})) ["no-diagnosis" "drug-without-diagnosis"])
    (a/deepEqual (:observations (calc "行為 12 再診料 75\n病名 感冒" {})) ["no-main-diagnosis"])
    (a/deepEqual (:observations (calc "病名 感冒 主" {})) ["empty-rezept"])
    (a/deepEqual (:observations (calc "行為 12 再診料 75\n病名 感冒 主" {:kogaku-kubun "Z"})) ["kogaku-kubun-unknown"])
    (a/equal (:status (calc "病名 感冒 主" {})) "draft")))

(test "negative controls: malformed lines are refused with the line number"
  (fn [] (a/equal (reason #(r/parse "")) "input-empty")
    (a/equal (reason #(r/parse "行為 99 謎 10")) "line-shape")
    (a/equal (:line (try (r/parse "行為 12 再診料 75\n行為 12 再診料 -5") (catch :default e (ex-data e)))) 2)
    (a/equal (reason #(r/parse "薬 60 検査薬 10 1")) "line-shape")
    (a/equal (reason #(r/parse "行為 12 再診料 75.5")) "line-shape")
    (a/equal (reason #(r/parse "行為 12 再診料 75 0")) "line-shape")
    (a/equal (reason #(r/parse "病名 感冒 確定")) "line-shape")
    (a/equal (reason #(r/parse "請求 送信")) "line-shape")
    (a/equal (reason #(r/parse (apply str (repeat 81 "行為 12 再診料 75\n")))) "too-many-lines")
    (a/equal (reason #(r/parse (apply str (repeat 700 "病名 感冒\n")))) "input-too-long")
    (a/equal (count (r/parse "# comment\n\n行為 12 再診料 75")) 1)))
