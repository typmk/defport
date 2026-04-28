(ns defport.cdp.sugar
  "Macro-driven bulk helper generation for CDP.

   defport.cdp.client ships typed helpers for ~20 of the 664 CDP
   commands — the 90% common surface (Page, Runtime, DOM, Network,
   Browser, Target, Input). For the remaining tail, consumers either
   call `cdp.client/request!` with a spec keyword
   (`(request! client :Accessibility/getFullAXTree {})`) or use this
   namespace to intern a typed helper for every command in a domain.

   ### Usage

     (require '[defport.cdp.sugar :refer [defcdp-domain]])

     (defcdp-domain Accessibility)
     ;; => interns `accessibility-enable`, `accessibility-disable`,
     ;;    `accessibility-get-partial-ax-tree`, ... in the current ns.

   Each generated helper has the shape:

     (defn <domain>-<command>
       \"<wire-method>\\n\\n<upstream doc>\"
       ([client] (request! client :<Domain>/<command> {}))
       ([client params] (request! client :<Domain>/<command> params)))

   Experimental / deprecated commands are included and tagged in the
   docstring — different Chromium versions expose different subsets.

   ### Design notes

   - JVM-only: the macro reads `defport.cdp.spec/methods` at expansion
     time, which loads two JSON files via `clojure.java.io/resource`.
     That's already a JVM-only path, so this namespace is `.clj`.
   - No `defcdp-all` helper: interning 664 fns in one namespace is
     almost never what consumers want. Domain-at-a-time is the grain
     that matches real usage (a browser-automation library pulls in
     Page + DOM + Runtime; a network interceptor pulls in Network +
     Fetch; an accessibility tool pulls in Accessibility). If a
     consumer truly wants everything, they can list the domains they
     care about in their own namespace."
  (:require [clojure.string :as str]
            [defport.cdp.spec :as spec]
            [defport.cdp.client :as client]))

(defn- camel->kebab [s]
  (-> s
      (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
      (str/replace #"([A-Z]+)([A-Z][a-z])" "$1-$2")
      str/lower-case))

(defn- helper-sym [domain cmd-name]
  (symbol (str (str/lower-case domain) "-" (camel->kebab cmd-name))))

(defn- build-docstring [wire doc experimental? deprecated?]
  (cond-> (str wire (when (seq doc) (str "\n\n" doc)))
    experimental? (str "\n\n(experimental)")
    deprecated?   (str "\n\n(deprecated)")))

(defmacro defcdp-domain
  "Intern a typed helper fn for every command in CDP `domain`
   (a symbol or keyword matching the domain name, case-sensitive,
   e.g. `Page`, `Accessibility`, `Network`).

   Events are NOT generated — CDP events are dispatched via
   `defport.cdp.client/on-event` and don't need per-event helpers.

   Throws at macro-expansion time if the domain is unknown."
  [domain]
  (let [d     (name domain)
        known (set (spec/domains))]
    (when-not (contains? known d)
      (throw (ex-info (str "Unknown CDP domain: " d
                           ". Known: " (pr-str (sort known)))
                      {:domain d})))
    (let [cmds (spec/all-commands d)]
      (when (empty? cmds)
        (throw (ex-info (str "Domain " d " has no commands") {:domain d})))
      `(do
         ~@(for [handler-kw cmds
                 :let [entry    (spec/method-for handler-kw)
                       cmd-name (name handler-kw)
                       sym      (helper-sym d cmd-name)
                       wire     (:method entry)
                       doc      (:doc entry)
                       exp?     (:experimental entry)
                       dep?     (:deprecated entry)
                       docstr   (build-docstring wire doc exp? dep?)]]
             `(defn ~sym
                ~docstr
                ([client#] (client/request! client# ~handler-kw {}))
                ([client# params#] (client/request! client# ~handler-kw params#))))
         ~(count cmds)))))
