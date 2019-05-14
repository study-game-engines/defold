(ns editor.code.script-intelligence
  (:require [dynamo.graph :as g]
            [editor.graph-util :as gu]
            [schema.core :as s]))

(g/deftype ScriptCompletions {s/Str [{s/Keyword s/Any}]})

(g/defnk produce-lua-completions
  [lua-completions]
  (reduce merge {} lua-completions))

(g/defnode ScriptIntelligenceNode
  (input lua-completions ScriptCompletions :array :substitute gu/array-subst-remove-errors)
  (output lua-completions ScriptCompletions :cached produce-lua-completions))

(defn lua-completions
  [script-intelligence]
  (g/node-value script-intelligence :lua-completions))

