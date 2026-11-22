(ns karute.app
  (:require [karute.domain :as d] [karute.soap :as soap] [karute.rezept :as rezept] [karute.draft :as draft] [karute.handoff :as handoff] ["@awai/inference-client" :refer [createInferenceClient]] ["@awai/ui" :as ui] [clojure.string :as str]
            ["@awai/client-runtime" :as runtime] ["@awai/profile-8620-text$default" :as profile-8620-text] ["@awai/profile-iryo-text$default" :as profile-iryo-text]))

;; PHI (the note and the レセプト lines) lives only in this atom: no storage, no send.
(defonce state (atom {:locale "ja" :notice "" :assignment nil :events nil :busy false :read-at nil
                      :blueprint "cloud-itonami-isic-8620" :note "" :soap nil
                      :readiness nil :draft-confirm false :drafting false :draft nil
                      :handoff nil :handoff-confirm false
                      :rezept-text "" :age "" :wari "" :kogaku "" :rezept nil
                      :expl-confirm false :explaining false :expl nil}))
(def node ui/node)
(declare render!)

(def copy
  {:ja {:title "Karute" :screen-note "患者の情報を守るため、この app は画面の撮影と共有ができません" :lead "電子カルテとレセプトの仕事を bot に任せる。その bot の状態と、手元の記録の確認を、端末の中で行う。"
        :mission "使命" :blueprint "担当する経済領域" :governor "統制" :money "使えるお金" :money-text "{min}〜{max} micro-USDC の貸付枠のみ（譲渡不可・オンチェーン token なし）。使える先: {services}"
        :registry {"not-registered" "正本 registry には未登録です（提案中の profile）。" "registered" "正本 registry に登録済み。"}
        :phi "患者情報（PHI）" :read-assignments "bot の状態を取得" :read-activity "活動を取得" :language "言語"
        :consent-note "取得のたびに確認画面が開きます。拒否すると何も送信しません。患者情報は送りません。自動更新はしません。"
        :endpoint "接続先" :health "健康状態" :not-read "まだ取得していません。"
        :not-launched "この bot はまだ起動していません。" :not-launched-detail "公開 assignment 一覧（{others} 件）にこの blueprint はありません。起動は運用者の確認が必要です。"
        :healthy "健康" :unhealthy "不健康" :unknown "不明（活動を未取得）"
        :status "状態" :ticks "実行回数" :checkpoint "最新 checkpoint" :none "なし" :credit "貸付枠 / 負債"
        :events "公開活動（新しい順、最大 {n} 件）" :no-events "活動はありません。"
        :denied "取得を中止しました。何も送信していません。" :failed "取得できません: " :fetched "取得しました。"
        :reasons {"bot-not-launched" "bot が未起動" "no-checkpoint" "checkpoint がない" "checkpoint-stale" "checkpoint が古い（間隔の 2 倍超）"
                  "activity-not-read" "活動を未取得" "no-activity" "活動がない" "latest-event-error" "最新の活動がエラー"}
        :profile-invalid "同梱の bot profile を確認できないため、表示を停止しました。"
        :read-at "最終取得" :source "出典" :bot "bot"
        :soap-title "診療記録（SOAP）の確認（端末内）" :note-label "SOAP 記録（S: O: A: P: の見出しで区切る。8000 bytes まで）" :soap-btn "記録を確認"
        :section {"S" "S 主観" "O" "O 客観" "A" "A 評価" "P" "P 計画"} :present "記載あり" :absent "記載なし"
        :vitals "バイタル" :no-vitals "読み取れるバイタルはありません。"
        :vital-names {"temperature" "体温" "pulse" "脈拍" "spo2" "SpO2" "respiration" "呼吸数" "blood-pressure" "血圧"}
        :vital-status {"within-reference" "参考範囲内" "outside-reference" "参考範囲外" "implausible" "値を確認（入力誤りの可能性）"}
        :dx "A の病名" :no-dx "A から病名を読み取れません。" :main "主" :suspected "疑い"
        :observations {"section-missing" "欠けている見出しがあります" "vital-implausible" "ありえない値のバイタルがあります" "vital-outside-reference" "参考範囲外のバイタルがあります"
                       "plan-without-assessment" "P があるのに A に病名がありません" "empty-rezept" "算定する行がありません（空レセプト）" "no-diagnosis" "病名がありません"
                       "no-main-diagnosis" "主病名がありません" "multiple-main-diagnoses" "主病名が複数あります" "drug-without-diagnosis" "病名なしの投薬があります" "kogaku-kubun-unknown" "高額療養費の区分を確認できません"}
        :soap-note "記載の有無と一般的な成人の参考範囲を機械的に確かめるだけで、診断や診療の評価ではありません。判断は医師が行います。記録は端末の外へ送らず、保存もしません。"
        :draft-title "要約の下書き（Murakumo・端末内）" :draft-btn "要約の下書きを作る（端末内の推論）" :open-murakumo "Murakumo を開く"
        :draft-confirm-title "この端末の Murakumo に記録を渡しますか？"
        :draft-confirm-text "SOAP 記録の本文（患者情報）を、この端末の Murakumo（network.awai.murakumo）に 1 回だけ渡して要約の下書きを作ります。端末の外へは送りません。Murakumo が次に自分の確認画面を出します。どちらかで拒否すると推論は実行されません。"
        :draft-go "Murakumo に渡す" :draft-cancel "やめる" :draft-cancelled "やめました。記録は渡していません。"
        :draft-note "小さなモデル（0.6B）の下書きで、誤りや記録にない内容を含むことがあります。診療録ではなく、診断でもありません。保存も送信もしません。必ず元の記録で確かめてください。"
        :draft-denied "下書きを中止しました。推論は実行していません。" :draft-failed "下書きを作れません: "
        :readiness {"ready" "端末内の推論を使えます。" "not-installed" "Murakumo が入っていないため使えません。" "refused-remote" "remote 推論の申告があるため使いません。"
                    "status-unknown" "Murakumo の状態を確認できません。" "model-not-imported" "Murakumo にモデルが入っていません。Murakumo で取り込んでください。"
                    "model-not-loaded" "Murakumo でモデルを読み込んでください。" "busy" "Murakumo が他の処理中です。" "unavailable" "この環境では端末内の推論を使えません（Android の Karute のみ）。"}
        :to-rezept "病名をレセプトへ渡す" :carried "A の病名をレセプトの入力に加えました（端末内）。"
        :handoff-title "病名の受け渡しの確認"
        :handoff-text "A の病名をレセプトの入力に加えます。端末内のメモリでの受け渡しで、送信も保存もしません。署名はつきません（member 署名の検証は今後の対応です）。5 分以内に確定してください。"
        :handoff-go "レセプトへ渡す" :handoff-cancel "やめる" :handoff-cancelled "やめました。病名は渡していません。"
        :expl-title "算定案の説明の下書き（Murakumo・端末内）" :expl-btn "説明の下書きを作る（端末内の推論）"
        :expl-confirm-title "この端末の Murakumo に算定案を渡しますか？"
        :expl-confirm-text "算定結果の集計（区分・合計・負担額・観察）と病名を、この端末の Murakumo（network.awai.murakumo）に 1 回だけ渡して説明の下書きを作ります。元の算定行そのままは渡さず、端末の外へも送りません。Murakumo が次に自分の確認画面を出します。どちらかで拒否すると推論は実行されません。"
        :expl-go "Murakumo に渡す" :expl-cancel "やめる" :expl-cancelled "やめました。算定案は渡していません。"
        :expl-note "小さなモデル（0.6B）の下書きで、誤りや記録にない内容を含むことがあります。査定ではなく、請求の送信もしません。保存も送信もしません。必ず算定結果と元の記録で確かめてください。"
        :rezept-title "レセプトの算定案（端末内）" :rezept-label "算定行（1 行 1 件）"
        :rezept-help "行為 <識別> <名称> <点数> [回数] ／ 薬 <識別> <名称> <1日分の薬価円> <日数> ／ 材 <名称> <薬価円> [数量] ／ 病名 <名称> [主|疑い]"
        :age "年齢" :wari "負担割合（空欄は年齢から）" :kogaku "高額療養費の区分" :kogaku-none "なし" :rezept-btn "算定する"
        :kubun "区分" :ten "点" :total-ten "総点数" :iryohi "総医療費" :ichibu "一部負担金（{w} 割）" :kogaku-limit "自己負担限度額" :pay "窓口負担" :yen "円"
        :lines "明細" :obs "観察（査定ではありません）" :no-obs "観察事項はありません。"
        :rezept-note "点数・薬価は入力どおりに使い、厚労省マスターとは照合していません。外来のみ（入院・食事・公費は対象外）。算定案であり、請求の送信はしません。請求の主体は保険医療機関で、審査は審査支払機関が行います。入力は端末の外へ送らず、保存もしません。"
        :errors {"not-text" "入力を確認できません。" "note-empty" "記録を入力してください。" "note-too-long" "記録が 8000 bytes を超えています。" "note-too-long-for-draft" "下書きに使える記録は 6000 bytes までです。" "prompt-too-long" "下書きの指示が長すぎます。"
                 "input-empty" "算定行を入力してください。" "input-too-long" "6000 bytes を超えています。" "too-many-lines" "80 行を超えています。"
                 "line-shape" "{line} 行目の形を確認できません。" "age" "年齢は 0〜120 の整数で入力してください。" "wari" "負担割合は 0、0.1、0.2、0.3 のいずれかです。"
                 "no-computed-claim" "先に「算定する」で算定案を計算してください。" "handoff-expired" "受け渡しの有効期限（5 分）が切れました。もう一度お試しください。"
                 "handoff-changed" "A の病名が変わったため受け渡しを中止しました。もう一度確認してください。"
                 "handoff-refused" "受け渡しを確認できませんでした。"}}
   :en {:title "Karute" :screen-note "To protect patient information, this app cannot be screenshotted or shared on screen." :lead "Hand clinical-record and claim work to bots. Check those bots and your own records on your device."
        :mission "Mission" :blueprint "Economic domain" :governor "Governor" :money "Spending" :money-text "Only a {min}–{max} micro-USDC loan position (non-transferable, no on-chain token). Allowed services: {services}"
        :registry {"not-registered" "Not in the canonical registry yet (proposed profile)." "registered" "In the canonical registry."}
        :phi "Patient data (PHI)" :read-assignments "Read bot status" :read-activity "Read activity" :language "Language"
        :consent-note "Each read opens an approval dialog. Denying sends nothing. No patient data is sent. Nothing refreshes automatically."
        :endpoint "Endpoint" :health "Health" :not-read "Not read yet."
        :not-launched "This bot has not been launched." :not-launched-detail "The public assignment list ({others} entries) does not include this blueprint. Launch needs operator approval."
        :healthy "Healthy" :unhealthy "Unhealthy" :unknown "Unknown (activity not read)"
        :status "Status" :ticks "Ticks" :checkpoint "Latest checkpoint" :none "none" :credit "Credit limit / debt"
        :events "Public activity (newest first, up to {n})" :no-events "No activity."
        :denied "Cancelled. Nothing was sent." :failed "Could not read: " :fetched "Read complete."
        :reasons {"bot-not-launched" "bot not launched" "no-checkpoint" "no checkpoint" "checkpoint-stale" "checkpoint older than 2 intervals"
                  "activity-not-read" "activity not read" "no-activity" "no activity" "latest-event-error" "newest event is an error"}
        :profile-invalid "The bundled bot profile could not be verified, so the view is stopped."
        :read-at "Last read" :source "Source" :bot "Bot"
        :soap-title "SOAP note check (on device)" :note-label "SOAP note (sections headed S: O: A: P:, up to 8000 bytes)" :soap-btn "Check note"
        :section {"S" "S subjective" "O" "O objective" "A" "A assessment" "P" "P plan"} :present "present" :absent "missing"
        :vitals "Vital signs" :no-vitals "No vital signs could be read."
        :vital-names {"temperature" "Temperature" "pulse" "Pulse" "spo2" "SpO2" "respiration" "Respiration" "blood-pressure" "Blood pressure"}
        :vital-status {"within-reference" "within reference" "outside-reference" "outside reference" "implausible" "check the value (possible typo)"}
        :dx "Problems in A" :no-dx "No problem names could be read from A." :main "main" :suspected "suspected"
        :observations {"section-missing" "a section is missing" "vital-implausible" "a vital sign has an implausible value" "vital-outside-reference" "a vital sign is outside the reference range"
                       "plan-without-assessment" "P is written but A names no problem" "empty-rezept" "no billable line (empty claim)" "no-diagnosis" "no diagnosis"
                       "no-main-diagnosis" "no main diagnosis" "multiple-main-diagnoses" "more than one main diagnosis" "drug-without-diagnosis" "a drug is billed without a diagnosis" "kogaku-kubun-unknown" "unknown high-cost category"}
        :soap-note "A mechanical check of which sections are written and of general adult reference ranges. It is not a diagnosis or a rating of care; the physician decides. The note never leaves the device and is not stored."
        :draft-title "Summary draft (Murakumo, on device)" :draft-btn "Draft a summary (on-device inference)" :open-murakumo "Open Murakumo"
        :draft-confirm-title "Give this note to Murakumo on this device?"
        :draft-confirm-text "The SOAP note text (patient data) goes once to Murakumo (network.awai.murakumo) on this device to draft a summary. Nothing leaves the device. Murakumo then shows its own confirmation. Denying either one runs no inference."
        :draft-go "Give it to Murakumo" :draft-cancel "Cancel" :draft-cancelled "Cancelled. The note was not given to Murakumo."
        :draft-note "A draft from a small model (0.6B). It can be wrong or add things that are not in the note. It is not a clinical record or a diagnosis, and it is neither stored nor sent. Check it against the note."
        :draft-denied "Draft cancelled. Inference did not run." :draft-failed "Could not draft: "
        :readiness {"ready" "On-device inference is available." "not-installed" "Murakumo is not installed." "refused-remote" "Refused: the provider reports remote inference."
                    "status-unknown" "Murakumo status is unknown." "model-not-imported" "Murakumo has no model. Import it in Murakumo."
                    "model-not-loaded" "Load the model in Murakumo." "busy" "Murakumo is busy." "unavailable" "On-device inference is not available here (Karute on Android only)."}
        :to-rezept "Carry problems to the claim" :carried "Added the problems from A to the claim input (on device)."
        :handoff-title "Confirm the problem hand-off"
        :handoff-text "Adds the A problems to the claim input. In-memory only on this device: nothing is sent or stored. Unsigned (member-signature verification comes later). Confirm within 5 minutes."
        :handoff-go "Carry to the claim" :handoff-cancel "Cancel" :handoff-cancelled "Cancelled. No problem was carried."
        :expl-title "Claim explanation draft (Murakumo, on device)" :expl-btn "Draft an explanation (on-device inference)"
        :expl-confirm-title "Give this claim draft to Murakumo on this device?"
        :expl-confirm-text "The computed aggregates (sections, totals, patient share, observations) and the problem names go once to Murakumo (network.awai.murakumo) on this device to draft an explanation. The raw claim lines never go, and nothing leaves the device. Murakumo then shows its own confirmation. Denying either one runs no inference."
        :expl-go "Give it to Murakumo" :expl-cancel "Cancel" :expl-cancelled "Cancelled. The claim draft was not given to Murakumo."
        :expl-note "A draft from a small model (0.6B). It can be wrong or add things that are not in the draft. It is not adjudication, and nothing is submitted, stored or sent. Check it against the computed draft and the note."
        :rezept-title "Claim (レセプト) draft (on device)" :rezept-label "Claim lines (one per line)"
        :rezept-help "行為 <code> <name> <points> [count] / 薬 <code> <name> <daily price yen> <days> / 材 <name> <price yen> [amount] / 病名 <name> [主|疑い]"
        :age "Age" :wari "Patient share (blank = from age)" :kogaku "High-cost category" :kogaku-none "none" :rezept-btn "Calculate"
        :kubun "Category" :ten "pt" :total-ten "Total points" :iryohi "Total cost" :ichibu "Patient share ({w}0%)" :kogaku-limit "High-cost cap" :pay "Patient pays" :yen "yen"
        :lines "Lines" :obs "Observations (not adjudication)" :no-obs "No observations."
        :rezept-note "Points and prices are used as entered and are not checked against the MHLW master. Outpatient only (no inpatient, meals or public funds). This is a draft; nothing is submitted. The clinic is the billing principal and the review body decides. The input never leaves the device and is not stored."
        :errors {"not-text" "Input could not be read." "note-empty" "Enter the note." "note-too-long" "The note exceeds 8000 bytes." "note-too-long-for-draft" "A draft takes notes up to 6000 bytes." "prompt-too-long" "The draft prompt is too long."
                 "input-empty" "Enter claim lines." "input-too-long" "Over 6000 bytes." "too-many-lines" "Over 80 lines."
                 "line-shape" "Line {line} could not be read." "age" "Enter the age as a whole number 0–120." "wari" "The share must be 0, 0.1, 0.2 or 0.3."
                 "no-computed-claim" "Calculate the draft with “Calculate” first." "handoff-expired" "The hand-off expired (5 minutes). Try again."
                 "handoff-changed" "The hand-off stopped because the A problems changed. Check again."
                 "handoff-refused" "The hand-off could not be verified."}}})

