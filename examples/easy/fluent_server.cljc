(ns fluent-server
  "Fluent API style - no macros, all data.

  This style is useful when tools are defined dynamically
  or loaded from configuration."
  (:require [defport :as mcp]))

(defn -main [& _]
  (-> (mcp/server "Fluent Demo")

      ;; Add tools with fluent API
      (mcp/add-tool "add"
                    '[a :int, b :int]
                    "Add two numbers"
                    #(+ (:a %) (:b %)))

      (mcp/add-tool "multiply"
                    '[a :int, b :int]
                    "Multiply two numbers"
                    #(* (:a %) (:b %)))

      (mcp/add-tool "greet"
                    '[name :string]
                    "Say hello"
                    #(str "Hello, " (:name %) "!"))

      (mcp/add-tool "echo"
                    '[message :string]
                    "Echo a message"
                    #(:message %))

      ;; Run!
      (mcp/run!)))