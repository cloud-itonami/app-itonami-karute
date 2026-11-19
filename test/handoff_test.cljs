(ns handoff-test
  (:require [karute.handoff :as h] [karute.soap :as s] [karute.rezept :as r]
            ["node:test" :refer [test]] ["node:assert/strict" :as a]
            ["node:crypto" :as crypto] ["node:fs" :as fs]))

(def note (fs/readFileSync "test/fixtures/soap-complete.txt" "utf8"))
(def dx (:diagnoses (s/check note)))
(def now 1790000000000)
(defn reason [f] (try (f) nil (catch :default e (:reason (ex-data e)))))

(test "a fresh envelope names the A problems and applies in memory"
  (fn [] (let [env (h/make dx now)]
    (a/equal (:schema env) "itonami.karute-handoff.v1")
    (a/equal (:scope env) "karute-diagnoses-to-iryo")
    (a/equal (:expires-at env) (+ now h/validity-ms))
    (a/equal (h/problem env now) nil)
    (a/equal (h/problem env (+ now h/validity-ms -1)) nil)
    (a/ok (h/matches? env dx))
    (a/ok (.includes (h/envelope-text env) "急性上気道炎"))
    (a/ok (.includes (h/canonical env) "急性上気道炎"))
    ;; The envelope diagnoses write the same claim lines the old copy wrote.
    (let [items (r/parse (r/diagnosis-lines (:diagnoses env)))]
      (a/equal (r/diagnosis-lines (:diagnoses env)) "病名 急性上気道炎 主\n病名 高血圧症")
      (a/deepEqual (mapv :main items) [true false])))))

(test "canonical bytes are deterministic for the same problems"
  (fn [] (let [a (h/make dx now) b (h/make (mapv #(select-keys % [:name :main :suspected]) dx) now)]
    (a/equal (h/canonical a) (h/canonical b)))))

(test "a changed A section no longer matches: the carry must be remade"
  (fn [] (let [env (h/make dx now)
               edited (conj (vec dx) {:name "肺炎" :main false :suspected true})]
    (a/equal (h/matches? env edited) false)
    (a/equal (h/matches? env (rest dx)) false))))

(test "negative controls: shape, window and freshness failures refuse"
  (fn [] (let [env (h/make dx now)]
    (a/equal (h/problem nil now) "handoff-empty")
    (a/equal (h/problem (assoc env :schema "x") now) "handoff-schema")
    (a/equal (h/problem (assoc env :scope "x") now) "handoff-scope")
    (a/equal (h/problem env (+ now h/validity-ms)) "handoff-expired")
    (a/equal (h/problem env (+ now h/validity-ms 60000)) "handoff-expired")
    (a/equal (h/problem env (- now 1)) "handoff-not-yet-valid")
    (a/equal (h/problem (assoc env :expires-at (+ now 60000)) now) "handoff-window")
    (a/equal (h/problem (assoc env :diagnoses []) now) "handoff-diagnoses")
    (a/equal (h/problem (assoc env :created-at "now") now) "handoff-time")
    (a/equal (reason #(h/make [] now)) "handoff-diagnoses")
    (a/equal (reason #(h/make [{:name ""}] now)) "handoff-diagnoses")
    (a/equal (reason #(h/make [{:name (apply str (repeat 61 "あ"))}] now)) "handoff-diagnoses")
    (a/equal (reason #(h/make [{:name "感冒\n疑い"}] now)) "handoff-diagnoses")
    (a/equal (reason #(h/make dx "now")) "handoff-time")
    (a/equal (reason #(h/make (vec (repeat 21 {:name "感冒"})) now)) "handoff-diagnoses"))))

(test "a real Ed25519 member signature verifies the canonical bytes"
  (fn [] (let [kp (crypto/generateKeyPairSync "ed25519")
               raw (fn [k] (.toString k "hex"))
               pub-der (.export (.-publicKey kp) #js{:format "der" :type "spki"})
               pub-hex (raw (.subarray pub-der (- (.-length pub-der) 32)))
               env (h/make dx now)
               sig-hex (raw (crypto/sign nil (js/Buffer.from (h/canonical env) "utf8") (.-privateKey kp)))
               check (fn [bytes sig pub]
                       (and (= pub pub-hex)
                            (crypto/verify nil (js/Buffer.from bytes "utf8")
                                           (.-publicKey kp) (js/Buffer.from sig "hex"))))]
    (a/equal (count pub-hex) 64)
    (a/equal (count sig-hex) 128)
    (a/deepEqual (h/verify-with env pub-hex sig-hex check now) env)
    ;; Another member key does not verify this envelope.
    (let [other (crypto/generateKeyPairSync "ed25519")
          oder (.export (.-publicKey other) #js{:format "der" :type "spki"})
          other-hex (raw (.subarray oder (- (.-length oder) 32)))]
      (a/equal (reason #(h/verify-with env other-hex sig-hex check now)) "handoff-bad-signature"))
    ;; A renamed problem breaks the signature, not just the match.
    (let [tampered (assoc env :diagnoses [{:name "肺炎" :main true :suspected false}
                                         {:name "高血圧症" :main false :suspected false}])]
      (a/equal (reason #(h/verify-with tampered pub-hex sig-hex check now)) "handoff-bad-signature"))
    ;; An expired envelope never reaches the verifier.
    (let [seen (atom false)]
      (a/equal (reason #(h/verify-with env pub-hex sig-hex (fn [& _] (reset! seen true) true)
                                        (+ now h/validity-ms))) "handoff-expired")
      (a/equal @seen false)))))

(test "verification is fail-closed on shape, missing verifier and verifier errors"
  (fn [] (let [env (h/make dx now)
               ok (fn [& _] true)]
    (a/equal (reason #(h/verify-with env "zz" (apply str (repeat 128 "0")) ok now)) "handoff-pubkey-shape")
    (a/equal (reason #(h/verify-with env (apply str (repeat 64 "0")) "short" ok now)) "handoff-signature-shape")
    (a/equal (reason #(h/verify-with env (apply str (repeat 64 "0")) (apply str (repeat 128 "0")) nil now)) "handoff-verify-missing")
    (a/equal (reason #(h/verify-with env (apply str (repeat 64 "0")) (apply str (repeat 128 "0"))
                                      (fn [& _] (throw (js/Error. "hsm down"))) now)) "handoff-bad-signature")
    (a/equal (reason #(h/verify-with env (apply str (repeat 64 "0")) (apply str (repeat 128 "0"))
                                      (fn [& _] false) now)) "handoff-bad-signature"))))
