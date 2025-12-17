(ns quick-start
  "Minimal MCP server - copy this and run.

  JVM:   clj -M:examples -m quick-start --http 8080
  Node:  node target/quick_start.js --http 8080"
  (:require [defport :refer [defserver deftool defprompt defresource run!]])
  #?(:clj (:gen-class)))

;; =============================================================================
;; Server
;; =============================================================================

(defserver my-server "1.0.0")

;; =============================================================================
;; Define a tool
;; =============================================================================

(deftool search-code [query :- :string]
  "Search codebase for matching code."
  [{:file  "src/core.cljc"
    :line  42
    :match query}])

;; =============================================================================
;; Define a prompt
;; =============================================================================

(defprompt explain-code [code :- :string]
  "Generate a prompt to explain code."
  [{:role    "user"
    :content {:type "text"
              :text (str "Explain:\n" code)}}])

;; =============================================================================
;; Define a resource
;; =============================================================================

(defresource "schema://db"
  "Database schema definition."
  {:entities [:user :post :comment]})

;; =============================================================================
;; Start server
;; =============================================================================

(defn parse-port
  "Extract port number from args."
  [args]
  (or (some->> args
               (drop-while #(not= % "--http"))
               second
               #?(:clj  Integer/parseInt
                  :cljs js/parseInt))
      8080))

#?(:clj
   (defn -main
     "JVM entry point."
     [& args]
     (let [transport (if (some #{"--stdio"} args) :stdio :http)
           port      (parse-port args)]
       (run! {:transport transport :port port}))))

#?(:cljs
   (defn main
     "Node.js entry point."
     []
     (let [args      (js->clj (.-argv js/process))
           transport (if (some #{"--stdio"} args) :stdio :http)
           port      (parse-port args)]
       (run! {:transport transport :port port}))))

#?(:cljs (set! (.-exports js/module) #js {:main main}))
