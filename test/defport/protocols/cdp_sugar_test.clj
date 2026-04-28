(ns defport.protocols.cdp-sugar-test
  "Unit tests for defport.cdp.sugar/defcdp-domain.

   These exercise the macro against a real domain from the loaded
   spec. They don't need a WebSocket — we stub the client record and
   assert that the generated helpers resolve to the right spec keyword
   and pass params through verbatim."
  (:require [clojure.test :refer [deftest testing is]]
            [defport.cdp.client :as client]
            [defport.cdp.spec :as spec]
            [defport.cdp.sugar :refer [defcdp-domain]]))

;; Intern helpers for two real CDP domains at macro-expansion time.
;; Accessibility is small (~8 commands) and stable; Page is the
;; largest that every Chromium version ships. Picking two catches
;; both small and large surface shapes.
(defcdp-domain Accessibility)
(defcdp-domain Page)

;; ============================================================================
;; Stub client — replaces request! so we can assert the spec keyword
;; ============================================================================

(defn- stub-client []
  (let [calls (atom [])]
    {:calls  calls
     :client (reify client/ClientTransport
               (transport-start! [_] nil)
               (transport-send!  [_ msg] (swap! calls conj msg) nil)
               (transport-recv!  [_] :defport.cdp.client/no-message)
               (transport-stop!  [_] nil)
               (transport-alive? [_] true))}))

(defn- call-records
  "Replace request! with a recorder and return [result calls] after
   invoking `f`. Calls are recorded as [handler-kw params]."
  [f]
  (let [calls (atom [])]
    (with-redefs [client/request! (fn [_client handler-kw params]
                                    (swap! calls conj [handler-kw params])
                                    :pending-stub)]
      (let [result (f)]
        [result @calls]))))

;; ============================================================================
;; Existence tests
;; ============================================================================

(defn- count-domain-helpers [prefix]
  (->> (ns-publics 'defport.protocols.cdp-sugar-test)
       keys
       (filter #(clojure.string/starts-with? (name %) prefix))
       count))

(deftest defcdp-domain-generates-every-command
  (testing "defcdp-domain interns one helper per command in the domain"
    (let [page-cmds (count (spec/all-commands "Page"))
          acc-cmds  (count (spec/all-commands "Accessibility"))]
      (is (pos? page-cmds))
      (is (pos? acc-cmds))
      (is (>= (count-domain-helpers "page-") page-cmds)
          "every Page/* command should have a page-* helper")
      (is (>= (count-domain-helpers "accessibility-") acc-cmds)
          "every Accessibility/* command should have an accessibility-* helper"))))

(deftest defcdp-domain-generates-page-helpers
  (testing "Page domain helpers exist for the canonical commands"
    (doseq [sym '[page-navigate page-reload page-enable page-disable
                  page-capture-screenshot page-print-to-pdf]]
      (is (var? (ns-resolve 'defport.protocols.cdp-sugar-test sym))
          (str sym " should be interned")))))

;; ============================================================================
;; Dispatch tests — single-arity and params-arity
;; ============================================================================

(deftest generated-helper-routes-to-spec-keyword
  (testing "single-arity helper calls request! with empty params"
    (let [[_ calls] (call-records
                      #((ns-resolve 'defport.protocols.cdp-sugar-test 'accessibility-enable)
                        :fake-client))]
      (is (= 1 (count calls)))
      (is (= :Accessibility/enable (ffirst calls)))
      (is (= {} (second (first calls)))))))

(deftest generated-helper-passes-params-through
  (testing "two-arity helper passes params map verbatim"
    (let [[_ calls] (call-records
                      #((ns-resolve 'defport.protocols.cdp-sugar-test 'page-navigate)
                        :fake-client
                        {:url "https://example.com"}))]
      (is (= 1 (count calls)))
      (is (= :Page/navigate (ffirst calls)))
      (is (= {:url "https://example.com"} (second (first calls)))))))

;; ============================================================================
;; Unknown domain fails at expansion time
;; ============================================================================

(deftest defcdp-domain-rejects-unknown-domain
  (testing "expanding defcdp-domain with an unknown domain throws"
    (let [thrown (try
                   (macroexpand '(defport.cdp.sugar/defcdp-domain NoSuchDomain))
                   nil
                   (catch Throwable t t))]
      (is (some? thrown) "macroexpansion should throw")
      (is (re-find #"Unknown CDP domain"
                   (or (some-> thrown ex-cause .getMessage)
                       (.getMessage thrown)))))))
