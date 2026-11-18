(ns karute.rezept
  "レセプト (診療報酬請求) draft for the iryo billing bot, done on the user's device.

  The arithmetic is ported unchanged from cloud-itonami/iryo at
  18cf0f809a0df5b4ae9b444c21bdf89214f16f35 (src/iryo/methods/rezept.cljk,
  insurance.cljk, kogaku.cljk): 診療識別 → 区分, 薬剤料 五捨五超入 (≤15 円は 1 点),
  1 点 = 10 円, 一部負担金 10 円未満四捨五入, 高額療養費 自己負担限度額.

  Unlike iryo, this app carries no 点数 master: the person enters each line's
  点数 or 薬価 themselves, and nothing is checked against the 厚労省 master.
  Outpatient only (no 入院, 食事, 公費). Validation only observes; it approves
  or rejects nothing, and the 審査支払機関 and the clinic decide. Nothing here
  sends or stores the input."
  (:require [clojure.string :as str]))

(def max-input-bytes 6000)
(def max-lines 80)
(def tensu-tanka-yen 10)

(def shikibetsu-kubun
  {"11" "初診" "12" "再診" "13" "医学管理" "14" "在宅"
   "21" "投薬" "22" "投薬" "23" "投薬" "24" "投薬"
   "25" "投薬" "26" "投薬" "27" "投薬" "28" "投薬"
   "31" "注射" "32" "注射" "33" "注射"
   "40" "処置" "50" "手術" "54" "麻酔"
   "60" "検査" "64" "病理" "70" "画像診断"
   "80" "その他" "90" "入院"})

(def kubun-order
  ["初診" "再診" "医学管理" "在宅" "投薬" "注射"
   "処置" "手術" "麻酔" "検査" "病理" "画像診断" "その他" "入院"])

