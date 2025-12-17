(ns minimal-server
  "The simplest possible MCP server - matches FastMCP's minimal example.

  FastMCP (Python):
    from fastmcp import FastMCP
    mcp = FastMCP('Demo')
    @mcp.tool
    def add(a: int, b: int) -> int:
        return a + b
    mcp.run()

  Defport (Clojure):
    (ns minimal-server
      (:require [defport :refer [defserver deftool run!]]))
    (defserver demo)
    (deftool add [a :- :int, b :- :int] (+ a b))
    (run!)"
  (:require [defport :refer [defserver deftool run!]]))

(defserver demo)

(deftool add [a :- :int, b :- :int]
  "Add two numbers"
  (+ a b))

(defn -main [& _]
  (run!))