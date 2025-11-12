(ns defport.transports.http
  "HTTP transport for JSON-RPC protocols.

  Provides HTTP server for JSON-RPC 2.0 protocols.
  Platform-agnostic using reader conditionals for JVM (http-kit) vs Node.js (http module)."
  (:require [defport.core :as core]
            [cheshire.core :as json]
            #?(:clj [org.httpkit.server :as http-kit])))

#?(:clj
   (defrecord HttpTransport [port host cors-config server*]
     core/Transport
     (transport-id [_] :http)

     (transport-start [this handler]
       (let [app (fn [request]
                  (try
                    (condp = (:uri request)
                      "/rpc"
                      (let [body (slurp (:body request))
                            json-request (json/parse-string body true)
                            response (handler json-request)
                            json-response (json/generate-string response)]
                        {:status 200
                         :headers {"Content-Type" "application/json"
                                   "Access-Control-Allow-Origin" (get-in cors-config [:allow-origin] "*")
                                   "Access-Control-Allow-Methods" (clojure.string/join ", " (get-in cors-config [:allow-methods] ["POST" "OPTIONS"]))
                                   "Access-Control-Allow-Headers" (clojure.string/join ", " (get-in cors-config [:allow-headers] ["Content-Type"]))}
                         :body json-response})

                      "/health"
                      {:status 200
                       :headers {"Content-Type" "application/json"}
                       :body (json/generate-string {:status "healthy"
                                                    :transport "http"
                                                    :port port})}

                      "/info"
                      {:status 200
                       :headers {"Content-Type" "application/json"}
                       :body (json/generate-string {:name "defport-http"
                                                    :version "0.1.0"
                                                    :transport {:type "http"
                                                               :port port
                                                               :host host}})}

                      ;; 404 for unknown paths
                      {:status 404
                       :headers {"Content-Type" "application/json"}
                       :body (json/generate-string {:error "Not found"})})
                    (catch Exception e
                      {:status 500
                       :headers {"Content-Type" "application/json"}
                       :body (json/generate-string {:error (.getMessage e)})})))

             server (http-kit/run-server app {:port port :host host})]
         (reset! server* server)
         (println (str "✓ HTTP transport started on http://" host ":" port))
         server))

     (transport-stop [_]
       (when-let [server @server*]
         (server :timeout 100)
         (reset! server* nil)
         (println "✓ HTTP transport stopped")))

     (transport-send [_ message]
       ;; HTTP is request/response - sending happens in handler response
       ;; This is for async notifications (not yet implemented)
       (println "Warning: HTTP transport doesn't support async send yet")))

   :cljs
   (defrecord HttpTransport [port host cors-config server*]
     core/Transport
     (transport-id [_] :http)

     (transport-start [this handler]
       (if (exists? js/require)
         (let [http (js/require "http")
               server (.createServer http
                        (fn [req res]
                          (let [chunks (atom [])]
                            (.on req "data" (fn [chunk] (swap! chunks conj chunk)))
                            (.on req "end"
                              (fn []
                                (try
                                  (let [body (apply str (map #(.toString %) @chunks))
                                        json-request (js->clj (js/JSON.parse body) :keywordize-keys true)
                                        response (handler json-request)
                                        json-response (js/JSON.stringify (clj->js response))]
                                    (.writeHead res 200 #js {"Content-Type" "application/json"
                                                             "Access-Control-Allow-Origin" (get-in cors-config [:allow-origin] "*")})
                                    (.end res json-response))
                                  (catch js/Error e
                                    (.writeHead res 500 #js {"Content-Type" "application/json"})
                                    (.end res (js/JSON.stringify #js {:error (.-message e)})))))))))]
           (.listen server port host
             (fn []
               (println (str "✓ HTTP transport started on http://" host ":" port))))
           (reset! server* server)
           server)
         (throw (ex-info "HTTP transport not available in browser environment" {}))))

     (transport-stop [_]
       (when-let [server @server*]
         (.close server)
         (reset! server* nil)
         (println "✓ HTTP transport stopped")))

     (transport-send [_ message]
       (println "Warning: HTTP transport doesn't support async send yet"))))

(defn create-http-transport
  "Create an HTTP transport for JSON-RPC protocols.

  opts: map with:
    - :port - HTTP port (default 9876)
    - :host - bind host (default \"127.0.0.1\")
    - :cors - CORS configuration map with:
      - :allow-origin - allowed origin (default \"*\")
      - :allow-methods - allowed HTTP methods (default [\"POST\" \"OPTIONS\"])
      - :allow-headers - allowed headers (default [\"Content-Type\"])

  Returns HttpTransport instance.

  Endpoints:
  - POST /rpc - JSON-RPC 2.0 endpoint
  - GET /health - Health check
  - GET /info - Server information

  Platform support:
  - JVM: http-kit (production-ready)
  - Node.js: http module (full support)
  - Browser: Not supported (can't create servers)

  Example:
    (def transport (create-http-transport {:port 8080}))
    (transport-start transport my-handler)"
  ([]
   (create-http-transport nil))
  ([opts]
   (let [port (or (:port opts) 9876)
         host (or (:host opts) "127.0.0.1")
         cors (or (:cors opts) {})]
     #?(:clj
        (->HttpTransport port host cors (atom nil))
        :cljs
        (->HttpTransport port host cors (atom nil))))))