(defn- floor [x] #?(:clj (long (Math/floor x)) :cljs (js/Math.floor x)))
(defn- round [x] #?(:clj (Math/round (double x)) :cljs (js/Math.round x)))
(defn- utf8-bytes [s]
  #?(:clj (count (.getBytes ^String s "UTF-8"))
     :cljs (.-length (.encode (js/TextEncoder.) s))))
(defn- refuse [reason data] (throw (ex-info reason (assoc data :reason reason))))

;; ---- iryo arithmetic (unchanged rules) --------------------------------------

(defn yakka-to-ten
  "薬価 (円) → 点. 15 円以下は 1 点; それ以上は 10 で割り 1 点未満を五捨五超入."
  [price-yen]
  (if (<= price-yen 15)
    1
    (let [q (/ price-yen 10.0) f (floor q) frac (- q f)]
      (if (<= frac (+ 0.5 1e-9)) f (+ f 1)))))

(defn round-ichibu-futan [yen]
  (* (floor (/ (+ (round yen) 5) 10)) 10))

(defn age-kubun [age]
  (cond (< age 6) "乳幼児" (< age 70) "成人" (< age 75) "前期高齢" :else "後期高齢"))

(defn futan-wari
  ([age] (futan-wari age false false))
  ([age gen-eki ittei-ijo]
   (case (age-kubun age)
     "乳幼児" 0.2
     "成人" 0.3
     "前期高齢" (if gen-eki 0.3 0.2)
     (cond gen-eki 0.3 ittei-ijo 0.2 :else 0.1))))

(def ^:private u70
  {"ア" {:base 252600 :threshold 842000} "イ" {:base 167400 :threshold 558000}
   "ウ" {:base 80100 :threshold 267000} "エ" {:flat 57600} "オ" {:flat 35400}})
(def ^:private o70-geneki
  {"現役3" {:base 252600 :threshold 842000} "現役2" {:base 167400 :threshold 558000}
   "現役1" {:base 80100 :threshold 267000}})
(def ^:private o70-flat
  {"一般" {:gairai 18000 :setai 57600} "低2" {:gairai 8000 :setai 24600} "低1" {:gairai 8000 :setai 15000}})
(def ^:private alias-map
  {"現役並みⅢ" "現役3" "現役並み3" "現役3" "現役並みIII" "現役3"
   "現役並みⅡ" "現役2" "現役並み2" "現役2" "現役並みII" "現役2"
   "現役並みⅠ" "現役1" "現役並み1" "現役1" "現役並みI" "現役1"
   "低所得Ⅱ" "低2" "低所得2" "低2" "低所得II" "低2"
   "低所得Ⅰ" "低1" "低所得1" "低1" "低所得I" "低1"})
(def kogaku-kubun-names
  ["ア" "イ" "ウ" "エ" "オ" "現役3" "現役2" "現役1" "一般" "低2" "低1"])

(defn- progressive [total {:keys [base threshold]}]
  (+ base (floor (/ (max 0 (- total threshold)) 100))))

(defn kogaku-limit
  "自己負担限度額 (円) or nil for an unknown 区分. age decides 70 歳未満/以上 when the
  区分 exists in both; gairai-only picks the 外来 (個人) cap for 一般/低所得."
  [total kubun age gairai-only]
  (when kubun
    (let [k (get alias-map kubun kubun)
          o70 (or (contains? o70-geneki k) (contains? o70-flat k))
          u (contains? u70 kubun)
          use-o70 (cond (and age (>= age 70) o70) true (and age (< age 70) u) false o70 true :else false)]
      (cond
        (and use-o70 (get o70-geneki k)) (progressive total (get o70-geneki k))
        (and use-o70 (get o70-flat k)) (let [{:keys [gairai setai]} (get o70-flat k)] (if gairai-only gairai setai))
        (and u (get-in u70 [kubun :flat])) (get-in u70 [kubun :flat])
        u (progressive total (get u70 kubun))
        :else nil))))

;; ---- input -----------------------------------------------------------------

;; One line per item, fields separated by spaces:
;;   行為 <診療識別> <名称> <点数> [回数]      例: 行為 12 再診料 75
;;   薬 <診療識別> <名称> <1 日分の薬価 円> <日数>  例: 薬 21 カロナール錠200×3 17.7 5
;;   材 <名称> <薬価 円> [数量]                例: 材 膀胱留置カテーテル 561
;;   病名 <名称> [主|疑い]                     例: 病名 急性上気道炎 主
(defn- tokens [line] (vec (remove str/blank? (str/split (str/trim line) #"[ \t　]+"))))
(defn- number [s] (when (and (string? s) (re-matches #"\d+(\.\d+)?" s)) #?(:clj (Double/parseDouble s) :cljs (js/Number s))))
(defn- whole [s] (when (and (string? s) (re-matches #"\d+" s)) (number s)))
(defn- name-ok? [s] (and (string? s) (<= 1 (count s) 60)))

(defn parse-line [n line]
  (let [[kind & args :as ts] (tokens line) bad (fn [] (refuse "line-shape" {:line n}))]
    (case kind
      "行為" (let [[sk nm ten cnt] args]
               (when-not (and (contains? shikibetsu-kubun sk) (name-ok? nm) (whole ten) (<= (count ts) 5)
                              (or (nil? cnt) (and (whole cnt) (pos? (whole cnt)))))
                 (bad))
               {:kind "act" :shikibetsu sk :name nm :unit-ten (whole ten) :count (or (whole cnt) 1)})
      "薬" (let [[sk nm yakka days] args]
             (when-not (and (contains? shikibetsu-kubun sk) (str/starts-with? sk "2") (name-ok? nm) (number yakka)
                            (= 5 (count ts)) (whole days))
               (bad))
             {:kind "drug" :shikibetsu sk :name nm :yakka-per-day (number yakka) :days (max 1 (whole days))})
      "材" (let [[nm yakka amount] args]
             (when-not (and (name-ok? nm) (number yakka) (<= (count ts) 4) (or (nil? amount) (number amount))) (bad))
             {:kind "material" :shikibetsu "40" :name nm :yakka (number yakka) :amount (or (number amount) 1)})
      "病名" (let [[nm flag] args]
               (when-not (and (name-ok? nm) (<= (count ts) 3) (contains? #{nil "主" "疑い"} flag)) (bad))
               {:kind "diagnosis" :name nm :main (= "主" flag) :suspected (or (= "疑い" flag) (str/includes? nm "疑"))})
      (bad))))

(defn parse [text]
  (cond
    (not (string? text)) (refuse "not-text" {})
    (str/blank? text) (refuse "input-empty" {})
    (> (utf8-bytes text) max-input-bytes) (refuse "input-too-long" {}))
  (let [lines (->> (str/split-lines text) (map-indexed (fn [i l] [(inc i) l]))
                   (remove (fn [[_ l]] (or (str/blank? l) (str/starts-with? (str/trim l) "#")))))]
    (when (> (count lines) max-lines) (refuse "too-many-lines" {}))
    (mapv (fn [[n l]] (parse-line n l)) lines)))

;; ---- compute ---------------------------------------------------------------

(defn- kubun-of [sk] (get shikibetsu-kubun sk "その他"))

(defn compute
  "patient: {:age n|nil :futan-wari 0..0.3|nil :gen-eki bool :ittei-ijo bool :kogaku-kubun str|nil}"
  [items patient]
  (let [acts (filterv #(= "act" (:kind %)) items)
        drugs (filterv #(= "drug" (:kind %)) items)
        mats (filterv #(= "material" (:kind %)) items)
        dx (filterv #(= "diagnosis" (:kind %)) items)
        lines (vec (concat
                    (map (fn [a] {:kind "act" :kubun (kubun-of (:shikibetsu a)) :shikibetsu (:shikibetsu a) :name (:name a)
                                  :unit-ten (:unit-ten a) :count (:count a) :ten (* (:unit-ten a) (:count a))}) acts)
                    (map (fn [d] (let [u (yakka-to-ten (:yakka-per-day d))]
                                   {:kind "drug" :kubun (kubun-of (:shikibetsu d)) :shikibetsu (:shikibetsu d) :name (:name d)
                                    :unit-ten u :count (:days d) :ten (* u (:days d))})) drugs)
                    (map (fn [m] (let [t (yakka-to-ten (* (:yakka m) (:amount m)))]
                                   {:kind "material" :kubun (kubun-of (:shikibetsu m)) :shikibetsu (:shikibetsu m) :name (:name m)
                                    :unit-ten t :count 1 :ten t})) mats)))
        totals (reduce (fn [acc l] (assoc acc (:kubun l) (+ (get acc (:kubun l) 0) (:ten l)))) {} lines)
        total-ten (reduce + 0 (map :ten lines))
        age (:age patient)
        wari (cond (number? (:futan-wari patient)) (:futan-wari patient)
                   (number? age) (futan-wari age (boolean (:gen-eki patient)) (boolean (:ittei-ijo patient)))
                   :else 0.3)
        iryohi (* total-ten tensu-tanka-yen)
        ichibu (round-ichibu-futan (* iryohi wari))
        limit (kogaku-limit iryohi (:kogaku-kubun patient) age true)
        applied (boolean (and limit (> ichibu limit)))]
    {:lines lines
     :kubun-totals (vec (keep (fn [k] (when (contains? totals k) {:kubun k :ten (get totals k)})) kubun-order))
     :total-ten total-ten
     :total-iryohi-yen iryohi
     :futan-wari wari
     :ichibu-futan-yen ichibu
     :kogaku-limit-yen limit
     :kogaku-applied applied
     :patient-pay-yen (if applied limit ichibu)
     :diagnoses dx
     :observations (cond-> []
                     (empty? lines) (conj "empty-rezept")
                     (empty? dx) (conj "no-diagnosis")
                     (and (seq dx) (not (some :main dx))) (conj "no-main-diagnosis")
                     (and (seq drugs) (empty? dx)) (conj "drug-without-diagnosis")
                     (and (:kogaku-kubun patient) (nil? limit)) (conj "kogaku-kubun-unknown"))
     :status "draft"}))

(defn diagnosis-lines
  "Lines for the レセプト input from a SOAP check's A section (karute → rezept, on device)."
  [diagnoses]
  (str/join "\n" (map (fn [d] (str "病名 " (str/replace (:name d) #"[ \t　]+" "")
                                   (cond (:main d) " 主" (:suspected d) " 疑い" :else "")))
                      diagnoses)))
