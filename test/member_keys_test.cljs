(ns member-keys-test
  (:require [karute.member-keys :as mk] [karute.handoff :as h] [karute.soap :as s]
            ["node:test" :refer [test]] ["node:assert/strict" :as a]
            ["node:crypto" :as crypto] ["node:fs" :as fs]))

(def dx (:diagnoses (s/check (fs/readFileSync "test/fixtures/soap-complete.txt" "utf8"))))
(def now 1790000000000)
(defn reason [f] (try (f) nil (catch :default e (:reason (ex-data e)))))
(defn raw-pub-hex [kp] (let [der (.export (.-publicKey kp) #js{:format "der" :type "spki"})]
  (.toString (.subarray der (- (.-length der) 32)) "hex")))

(def kp (crypto/generateKeyPairSync "ed25519"))
(def pub-hex (raw-pub-hex kp))
(def keyset {:schema "itonami.member-keys.v1"
             :keys [{:member "clinic-a" :pubkey pub-hex :scope "karute-diagnoses-to-iryo"}]})
(def env (h/make dx now))
(def sig-hex (.toString (crypto/sign nil (js/Buffer.from (h/canonical env) "utf8") (.-privateKey kp)) "hex"))
(defn check [bytes sig pub]
  (and (= pub pub-hex)
       (crypto/verify nil (js/Buffer.from bytes "utf8") (.-publicKey kp) (js/Buffer.from sig "hex"))))

(test "a clean key set verifies a member-signed envelope with real Ed25519"
  (fn [] (a/equal (mk/keyset-problem keyset) nil)
    (a/deepEqual (mk/find-key keyset "clinic-a") {:member "clinic-a" :pubkey pub-hex :scope "karute-diagnoses-to-iryo"})
    (a/equal (mk/find-key keyset "nobody") nil)
    (a/deepEqual (mk/verify-member env "clinic-a" sig-hex keyset check now) env)))

(test "negative controls: the key set itself is refused before any crypto"
  (fn [] (a/equal (mk/keyset-problem nil) "member-keys-schema")
    (a/equal (mk/keyset-problem (assoc keyset :schema "x")) "member-keys-schema")
    (a/equal (mk/keyset-problem (assoc keyset :keys [])) "member-keys-shape")
    (a/equal (mk/keyset-problem (assoc keyset :keys (vec (repeat 51 {:member "m" :pubkey pub-hex :scope "s"})))) "member-keys-shape")
    (a/equal (mk/keyset-problem (assoc keyset :keys [{:member "" :pubkey pub-hex :scope "s"}])) "member-key")
    (a/equal (mk/keyset-problem (assoc keyset :keys [{:member "m" :pubkey "zz" :scope "s"}])) "member-key")
    (a/equal (mk/keyset-problem (assoc keyset :keys [{:member "m" :pubkey pub-hex :scope ""}])) "member-key")
    (let [other (raw-pub-hex (crypto/generateKeyPairSync "ed25519"))]
      (a/equal (mk/keyset-problem (assoc keyset :keys [{:member "m" :pubkey pub-hex :scope "s"}
                                                       {:member "m" :pubkey other :scope "s"}])) "member-duplicate")
      (a/equal (mk/keyset-problem (assoc keyset :keys [{:member "m" :pubkey pub-hex :scope "s"}
                                                       {:member "n" :pubkey pub-hex :scope "s"}])) "member-duplicate"))))

(test "verification refuses without reaching the verifier when it must"
  (fn [] (let [seen (atom false) spy (fn [& _] (reset! seen true) true)]
           (a/equal (reason #(mk/verify-member env "nobody" sig-hex keyset spy now)) "member-unknown")
           (a/equal @seen false))
    (let [seen (atom false) spy (fn [& _] (reset! seen true) true)
          scoped (assoc keyset :keys [{:member "clinic-a" :pubkey pub-hex :scope "other-scope"}])]
      (a/equal (reason #(mk/verify-member env "clinic-a" sig-hex scoped spy now)) "member-scope")
      (a/equal @seen false))
    (let [seen (atom false) spy (fn [& _] (reset! seen true) true)]
      (a/equal (reason #(mk/verify-member env "clinic-a" sig-hex keyset spy (+ now h/validity-ms))) "handoff-expired")
      (a/equal @seen false))
    (let [tampered (assoc env :diagnoses [{:name "肺炎" :main true :suspected false}])]
      (a/equal (reason #(mk/verify-member tampered "clinic-a" sig-hex keyset check now)) "handoff-bad-signature"))))
