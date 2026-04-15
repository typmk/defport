(ns defport.cdp.spec
  "Chrome DevTools Protocol — spec registry generated from the
   upstream JSON at load time.

   CDP has ~56 domains, ~660 commands, and ~240 events — too large
   to hand-maintain. Fortunately Chromium publishes the canonical
   protocol as two JSON files:

     https://raw.githubusercontent.com/ChromeDevTools/devtools-protocol/master/json/browser_protocol.json
     https://raw.githubusercontent.com/ChromeDevTools/devtools-protocol/master/json/js_protocol.json

   Defport commits snapshots at resources/cdp-browser-protocol.json
   and resources/cdp-js-protocol.json. This namespace reads them on
   load and builds the same `methods` map shape that
   defport.{lsp,dap,mcp,bsp}.spec expose by hand — so every
   consumer of the spec (lookups, sugar macros, drift tests) works
   identically.

   Each CDP command becomes a spec entry keyed
   `<domain>/<commandName>`, e.g. `:Page/navigate`,
   `:Runtime/evaluate`. Each event becomes an entry with `:kind
   :notification`. Deprecated + experimental commands are included
   — defport doesn't filter them since different Chromium versions
   expose different subsets.

   No hand-typed 660 rows. Just data-driven spec building."
  (:require [clojure.java.io :as io]
            [defport.util.platform :as platform :include-macros true])
  (:refer-clojure :exclude [methods]))

(defn- read-protocol-resource [name]
  (when-let [r (io/resource name)]
    (platform/json-decode (slurp r))))

(defn- domain-entries
  "Turn one domain's commands + events into a seq of [handler-key spec-entry] pairs."
  [domain]
  (let [domain-name (:domain domain)]
    (concat
     (for [cmd (:commands domain)]
       (let [hname (keyword domain-name (name (:name cmd)))
             wire  (str domain-name "." (:name cmd))]
         [hname
          {:method     wire
           :kind       :request
           :direction  :client->server
           :domain     domain-name
           :sugar      :raw
           :default    (constantly {})
           :doc        (or (:description cmd) "")
           :experimental (boolean (:experimental cmd))
           :deprecated   (boolean (:deprecated cmd))}]))
     (for [evt (:events domain)]
       (let [hname (keyword (str domain-name ".event")
                            (name (:name evt)))
             wire  (str domain-name "." (:name evt))]
         [hname
          {:method     wire
           :kind       :notification
           :direction  :server->client
           :domain     domain-name
           :sugar      :raw
           :default    nil
           :doc        (or (:description evt) "")
           :experimental (boolean (:experimental evt))
           :deprecated   (boolean (:deprecated evt))}])))))

(def methods
  "Every CDP command + event Chromium publishes, keyed by
   `:<Domain>/<commandName>` or `:<Domain>.event/<eventName>`.
   Generated from the upstream JSON at namespace load time."
  (let [browser (read-protocol-resource "cdp-browser-protocol.json")
        js      (read-protocol-resource "cdp-js-protocol.json")
        all     (concat (:domains browser) (:domains js))]
    (into {}
          (mapcat domain-entries)
          all)))

(def version
  "Protocol version metadata from the upstream JSON."
  (or (-> (read-protocol-resource "cdp-browser-protocol.json") :version)
      {}))

;; ============================================================================
;; Lookups — same surface as other defport.*.spec namespaces
;; ============================================================================

(defn method-for [handler-name] (get methods handler-name))

(defn method-name-for [method-string]
  (some (fn [[k v]] (when (= method-string (:method v)) k)) methods))

(defn wire-method [handler-name] (:method (method-for handler-name)))

(defn sugar-extractor [_handler-name]
  ;; CDP is :raw-only — params shapes differ per command and there's
  ;; no shared sugar shape. Typed helpers in defport.cdp.client handle
  ;; ergonomics for the common commands.
  identity)

(defn default-response [handler-name] (:default (method-for handler-name)))

(defn notification? [handler-name]
  (= :notification (:kind (method-for handler-name))))

(defn request? [handler-name]
  (= :request (:kind (method-for handler-name))))

(defn server-initiated? [handler-name]
  (= :server->client (:direction (method-for handler-name))))

(defn all-handler-names [] (keys methods))

(defn all-commands
  "All request handler names, optionally filtered by domain."
  ([] (filter request? (all-handler-names)))
  ([domain]
   (let [d (name domain)]
     (filter #(and (request? %)
                   (= d (:domain (method-for %))))
             (all-handler-names)))))

(defn all-events
  ([] (filter notification? (all-handler-names)))
  ([domain]
   (let [d (name domain)]
     (filter #(and (notification? %)
                   (= d (:domain (method-for %))))
             (all-handler-names)))))

(defn domains
  "Sorted list of every domain defport's CDP spec knows about."
  []
  (->> methods vals (map :domain) distinct sort vec))

(defn domain-summary
  "Pretty stats — how many commands / events per domain. Useful for
   consumers building domain pickers."
  []
  (into (sorted-map)
        (for [d (domains)]
          [d {:commands (count (all-commands d))
              :events   (count (all-events d))}])))
