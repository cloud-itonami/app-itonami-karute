(ns karute.draft
  "SOAP summary draft through Murakumo, on the same device (AWAI-3396).

  The note is PHI. It leaves page memory only as one Murakumo prompt, after the
  person confirms in Karute and again in Murakumo's own native dialog. There is
  no remote fallback: the pinned awai-inference client refuses outside Android
  and Murakumo refuses a caller whose package or signature is not admitted.
  The draft is shown only; it is never stored or sent, and it is not a
  clinical record or a diagnosis."
  (:require [clojure.string :as str]
            [karute.soap :as soap]))

;; Murakumo accepts prompts up to 8192 bytes. The note part is capped below
;; that so the instructions and section labels always fit.
(def max-note-bytes 6000)
(def max-prompt-bytes 8000)
(def max-tokens 256)
(def max-draft-chars 2000)

(defn- utf8-bytes [s]
  #?(:clj (count (.getBytes ^String s "UTF-8"))
     :cljs (.-length (.encode (js/TextEncoder.) s))))

(defn- refuse [reason] (throw (ex-info reason {:reason reason})))
(defn- int-value? [x] #?(:clj (integer? x) :cljs (js/Number.isSafeInteger x)))
(defn- seq-value? [x] #?(:clj (sequential? x) :cljs (js/Array.isArray x)))

(defn note-problem [text]
  (or (soap/note-problem text)
      (when (> (utf8-bytes text) max-note-bytes) "note-too-long-for-draft")))

(defn prompt
  "One prompt from the note and its structure check. The check result is
  included so the model is told which sections are missing rather than
  guessing; it is asked not to add findings or diagnoses."
  [text check]
  (when-let [p (note-problem text)] (refuse p))
  (let [missing (:missing check)
        out-of-range (->> (:vitals check) (remove #(= "within-reference" (:status %)))
                          (map (fn [v] (str (:id v) " " (:text v)))))
        p (str "次の診療記録（SOAP）を、医師が読み返すための3〜5行の日本語の要約にしてください。"
               "記録に書かれていないこと（新しい所見、診断、薬、数値）を加えないでください。"
               "診断や治療の判断はしないでください。\n"
               (when (seq missing) (str "記載がない見出し: " (str/join "、" missing) "\n"))
               (when (seq out-of-range) (str "参考範囲外または要確認の値: " (str/join "、" out-of-range) "\n"))
               "--- 記録 ---\n" (str/trim text) "\n--- ここまで ---")]
    (when (> (utf8-bytes p) max-prompt-bytes) (refuse "prompt-too-long"))
    p))

(defn readiness
  "Map the inference client's info() result to one state the UI can show."
  [info]
  (let [st (:status info)]
    (cond
      (not (true? (:installed info))) "not-installed"
      (not (false? (:remote info))) "refused-remote"
      (nil? st) "status-unknown"
      (not (true? (:imported st))) "model-not-imported"
      (not (true? (:loaded st))) "model-not-loaded"
      (true? (:busy st)) "busy"
      :else "ready")))

(defn draft
  "Validate a generate() result. The client already binds request id, engine
  and modality; this keeps the rule independent of the client."
  [result]
  (let [text (:text result)]
    (when-not (and (= "awai.inference.result.v1" (:schema result)) (= "llama.cpp" (:engine result))
                   (= "text" (:modality result)) (string? text))
      (refuse "invalid-draft"))
    (let [clean (str/trim (str/replace text #"(?s)<think>.*?</think>" ""))]
      (when (str/blank? clean) (refuse "empty-draft"))
      {:text (subs clean 0 (min max-draft-chars (count clean))) :engine "llama.cpp" :status "draft"})))

(def observation-gloss
  {"empty-rezept" "空レセプト（算定する行がありません）"
   "no-diagnosis" "病名がありません"
   "no-main-diagnosis" "主病名がありません"
   "multiple-main-diagnoses" "主病名が複数あります"
   "drug-without-diagnosis" "病名なしの投薬があります"
   "kogaku-kubun-unknown" "高額療養費の区分を確認できません"})

(defn explanation-problem
  "Bounds for the 算定案 input handed to Murakumo. Mirrors the rezept input
  limits without depending on that namespace."
  [text]
  (cond
    (not (string? text)) "not-text"
    (str/blank? text) "input-empty"
    (> (utf8-bytes text) 6000) "input-too-long"
    :else nil))

(defn explanation-prompt
  "One prompt explaining a computed claim draft. The claim lines hold 病名,
  so this is PHI like the note: it goes to Murakumo only after the person's
  confirmation and Murakumo's own native confirmation, with no remote fallback.
  The model is asked to explain the computed amounts, not to add amounts or
  names and not to judge the claim."
  [claim-text result]
  (when-let [p (explanation-problem claim-text)] (refuse p))
  (when-not (and (map? result) (= "draft" (:status result))
                 (int-value? (:total-ten result)) (int-value? (:total-iryohi-yen result))
                 (int-value? (:ichibu-futan-yen result)) (int-value? (:patient-pay-yen result))
                 (number? (:futan-wari result)) (seq-value? (:kubun-totals result))
                 (seq-value? (:observations result)) (seq-value? (:diagnoses result)))
    (refuse "no-computed-claim"))
  (let [kubun (if (empty? (:kubun-totals result)) "なし"
                  (str/join "、" (map (fn [k] (str (:kubun k) " " (:ten k) "点")) (:kubun-totals result))))
        dx (:diagnoses result)
        names (if (empty? dx) "なし"
                  (str/join "、" (map (fn [d] (str (:name d) (when (:main d) "（主）") (when (:suspected d) "（疑い）"))) dx)))
        obs (if (empty? (:observations result)) "なし"
                (str/join "、" (map (fn [o] (get observation-gloss o o)) (:observations result))))
        wari #?(:clj (Math/round (* 10.0 (double (:futan-wari result))))
                  :cljs (js/Math.round (* 10 (:futan-wari result))))
        p (str "次のレセプト算定案（外来のドラフト。請求の送信はしません）を、確認のための3〜5行の日本語で説明してください。"
               "書かれていない金額や病名を加えないでください。査定や請求の可否は判断しないでください。\n"
               "区分: " kubun "\n"
               "合計: 総点数 " (:total-ten result) "点、総医療費 " (:total-iryohi-yen result) "円、一部負担金（" wari "割） "
               (:ichibu-futan-yen result) "円、窓口負担 " (:patient-pay-yen result) "円\n"
               "病名: " names "\n"
               "観察: " obs "\n"
               "--- ここまで ---")]
    (when (> (utf8-bytes p) max-prompt-bytes) (refuse "prompt-too-long"))
    p))

(defn error-text
  "Tauri rejects with a plain string; JS code rejects with an Error. Read both."
  [e]
  (or (:reason (ex-data e))
      (when (string? e) e)
      #?(:cljs (when (some? e) (.-message e)) :clj (when (instance? Throwable e) (.getMessage ^Throwable e)))
      "unknown"))

(defn denied? [msg] (and (string? msg) (str/includes? msg "denied")))
