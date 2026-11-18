(ns karute.soap
  "SOAP note structure check for the 8620 clinical-practice bot, done on the
  user's device. It finds the S/O/A/P sections, reads common vital signs from
  the text and checks them against general adult reference ranges, and lists
  the problem names written in A so they can be carried to the レセプト form.

  It never diagnoses or rates care. The note is PHI: nothing here sends or
  stores it, and the app keeps it in memory only."
  (:require [clojure.string :as str]))

(def max-note-bytes 8000)
(def max-diagnoses 20)
(def sections ["S" "O" "A" "P"])

(defn- utf8-bytes [s]
  #?(:clj (count (.getBytes ^String s "UTF-8"))
     :cljs (.-length (.encode (js/TextEncoder.) s))))

(defn note-problem [text]
  (cond
    (not (string? text)) "not-text"
    (str/blank? text) "note-empty"
    (> (utf8-bytes text) max-note-bytes) "note-too-long"
    :else nil))

(defn- refuse [reason] (throw (ex-info reason {:reason reason})))

;; Full-width digits, letters and punctuation are folded so 「Ｓ：」「ＢＰ１３０／８０」 read
;; the same as their ASCII forms.
(def ^:private punct {"：" ":" "／" "/" "．" "." "％" "%" "　" " " "（" "(" "）" ")"})

(defn- fold-char [c]
  (let [code #?(:clj (int (first c)) :cljs (.charCodeAt c 0))]
    (cond
      (or (<= 0xFF10 code 0xFF19) (<= 0xFF21 code 0xFF3A) (<= 0xFF41 code 0xFF5A))
      #?(:clj (str (char (- code 0xFEE0))) :cljs (js/String.fromCharCode (- code 0xFEE0)))
      :else (get punct c c))))

(defn fold [s]
  (apply str (map (fn [c] (fold-char (str c))) s)))

;; A section header is a line that starts with S, O, A or P, optionally in brackets,
;; followed by ':' ')' ']' or a closing bracket: 「S:」「(O)」「【A】」「P）」.
(def header-re #"^\s*[\[(（【〔]?\s*([SOAPsoap])\s*(?:[\])）】〕]\s*:?|:)\s*(.*)$")

(defn split-sections
  "Returns {\"S\" text ...}. Text before the first header is kept under \"pre\"."
  [text]
  (loop [lines (str/split-lines (fold text)) current "pre" acc {}]
    (if (empty? lines)
      (into {} (map (fn [[k v]] [k (str/trim v)]) acc))
      (let [line (first lines)
            m (re-matches header-re line)]
        (if m
          (let [k (str/upper-case (nth m 1))]
            (recur (rest lines) k (assoc acc k (str (get acc k "") (nth m 2) "\n"))))
          (recur (rest lines) current (assoc acc current (str (get acc current "") line "\n"))))))))

(defn- num [s] (when s (let [n #?(:clj (Double/parseDouble s) :cljs (js/Number s))] (when-not #?(:clj (Double/isNaN n) :cljs (js/Number.isNaN n)) n))))

;; Reference ranges are general adult values, used only to point at a number a
;; person should look at again. Plausible bounds catch typos (体温 365).
(def vital-specs
  [{:id "temperature" :re #"(?:体温|BT|KT)\s*:?\s*(\d{2,3}(?:\.\d)?)" :unit "℃" :plausible [30 43] :reference [35.0 37.4]}
   {:id "pulse" :re #"(?:脈拍|HR|PR)\s*:?\s*(\d{2,3})" :unit "/min" :plausible [20 250] :reference [50 100]}
   {:id "spo2" :re #"(?:SpO2|SPO2|spo2)\s*:?\s*(\d{2,3})" :unit "%" :plausible [50 100] :reference [95 100]}
   {:id "respiration" :re #"(?:呼吸数|RR)\s*:?\s*(\d{1,2})" :unit "/min" :plausible [4 60] :reference [12 20]}])

(def bp-re #"(?:血圧|BP)\s*:?\s*(\d{2,3})\s*/\s*(\d{2,3})")

(defn- status [v [plo phi] [rlo rhi]]
  (cond (or (< v plo) (> v phi)) "implausible"
        (or (< v rlo) (> v rhi)) "outside-reference"
        :else "within-reference"))

(defn vitals [text]
  (let [t (fold text)
        simple (keep (fn [{:keys [id re unit plausible reference]}]
                       (when-let [m (re-find re t)]
                         (let [v (num (nth m 1))]
                           {:id id :value v :text (nth m 1) :unit unit :status (status v plausible reference)})))
                     vital-specs)
        bp (when-let [m (re-find bp-re t)]
             (let [sys (num (nth m 1)) dia (num (nth m 2))]
               {:id "blood-pressure" :value sys :diastolic dia :text (str (nth m 1) "/" (nth m 2)) :unit "mmHg"
                :status (cond (or (< sys 50) (> sys 260) (< dia 20) (> dia 160) (<= sys dia)) "implausible"
                              (or (>= sys 140) (>= dia 90) (< sys 90)) "outside-reference"
                              :else "within-reference")}))]
    (vec (if bp (concat simple [bp]) simple))))

;; Problem names in A: 「#1 急性上気道炎 #2 高血圧症」, one per line, or separated by
;; 、 ・ , ;. A name containing 疑い is a suspected diagnosis (疑い病名).
(defn diagnoses [a-text]
  (->> (str/split (or a-text "") #"#\s*\d*|\n|、|・|,|;|；")
       (map (fn [s] (str/trim (str/replace s #"^\d+[.)）]\s*" ""))))
       (remove str/blank?)
       (filter (fn [s] (<= (count s) 60)))
       (take max-diagnoses)
       (map-indexed (fn [i s] {:name s :main (zero? i) :suspected (str/includes? s "疑")}))
       vec))

(defn check
  "Structure check of one note. Throws {:reason ...} for input that cannot be checked."
  [text]
  (when-let [p (note-problem text)] (refuse p))
  (let [secs (split-sections text)
        missing (filterv (fn [k] (str/blank? (get secs k ""))) sections)
        vs (vitals (str (get secs "O" "") "\n" (get secs "pre" "")))
        dx (diagnoses (get secs "A"))]
    {:sections (mapv (fn [k] {:id k :present (not (str/blank? (get secs k ""))) :chars (count (get secs k ""))}) sections)
     :missing missing
     :vitals vs
     :diagnoses dx
     :observations (cond-> []
                     (seq missing) (conj "section-missing")
                     (some #(= "implausible" (:status %)) vs) (conj "vital-implausible")
                     (some #(= "outside-reference" (:status %)) vs) (conj "vital-outside-reference")
                     (and (not (str/blank? (get secs "P" ""))) (empty? dx)) (conj "plan-without-assessment"))
     :status "draft"}))
