(ns defport.protocols.lsp-client-test
  "Tests for defport.lsp.client.

  Uses an in-memory FakeTransport that pairs a defport LSP server
  adapter to a defport LSP client through two queues — no
  subprocess, no real wire framing. Exercises:

  - request/response correlation by id
  - notification dispatch
  - typed convenience helpers reading wire names from spec
  - initialize/initialized handshake
  - did-* document-sync notifications round-trip into the server's
    document store
  - server-side ports defined via deflsp respond to client requests"
  (:require [clojure.test :refer [deftest testing is]]
            [defport.core :as core]
            [defport.lsp :as lsp]
            [defport.lsp.client :as client]
            [defport.lsp.spec :as spec]
            [defport.registry :as registry]
            [defport.sugar :as sugar]))

;; ============================================================================
;; In-memory paired transport
;; ============================================================================
;;
;; The client transport pushes into a "to-server" queue and reads
;; from a "to-client" queue. A tiny pump function takes one message
;; from to-server, dispatches it through the server adapter, and
;; pushes the response into to-client. This is just enough to round-trip
;; LSP messages without spawning a subprocess.

(defrecord FakeTransport [name to-server to-client started?* stopped?*]
  client/ClientTransport
  (transport-start! [this]
    (reset! started?* true)
    this)
  (transport-send! [_ msg]
    (swap! to-server conj msg)
    nil)
  (transport-recv! [_]
    (let [[old new] (swap-vals! to-client (fn [q] (if (seq q) (subvec q 1) q)))]
      (if (seq old)
        (first old)
        ::client/no-message)))
  (transport-stop! [_]
    (reset! stopped?* true)
    nil)
  (transport-alive? [_]
    (and @started?* (not @stopped?*))))

(defn- make-paired-transport []
  (->FakeTransport "test"
                   (atom [])
                   (atom [])
                   (atom false)
                   (atom false)))

(defn- pump-one!
  "Drain one message off the client→server queue, dispatch it through
   the LSP adapter, and (for requests) push the response onto the
   server→client queue. Returns true if a message was processed."
  [transport server-adapter port-registry]
  (let [[old _] (swap-vals! (:to-server transport)
                            (fn [q] (if (seq q) (subvec q 1) q)))]
    (when (seq old)
      (let [msg (first old)
            method (:method msg)
            params (:params msg)
            id     (:id msg)]
        (when method
          (let [resp (core/protocol-dispatch server-adapter method params
                                             {:port-registry port-registry
                                              :id id})]
            (when id
              (swap! (:to-client transport) conj
                     {:jsonrpc "2.0" :id id :result resp})))))
      true)))

(defn- pump-all! [transport server-adapter port-registry]
  (loop [n 0]
    (if (and (pump-one! transport server-adapter port-registry) (< n 100))
      (recur (inc n))
      n)))

(defn- fresh-registry [] (registry/create-function-registry))

;; ============================================================================
;; Smoke: connect + initialize handshake
;; ============================================================================

(deftest test-client-connect-initializes-and-records-server-info
  (testing "connect! drives the LSP initialize handshake against a paired adapter"
    (let [reg     (fresh-registry)
          adapter (sugar/create-adapter :lsp
                    {:server-info {:name "fake-server" :version "1.2.3"}
                     :registry reg})
          ;; Pre-register the lifecycle handlers so the server responds
          ;; to initialize/initialized.
          _       (lsp/register-lifecycle-handlers! adapter)
          tx      (make-paired-transport)
          client  (client/create-client tx)]
      ;; Asynchronously connect, but pump synchronously after each step.
      (let [done (promise)]
        (client/connect-async! client {:client-info {:name "test" :version "0.0"}}
          (fn [c err] (deliver done [c err])))
        ;; Drain the initialize request → response.
        (pump-all! tx adapter reg)
        (let [[c err] (deref done 1000 [::timeout nil])]
          (is (not= ::timeout c) "client did not receive initialize response within 1s")
          (is (nil? err))
          (is (some? c))
          (is (true? (:initialized? @(:state* c))))
          (is (= "fake-server" (:name (:server-info @(:state* c))))))))))

;; ============================================================================
;; Typed helper round-trip: hover via deflsp
;; ============================================================================