(defn t [k] (get-in copy [(:locale @state) k]))
(defn fmt [k values] (reduce (fn [s [key v]] (str/replace s (str "{" (name key) "}") (str v))) (t k) values))
(defn time-text [ms] (if ms (.format (js/Intl.DateTimeFormat. (:locale @state) #js{:dateStyle "medium" :timeStyle "short"}) (js/Date. ms)) (t :none)))
(defn yen [n] (.format (js/Intl.NumberFormat. "ja-JP") n))

(def profiles {"cloud-itonami-isic-8620" (js/JSON.parse profile-8620-text)
               "iryo" (js/JSON.parse profile-iryo-text)})
(defn profile [] (get profiles (:blueprint @state)))
;; Every bundled profile must verify against its own blueprint, or the view stops.
(def profile-ok (every? (fn [id] (empty? (d/profile-problems (get profiles id) id))) d/blueprints))

(def client
  (let [desc (d/descriptor)]
    (when (seq (runtime/clientRuntimeProblems desc #js[d/capability] js/location.origin))
      (throw (js/Error. "canonical client runtime refused descriptor")))
    (runtime/createClientOperationBroker
     #js{:runtime desc :requestedCapabilities #js[d/capability] :approvedOperations (:operations desc)
         :hostSupportedOperations (clj->js (map (fn [o] [(:capability o) (:service o) (:operation o)]) (:operations desc)))
         :consentHost (runtime/createClientConsentHost)})))

(defn error-reason [e] (or (:reason (ex-data e)) (when (string? e) e) (.-message e) "unknown"))
(defn error-text [e] (let [r (error-reason e)] (str/replace (get (t :errors) r r) "{line}" (str (:line (ex-data e))))))

(defn bounded-json [response]
  (if-not (.-ok response)
    (js/Promise.reject (js/Error. (str "HTTP " (.-status response))))
    (let [declared (js/Number (.get (.-headers response) "content-length"))]
      (if (and (js/Number.isFinite declared) (> declared d/max-response-bytes))
        (js/Promise.reject (js/Error. "response too large"))
        (.then (.text response) (fn [text] (if (> (count text) d/max-response-bytes) (throw (js/Error. "response too large")) (js/JSON.parse text))))))))

(defn notice! [s] (swap! state assoc :notice s) (render!))

(defn read! [operation url on-body]
  (swap! state assoc :busy true) (render!)
  (-> (.invoke client #js{:capability d/capability :service d/service :operation operation} #js{:input url :init #js{:method "GET"}})
      (.then bounded-json)
      (.then (fn [body] (on-body body) (swap! state assoc :read-at (js/Date.now)) (notice! (t :fetched))))
      (.catch (fn [e] (notice! (if (re-find #"not approved" (str (.-message e))) (t :denied) (str (t :failed) (error-reason e))))))
      (.finally (fn [] (swap! state assoc :busy false) (render!)))))

(defn read-assignments! []
  (read! "read-assignments" (d/assignments-url)
         (fn [body] (swap! state assoc :assignment (d/assignment body (:blueprint @state)) :events nil))))

(defn read-activity! []
  (let [bot-id (get-in @state [:assignment :bot-id])]
    (read! "read-activity" (d/activity-url bot-id)
           (fn [body] (swap! state assoc :events (d/activity body bot-id))))))

(defn profile-card []
  (let [profile (profile) p (:profile profile) bp (:business_blueprint p) m (:money p)]
    (ui/card
     (ui/heading 2 (:name bp))
     [:p {:id "mission"} (str (t :mission) ": " (get-in profile [:mission :statement]))]
     [:dl
      [:dt (t :blueprint)] [:dd (str (:domain bp) " (ISIC " (:isic_rev5 bp) ")")]
      [:dt (t :governor)] [:dd (:governor bp)]
      [:dt (t :money)] [:dd (fmt :money-text {:min (get-in m [:credit_bounds_micro_usdc :min]) :max (get-in m [:credit_bounds_micro_usdc :max])
                                               :services (str/join ", " (:allowed_services m))})]
      [:dt (t :phi)] [:dd (get-in p [:phi :rule])]]
     [:p {:class "awai-caption awai-muted"} (:offer bp)]
     [:p {:id "registry-state" :class "awai-caption"} (get (t :registry) (get-in profile [:provenance :registry_state]))]
     [:p {:class "awai-caption awai-muted"} (str (t :source) ": " (get-in profile [:provenance :repository]) " " (subs (get-in profile [:provenance :revision]) 0 12))])))

(defn status-card []
  (let [a (:assignment @state) evs (:events @state) h (when a (d/health a evs (js/Date.now)))]
    (ui/card
     (ui/heading 2 (t :health))
     (cond
       (nil? a) [:p {:id "health" :class "awai-muted"} (t :not-read)]
       (= "not-launched" (:state a))
       [:div [:p {:id "health" :data-verdict "not-launched"} (t :not-launched)]
        [:p {:class "awai-caption awai-muted"} (fmt :not-launched-detail {:others (:others a)})]]
       :else
       [:div
        [:p {:id "health" :data-verdict (:verdict h)} (t (keyword (:verdict h)))]
        (when (seq (:reasons h)) [:ul {:id "reasons"} (map (fn [r] [:li (get (t :reasons) r r)]) (:reasons h))])
        [:dl
         [:dt (t :status)] [:dd (:status a)]
         [:dt (t :ticks)] [:dd (str (:tick-count a))]
         [:dt (t :checkpoint)] [:dd (time-text (:last-checkpoint-at a))]
         [:dt (t :credit)] [:dd (str (:credit-limit-micros a) " / " (:debt-micros a) " micro-USDC")]]])
     (when (:read-at @state) [:p {:class "awai-caption awai-muted"} (str (t :read-at) ": " (time-text (:read-at @state)))]))))

(defn events-card []
  (when-let [evs (:events @state)]
    (ui/card
     (ui/heading 2 (fmt :events {:n d/max-events}))
     (if (empty? evs) [:p (t :no-events)]
         [:ol {:id "events"}
          (map (fn [e] [:li [:p (str (:occurred-at e) " · " (:type e))]
                        (when (:error e) [:p {:class "event-error"} (:error e)])
                        [:p {:class "awai-caption awai-muted"} (str "sha256 " (subs (:digest e) 0 16) "…")]]) evs)]))))

(defn observations [obs]
  [:div [:p [:strong (t :obs)]]
   (if (empty? obs) [:p {:class "awai-muted"} (t :no-obs)]
       [:ul {:class "checks"} (map (fn [o] [:li {:data-found "false"} (get (t :observations) o o)]) obs)])])

;; ---- 電子カルテ: SOAP check ---------------------------------------------------

(def inference (createInferenceClient))

(defn refresh-readiness! []
  (-> (.info inference)
      (.then (fn [info] (swap! state assoc :readiness (draft/readiness info)) (render!)))
      (.catch (fn [_] (swap! state assoc :readiness "unavailable") (render!)))))

(defn run-draft! []
  (let [text (:note @state) check (:soap @state)]
    (swap! state assoc :draft-confirm false)
    (if-not (and check (= "ready" (:readiness @state)))
      (notice! (get (t :readiness) (or (:readiness @state) "status-unknown")))
      (try
        (let [p (draft/prompt text check)]
          (swap! state assoc :drafting true :draft nil) (render!)
          (-> (.generate inference #js{:prompt p :maxTokens draft/max-tokens})
              (.then (fn [r] (swap! state assoc :draft (draft/draft r)) (notice! (t :draft-title))))
              (.catch (fn [e] (let [msg (draft/error-text e)]
                                (notice! (if (draft/denied? msg) (t :draft-denied) (str (t :draft-failed) (get (t :errors) msg msg)))))))
              (.finally (fn [] (swap! state assoc :drafting false) (refresh-readiness!)))))
        (catch :default e (notice! (error-text e)))))))

(defn draft-section []
  (let [rd (:readiness @state) dr (:draft @state)]
    [:section {:id "draft"}
     (when rd [:p {:id "readiness" :class "awai-caption"} (get (t :readiness) rd rd)])
     (if (:draft-confirm @state)
       [:div {:id "draft-confirm" :class "confirm" :role "alertdialog" :aria-labelledby "draft-confirm-title"}
        [:p {:id "draft-confirm-title"} [:strong (t :draft-confirm-title)]]
        [:p (t :draft-confirm-text)]
        [:div {:class "actions"}
         (ui/button (t :draft-go) {:attrs {:id "draft-go" :on-click run-draft!}})
         (ui/button (t :draft-cancel) {:variant "tonal" :attrs {:id "draft-cancel" :on-click (fn [] (swap! state assoc :draft-confirm false) (notice! (t :draft-cancelled)))}})]]
       [:div {:class "actions"}
        (ui/button (t :draft-btn) {:variant "tonal" :attrs {:id "draft-btn" :disabled (or (:drafting @state) (not= "ready" rd))
                                                             :on-click (fn [] (swap! state assoc :draft-confirm true) (render!))}})])
     (when dr
       [:div {:id "draft-result"}
        [:p [:strong (t :draft-title)]]
        [:p {:class "draft-text"} (:text dr)]
        [:p {:class "awai-caption awai-muted"} (t :draft-note)]])]))

(defn check-note! []
  (try (swap! state assoc :soap (soap/check (:note @state)) :draft nil :draft-confirm false :handoff nil :handoff-confirm false) (render!) (refresh-readiness!)
       (catch :default e (swap! state assoc :soap nil) (notice! (error-text e)))))

(defn handoff-refusal-text [reason]
  (get (t :errors) (if (= "handoff-expired" reason) "handoff-expired"
                       (if (= "handoff-changed" reason) "handoff-changed" "handoff-refused"))
       (t :handoff-cancelled)))

(defn carry-to-rezept! []
  (when-let [dx (seq (get-in @state [:soap :diagnoses]))]
    (try (swap! state assoc :handoff (handoff/make dx (js/Date.now)) :handoff-confirm true) (render!)
         (catch :default e (swap! state assoc :handoff nil :handoff-confirm false)
           (notice! (let [r (error-reason e)]
                      (if (and (string? r) (str/starts-with? r "handoff-")) (handoff-refusal-text r) (error-text e))))))))

(defn confirm-carry! []
  (let [env (:handoff @state) dx (get-in @state [:soap :diagnoses]) now (js/Date.now)
        bad (or (handoff/problem env now)
                (when-not (handoff/matches? env dx) "handoff-changed"))]
    (swap! state assoc :handoff-confirm false)
    (if bad
      (do (swap! state assoc :handoff nil) (notice! (handoff-refusal-text bad)))
      (let [current (str/trim (:rezept-text @state))
            lines (rezept/diagnosis-lines (:diagnoses env))]
        (swap! state assoc :rezept-text (if (str/blank? current) lines (str current "\n" lines))
               :rezept nil :handoff nil :expl nil :expl-confirm false
               :blueprint "iryo" :assignment nil :events nil :read-at nil)
        (notice! (t :carried))))))

(defn handoff-section []
  (when (:handoff-confirm @state)
    [:div {:id "handoff-confirm" :class "confirm" :role "alertdialog" :aria-labelledby "handoff-confirm-title"}
     [:p {:id "handoff-confirm-title"} [:strong (t :handoff-title)]]
     (when-let [env (:handoff @state)]
       [:ul {:id "handoff-names"}
        (map (fn [d] [:li (str (:name d) (when (:main d) (str "（" (t :main) "）")) (when (:suspected d) (str "（" (t :suspected) "）")))]) (:diagnoses env))])
     [:p (t :handoff-text)]
     [:div {:class "actions"}
      (ui/button (t :handoff-go) {:attrs {:id "handoff-go" :on-click confirm-carry!}})
      (ui/button (t :handoff-cancel) {:variant "tonal" :attrs {:id "handoff-cancel" :on-click (fn [] (swap! state assoc :handoff nil :handoff-confirm false) (notice! (t :handoff-cancelled)))}})]]))

(defn run-expl! []
  (let [text (:rezept-text @state) result (:rezept @state)]
    (swap! state assoc :expl-confirm false)
    (if-not (and result (= "ready" (:readiness @state)))
      (notice! (get (t :readiness) (or (:readiness @state) "status-unknown")))
      (try
        (let [p (draft/explanation-prompt text result)]
          (swap! state assoc :explaining true :expl nil) (render!)
          (-> (.generate inference #js{:prompt p :maxTokens draft/max-tokens})
              (.then (fn [r] (swap! state assoc :expl (draft/draft r)) (notice! (t :expl-title))))
              (.catch (fn [e] (let [msg (draft/error-text e)]
                                (notice! (if (draft/denied? msg) (t :draft-denied) (str (t :draft-failed) (get (t :errors) msg msg)))))))
              (.finally (fn [] (swap! state assoc :explaining false) (refresh-readiness!)))))
        (catch :default e (notice! (error-text e)))))))

(defn expl-section []
  (let [rd (:readiness @state) ex (:expl @state)]
    [:section {:id "expl"}
     (when rd [:p {:id "expl-readiness" :class "awai-caption"} (get (t :readiness) rd rd)])
     (if (:expl-confirm @state)
       [:div {:id "expl-confirm" :class "confirm" :role "alertdialog" :aria-labelledby "expl-confirm-title"}
        [:p {:id "expl-confirm-title"} [:strong (t :expl-confirm-title)]]
        [:p (t :expl-confirm-text)]
        [:div {:class "actions"}
         (ui/button (t :expl-go) {:attrs {:id "expl-go" :on-click run-expl!}})
         (ui/button (t :expl-cancel) {:variant "tonal" :attrs {:id "expl-cancel" :on-click (fn [] (swap! state assoc :expl-confirm false) (notice! (t :expl-cancelled)))}})]]
       [:div {:class "actions"}
        (ui/button (t :expl-btn) {:variant "tonal" :attrs {:id "expl-btn" :disabled (or (:explaining @state) (not= "ready" rd) (nil? (:rezept @state)))
                                                           :on-click (fn [] (swap! state assoc :expl-confirm true) (render!))}})])
     (when ex
       [:div {:id "expl-result"}
        [:p [:strong (t :expl-title)]]
        [:p {:class "draft-text"} (:text ex)]
        [:p {:class "awai-caption awai-muted"} (t :expl-note)]])]))

(defn soap-card []
  (let [r (:soap @state)]
    (ui/card
     (ui/heading 2 (t :soap-title))
     (ui/field {:id "note" :label (t :note-label)}
       [:textarea {:rows "10" :value (:note @state) :on-input (fn [e] (swap! state assoc :note (.. e -target -value) :soap nil :draft nil :draft-confirm false))}])
     [:div {:class "actions"} (ui/button (t :soap-btn) {:variant "tonal" :attrs {:id "check-note" :on-click check-note!}})]
     (when r
       [:section {:id "soap-result"}
        [:ul {:id "sections" :class "checks"}
         (map (fn [s] [:li {:data-found (str (:present s))} (str (get (t :section) (:id s)) ": " (if (:present s) (t :present) (t :absent)))]) (:sections r))]
        [:p [:strong (t :vitals)]]
        (if (empty? (:vitals r)) [:p {:class "awai-muted"} (t :no-vitals)]
            [:ul {:id "vitals" :class "checks"}
             (map (fn [v] [:li {:data-found (str (= "within-reference" (:status v)))}
                           (str (get (t :vital-names) (:id v)) " " (:text v) " " (:unit v) ": " (get (t :vital-status) (:status v)))]) (:vitals r))])
        [:p [:strong (t :dx)]]
        (if (empty? (:diagnoses r)) [:p {:class "awai-muted"} (t :no-dx)]
            [:ul {:id "diagnoses"} (map (fn [d] [:li (str (:name d) (when (:main d) (str "（" (t :main) "）")) (when (:suspected d) (str "（" (t :suspected) "）")))]) (:diagnoses r))])
        (observations (:observations r))
        (when (seq (:diagnoses r))
          [:div {:class "actions"} (ui/button (t :to-rezept) {:attrs {:id "to-rezept" :on-click carry-to-rezept!}})])
        (handoff-section)
        [:p {:class "awai-caption awai-muted"} (t :soap-note)]
        (draft-section)]))))

;; ---- レセプト: claim draft ----------------------------------------------------

(defn patient-input []
  (let [age (str/trim (:age @state)) wari (str/trim (:wari @state)) kogaku (:kogaku @state)]
    (when-not (or (= "" age) (and (re-matches #"\d{1,3}" age) (<= (js/Number age) 120))) (throw (ex-info "age" {:reason "age"})))
    (when-not (contains? #{"" "0" "0.1" "0.2" "0.3"} wari) (throw (ex-info "wari" {:reason "wari"})))
    {:age (when-not (= "" age) (js/Number age)) :futan-wari (when-not (= "" wari) (js/Number wari))
     :kogaku-kubun (when-not (= "" kogaku) kogaku)}))

(defn calculate! []
  (try (swap! state assoc :rezept (rezept/compute (rezept/parse (:rezept-text @state)) (patient-input)) :expl nil :expl-confirm false) (render!) (refresh-readiness!)
       (catch :default e (swap! state assoc :rezept nil) (notice! (error-text e)))))

(defn rezept-card []
  (let [r (:rezept @state) on-set (fn [k] (fn [e] (swap! state assoc k (.. e -target -value) :rezept nil)))]
    (ui/card
     (ui/heading 2 (t :rezept-title))
     (ui/field {:id "rezept-lines" :label (t :rezept-label)}
       [:textarea {:rows "8" :value (:rezept-text @state) :on-input (on-set :rezept-text)}])
     [:p {:class "awai-caption awai-muted"} (t :rezept-help)]
     [:div {:class "patient"}
      (ui/field {:id "age" :label (t :age)} [:input {:inputmode "numeric" :value (:age @state) :on-input (on-set :age)}])
      (ui/field {:id "wari" :label (t :wari)} [:input {:inputmode "decimal" :value (:wari @state) :on-input (on-set :wari)}])
      (ui/field {:id "kogaku" :label (t :kogaku)}
        [:select {:on-change (on-set :kogaku)}
         [:option {:value "" :selected (= "" (:kogaku @state))} (t :kogaku-none)]
         (map (fn [k] [:option {:value k :selected (= k (:kogaku @state))} k]) rezept/kogaku-kubun-names)])]
     [:div {:class "actions"} (ui/button (t :rezept-btn) {:variant "tonal" :attrs {:id "calculate" :on-click calculate!}})]
     (when r
       [:section {:id "rezept-result"}
        [:dl {:id "totals"}
         [:dt (t :total-ten)] [:dd {:id "total-ten"} (str (yen (:total-ten r)) " " (t :ten))]
         [:dt (t :iryohi)] [:dd (str (yen (:total-iryohi-yen r)) " " (t :yen))]
         [:dt (fmt :ichibu {:w (js/Math.round (* 10 (:futan-wari r)))})] [:dd (str (yen (:ichibu-futan-yen r)) " " (t :yen))]
         (when (:kogaku-limit-yen r) [:dt (t :kogaku-limit)]) (when (:kogaku-limit-yen r) [:dd (str (yen (:kogaku-limit-yen r)) " " (t :yen))])
         [:dt (t :pay)] [:dd {:id "patient-pay"} [:strong (str (yen (:patient-pay-yen r)) " " (t :yen))]]]
        [:p [:strong (t :kubun)]]
        [:ul {:id "kubun"} (map (fn [k] [:li (str (:kubun k) " " (yen (:ten k)) " " (t :ten))]) (:kubun-totals r))]
        [:p [:strong (t :lines)]]
        [:ol {:id "lines"} (map (fn [l] [:li (str (:name l) "（" (:kubun l) "） " (:unit-ten l) " × " (:count l) " = " (:ten l) " " (t :ten))]) (:lines r))]
        (observations (:observations r))
        (expl-section)
        [:p {:class "awai-caption awai-muted"} (t :rezept-note)]]))))

(defn render! []
  (let [root (.getElementById js/document "app") busy (:busy @state) a (:assignment @state)]
    (set! (.. js/document -documentElement -lang) (:locale @state))
    (.replaceChildren root
     (node [:div {:class "awai-ui awai-container"}
            [:header {:class "app-header"} (ui/heading 1 (t :title))
             (ui/field {:id "language" :label (t :language)}
               [:select {:value (:locale @state) :on-change (fn [e] (swap! state assoc :locale (.. e -target -value)) (render!))}
                [:option {:value "ja" :selected (= "ja" (:locale @state))} "日本語"] [:option {:value "en" :selected (= "en" (:locale @state))} "English"]])]
            (ui/status (:notice @state) {:id "notice"})
            [:main {:id "main"}
             ;; FLAG_SECURE (release builds): say so where the user reads about the app (Owner bot, KOTOBA-159).
             [:p {:id "screen-note" :class "awai-caption awai-muted"} (t :screen-note)]
             (if-not profile-ok
               [:p {:id "profile-invalid"} (t :profile-invalid)]
               (ui/stack
                [:p {:class "lead"} (t :lead)]
                (ui/field {:id "blueprint" :label (t :bot)}
                  [:select {:on-change (fn [e] (swap! state assoc :blueprint (.. e -target -value) :assignment nil :events nil :read-at nil) (render!))}
                   (map (fn [id] [:option {:value id :selected (= id (:blueprint @state))} (get-in profiles [id :profile :business_blueprint :name])]) d/blueprints)])
                (if (= "iryo" (:blueprint @state)) (rezept-card) (soap-card))
                (profile-card)
                [:div {:class "actions"}
                 (ui/button (t :read-assignments) {:attrs {:id "read-assignments" :disabled busy :on-click read-assignments!}})
                 (when (= "launched" (:state a))
                   (ui/button (t :read-activity) {:variant "tonal" :attrs {:id "read-activity" :disabled busy :on-click read-activity!}}))]
                [:p {:class "awai-caption awai-muted"} (str (t :endpoint) ": " d/bots-origin " — " (t :consent-note))]
                (status-card)
                (events-card)))]]))))

(render!)
