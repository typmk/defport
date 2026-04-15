(ns industrial-mcp
  "MCP server exposing a mock industrial backend — demonstrates the
  pattern for wrapping SCADA / OPC UA / Modbus / DNP3 / IEC 61850
  backends as MCP tools Claude (or any MCP client) can call.

  Defport does *not* speak Modbus or OPC UA natively — the
  substrate is optimized for text-based JSON-RPC protocols
  (LSP/DAP/MCP/BSP/CDP/rosbridge). Industrial wire protocols are
  binary, often real-time, often hardware-routed. The right
  pattern is: put the industrial protocol library behind a plain
  Clojure function, then expose that function as an MCP tool via
  `(mcp/deftool ...)`. Defport handles everything on the AI side;
  your library (or JVM-interop into Eclipse Milo, j2mod,
  OpenDNP3, etc.) handles the OT side.

  This example uses an in-memory atom as the 'PLC' so it's
  self-contained. In a real deployment, `read-holding-register`
  would call `j2mod`, `browse-nodes` would call `Eclipse Milo`,
  `read-tag` would call `clj-opcua` or similar.

  ## Run

      clojure -M:examples -m industrial-mcp

  ## Validate via MCP Inspector

      npx @modelcontextprotocol/inspector \\
        clojure -M:examples -m industrial-mcp

  An AI assistant with this MCP server attached can now query the
  'PLC', write setpoints, and monitor alarms without knowing
  anything about Modbus framing."
  (:require [defport.mcp :as mcp]
            [defport.sugar :as sugar]))

;; ----- Mock SCADA backend ---------------------------------------------------
;;
;; In production this is the only piece you'd swap. Everything
;; below the backend stays identical.

(defonce ^:private plc
  (atom {:holding-registers {1 1200    ;; tank-1 level, mm
                             2 850     ;; pump-1 flow, L/min
                             3 42      ;; reactor-1 temp, °C
                             4 0       ;; ESTOP flag (0 = ok, 1 = tripped)
                             5 100}    ;; valve-1 position, 0-100%
         :alarms {}
         :tags {"Tank1.Level" 1200
                "Pump1.Flow"  850
                "Reactor1.Temperature" 42}}))

(defn- read-register [address]
  (get-in @plc [:holding-registers address]))

(defn- write-register! [address value]
  (swap! plc assoc-in [:holding-registers address] value)
  value)

(defn- read-tag [tag-name]
  (get-in @plc [:tags tag-name]))

(defn- browse-tags [prefix]
  (->> @plc :tags keys
       (filter #(clojure.string/starts-with? % (or prefix "")))
       vec))

(defn- trip-estop! []
  (swap! plc #(-> % (assoc-in [:holding-registers 4] 1)
                    (assoc-in [:alarms :estop]
                              {:severity "critical"
                               :message "Emergency stop activated"})))
  :tripped)

;; ----- MCP tools over the backend -------------------------------------------
;;
;; Each tool is a plain Clojure function wrapped in MCP content.
;; A real backend would swap the in-memory atom for Eclipse Milo,
;; j2mod, OpenDNP3, or whatever protocol library is in scope.

(mcp/deftool read-holding-register
  "Read a Modbus holding register by address."
  [address :- :int]
  (let [value (read-register address)]
    {:content [{:type "text"
                :text (if value
                        (str "Register " address " = " value)
                        (str "No register at address " address))}]}))

(mcp/deftool write-holding-register
  "Write a value to a Modbus holding register. Dangerous — real
   deployments should gate this behind authorization."
  [address :- :int value :- :int]
  (write-register! address value)
  {:content [{:type "text"
              :text (str "Wrote " value " to register " address)}]})

(mcp/deftool read-opcua-tag
  "Read a named OPC UA tag from the gateway."
  [tag :- :string]
  (let [value (read-tag tag)]
    {:content [{:type "text"
                :text (if (some? value)
                        (str tag " = " value)
                        (str "No tag named " tag))}]}))

(mcp/deftool browse-opcua-namespace
  "Browse the OPC UA namespace for tags matching a prefix."
  [prefix :- :string]
  {:content [{:type "text"
              :text (pr-str (browse-tags prefix))}]})

(mcp/deftool trip-emergency-stop
  "Trip the emergency stop. All motion halts. Requires manual reset."
  []
  (trip-estop!)
  {:content [{:type "text"
              :text "ESTOP tripped. Motion halted. Manual reset required."}]})

(mcp/deftool list-active-alarms
  "List all currently active alarms."
  []
  {:content [{:type "text"
              :text (pr-str (:alarms @plc))}]})

;; ----- Main -----------------------------------------------------------------

(defn -main [& _]
  (sugar/run! {:protocol :mcp
               :server-info {:name "defport-industrial-mcp-example"
                             :version "0.1.0"}}))