(deftest test-hover-at-roundtrips-through-deflsp
  (testing "client/hover-at calls a server-side deflsp handler"
    (let [reg (fresh-registry)]
      (binding [sugar/*registry* reg]
        (lsp/deflsp hover
          [uri :- :string line :- :int col :- :int]
          {:contents {:kind "markdown"
                      :value (str uri "@" line ":" col)}}))
      (let [adapter (sugar/create-adapter :lsp
                      {:server-info {:name "t" :version "0"}
                       :registry reg})
            _       (lsp/register-lifecycle-handlers! adapter)
            tx      (make-paired-transport)
            client  (client/create-client tx)
            done    (promise)]
        (client/connect-async! client {} (fn [c err] (deliver done [c err])))
        (pump-all! tx adapter reg)
        (deref done 1000 nil)
        ;; Issue hover-at and pump.
        (let [pending (client/hover-at client "file:///a.clj" 10 4)
              hover-done (promise)]
          (client/then pending (fn [r e] (deliver hover-done [r e])))
          (pump-all! tx adapter reg)
          (let [[result error] (deref hover-done 1000 [::timeout nil])]
            (is (not= ::timeout result))
            (is (nil? error))
            (is (= {:contents {:kind "markdown" :value "file:///a.clj@10:4"}}
                   result))))))))

;; ============================================================================
;; Pending: then + await
;; ============================================================================

(deftest test-pending-then-fires-on-resolve
  (let [p (client/->Pending 1 (atom {:status :pending :callbacks []}))
        cb-result (atom nil)]
    (client/then p (fn [r e] (reset! cb-result [r e])))
    (#'client/resolve-pending! p {:ok 1} nil)
    (is (= [{:ok 1} nil] @cb-result))))

(deftest test-pending-then-fires-immediately-when-already-resolved
  (let [p (client/->Pending 2 (atom {:status :done :result 42 :callbacks []}))
        cb-result (atom nil)]
    (client/then p (fn [r e] (reset! cb-result [r e])))
    (is (= [42 nil] @cb-result))))

(deftest test-await-returns-resolved-value-jvm
  (let [p (client/->Pending 3 (atom {:status :pending :callbacks []}))]
    (future
      (Thread/sleep 20)
      (#'client/resolve-pending! p :ok nil))
    (let [[r e] (client/await p)]
      (is (= :ok r))
      (is (nil? e)))))

;; ============================================================================
;; Notifications: did-open / did-save round-trip into doc store
;; ============================================================================

(deftest test-did-open-syncs-server-document-store
  (let [reg (fresh-registry)
        adapter (sugar/create-adapter :lsp
                  {:server-info {:name "t" :version "0"}
                   :registry reg})
        _ (lsp/register-document-sync-handlers! adapter)
        tx (make-paired-transport)
        client (client/create-client tx)]
    (client/connect-async! client {} (fn [_ _] nil))
    (pump-all! tx adapter reg)
    (client/did-open! client "file:///x.clj" "clojure" 1 "(ns x)")
    (pump-all! tx adapter reg)
    (let [doc (lsp/doc-get (:document-store adapter) "file:///x.clj")]
      (is (= "(ns x)" (:content doc))))))

(deftest test-on-notification-receives-server-pushed-events
  (let [reg (fresh-registry)
        adapter (sugar/create-adapter :lsp
                  {:server-info {:name "t" :version "0"}
                   :registry reg})
        tx (make-paired-transport)
        client (client/create-client tx)
        seen (atom nil)]
    (client/on-notification client "textDocument/publishDiagnostics"
                            (fn [params] (reset! seen params)))
    ;; Simulate the server pushing a notification by inserting one into
    ;; the to-client queue directly; the driver will pick it up and
    ;; dispatch.
    (client/transport-start! tx)
    (#'client/start-driver! client)
    (swap! (:to-client tx) conj
           {:jsonrpc "2.0"
            :method "textDocument/publishDiagnostics"
            :params {:uri "file:///a.clj" :diagnostics []}})
    ;; Give the JVM reader thread a moment.
    (Thread/sleep 50)
    (is (= "file:///a.clj" (:uri @seen)))))
