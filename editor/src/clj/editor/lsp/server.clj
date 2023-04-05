;; Copyright 2020-2022 The Defold Foundation
;; Copyright 2014-2020 King
;; Copyright 2009-2014 Ragnar Svensson, Christian Murray
;; Licensed under the Defold License version 1.0 (the "License"); you may not use
;; this file except in compliance with the License.
;;
;; You may obtain a copy of the License, together with FAQs at
;; https://www.defold.com/license
;;
;; Unless required by applicable law or agreed to in writing, software distributed
;; under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
;; CONDITIONS OF ANY KIND, either express or implied. See the License for the
;; specific language governing permissions and limitations under the License.

(ns editor.lsp.server
  (:require [clojure.core.async :as a :refer [<! >!]]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [dynamo.graph :as g]
            [editor.code.data :as data]
            [editor.lsp.async :as lsp.async]
            [editor.lsp.base :as lsp.base]
            [editor.lsp.jsonrpc :as lsp.jsonrpc]
            [editor.lua :as lua]
            [editor.resource :as resource]
            [editor.util :as util]
            [editor.workspace :as workspace]
            [service.log :as log])
  (:import [editor.code.data Cursor CursorRange]
           [java.io File InputStream]
           [java.lang ProcessHandle ProcessBuilder$Redirect]
           [java.net URI]
           [java.util Map]
           [java.util.concurrent TimeUnit]
           [java.util.function BiFunction]))

(set! *warn-on-reflection* true)

(defprotocol Connection
  (^InputStream input-stream [connection])
  (^InputStream output-stream [connection])
  (dispose [connection]))

(extend-protocol Connection
  Process
  (input-stream [process]
    (.getInputStream process))
  (output-stream [process]
    (.getOutputStream process))
  (dispose [process]
    (when (.isAlive process)
      (.destroy process)
      (-> process
          .onExit
          (.orTimeout 10 TimeUnit/SECONDS)
          (.handle (reify BiFunction
                     (apply [_ _ timeout]
                       (if timeout
                         (do (log/warn :message "Language server process didn't exit gracefully in time, terminating")
                             (.destroyForcibly process))
                         (when-not (zero? (.exitValue process))
                           (log/warn :message "Language server process exit code is not zero"
                                     :exit-code (.exitValue process)))))))))))

(defprotocol Launcher
  (launch [launcher ^File directory]))

(extend-protocol Launcher
  Map
  (launch [{:keys [command]} ^File directory]
    {:pre [(vector? command)]}
    ;; We need to resolve command in addition to setting ProcessBuilder's directory,
    ;; since the latter only affects the current directory of the spawned process,
    ;; and does not affect executable resolution
    (let [resolved-command (update command 0 #(str (.resolve (.toPath directory) (.toPath (io/file %)))))]
      (.start
        (doto (ProcessBuilder. ^"[Ljava.lang.String;" (into-array String resolved-command))
          (.redirectError ProcessBuilder$Redirect/INHERIT)
          (.directory directory))))))

(defprotocol Message
  (->jsonrpc [input project on-response]))

(extend-protocol Message
  Object
  (->jsonrpc [input _ _] input)
  nil
  (->jsonrpc [input _ _] input))

(deftype RawRequest [notification result-converter]
  Message
  (->jsonrpc [this _ _]
    (throw (ex-info "Can't send raw request: use finalize-request first" {:request this}))))

(defn finalize-request [^RawRequest raw-request id]
  (reify Message
    (->jsonrpc [_ project on-response]
      (let [ch (a/chan 1)
            result-converter (.-result-converter raw-request)
            request (lsp.jsonrpc/notification->request (.-notification raw-request) ch)]
        (a/take! ch (fn [response]
                      (on-response
                        id
                        (cond-> response (not (:error response)) (update :result result-converter project)))))
        request))))

(defn- raw-request [notification result-converter]
  (->RawRequest notification result-converter))

(defn- make-uri-string [abs-path]
  (let [path (if (util/is-win32?)
               (str "/" (string/replace abs-path "\\" "/"))
               abs-path)]
    (str (URI. "file" "" path nil))))

(defn resource-uri [resource]
  (make-uri-string (resource/abs-path resource)))

(defn- root-uri [workspace evaluation-context]
  (make-uri-string (g/node-value workspace :root evaluation-context)))

(defn- maybe-resource [project uri evaluation-context]
  (let [workspace (g/node-value project :workspace evaluation-context)]
    (when-let [proj-path (workspace/as-proj-path workspace (.getPath (URI. uri)) evaluation-context)]
      (workspace/find-resource workspace proj-path evaluation-context))))

;; diagnostics
(s/def ::severity #{:error :warning :information :hint})
(s/def ::message string?)
(s/def ::cursor #(instance? Cursor %))
(s/def ::cursor-range #(instance? CursorRange %))
(s/def ::diagnostic (s/and ::cursor-range (s/keys :req-un [::severity ::message])))
(s/def ::result-id string?)
(s/def ::version int?)
(s/def ::items (s/coll-of ::diagnostic))
(s/def ::diagnostics-result (s/keys :req-un [::items]
                                    :opt-un [::result-id
                                             ::version]))

;; capabilities
(s/def ::open-close boolean?)
(s/def ::change #{:none :full :incremental})
(s/def ::text-document-sync (s/keys :req-un [::open-close ::change]))
(s/def ::pull-diagnostics #{:none :text-document :workspace})
(s/def ::capabilities (s/keys :req-un [::text-document-sync ::pull-diagnostics]))

(defn- lsp-position->editor-cursor [{:keys [line character]}]
  (data/->Cursor line character))

(defn- editor-cursor->lsp-position [{:keys [row col]}]
  {:line row
   :character col})

(defn- lsp-range->editor-cursor-range [{:keys [start end]}]
  (data/->CursorRange
    (lsp-position->editor-cursor start)
    (lsp-position->editor-cursor end)))

(defn- editor-cursor-range->lsp-range [{:keys [from to]}]
  {:start (editor-cursor->lsp-position from)
   :end (editor-cursor->lsp-position to)})

(defn- lsp-diagnostic->editor-diagnostic [{:keys [range message severity]}]
  (assoc (lsp-range->editor-cursor-range range)
    :message message
    :severity ({1 :error 2 :warning 3 :information 4 :hint} severity :error)))

(defn- lsp-diagnostic-result->editor-diagnostic-result
  [{:keys [resultId version] :as lsp-diagnostics-result} diagnostics-key]
  (cond-> {:items (mapv lsp-diagnostic->editor-diagnostic (get lsp-diagnostics-result diagnostics-key))}
          resultId
          (assoc :result-id resultId)
          version
          (assoc :version version)))

(defn- diagnostics-handler [project out on-publish-diagnostics]
  (fn [{:keys [uri] :as result}]
    (lsp.async/with-auto-evaluation-context evaluation-context
      (when-let [resource (maybe-resource project uri evaluation-context)]
        (let [result (lsp-diagnostic-result->editor-diagnostic-result result :diagnostics)]
          (a/put! out (on-publish-diagnostics resource result)))))))

(def lsp-text-document-sync-kind-incremental 2)
(def lsp-text-document-sync-kind-full 1)
(def lsp-text-document-sync-kind-none 0)

(defn- lsp-text-document-sync-kind->editor-sync-kind [n]
  (condp = n
    lsp-text-document-sync-kind-none :none
    lsp-text-document-sync-kind-full :full
    lsp-text-document-sync-kind-incremental :incremental))

(defn- lsp-capabilities->editor-capabilities [{:keys [textDocumentSync diagnosticProvider]}]
  {:text-document-sync (cond
                         (nil? textDocumentSync)
                         {:change :none :open-close false}

                         (number? textDocumentSync)
                         {:open-close true
                          :change (lsp-text-document-sync-kind->editor-sync-kind textDocumentSync)}

                         (map? textDocumentSync)
                         {:open-close (:openClose textDocumentSync false)
                          :change (lsp-text-document-sync-kind->editor-sync-kind (:change textDocumentSync 0))}

                         :else
                         (throw (ex-info "Invalid text document sync kind" {:value textDocumentSync})))
   :pull-diagnostics (cond
                       (nil? diagnosticProvider) :none
                       (:workspaceDiagnostics diagnosticProvider) :workspace
                       :else :text-document)})

(defn- configuration-handler [project]
  (fn [{:keys [items]}]
    (lsp.async/with-auto-evaluation-context evaluation-context
      (mapv
        (fn [{:keys [section]}]
          (case section
            "Lua"
            (let [script-intelligence (g/node-value project :script-intelligence evaluation-context)
                  completions (g/node-value script-intelligence :lua-completions evaluation-context)]
              {:diagnostics {:globals (into lua/defined-globals
                                            (lua/extract-globals-from-completions completions))}})

            "files.associations"
            (let [workspace (g/node-value project :workspace evaluation-context)
                  resource-types (g/node-value workspace :resource-types evaluation-context)]
              (into {}
                    (keep (fn [[ext {:keys [textual? language]}]]
                            (when (and textual? (not= "plaintext" language))
                              [(str "*." ext) language])))
                    resource-types))

            "files.exclude"
            ["/build" "/.internal"]

            nil))
        items))))

(defn- initialize [jsonrpc project]
  (lsp.jsonrpc/request!
    jsonrpc
    "initialize"
    (lsp.async/with-auto-evaluation-context evaluation-context
      (let [uri (root-uri (g/node-value project :workspace evaluation-context) evaluation-context)
            title ((g/node-value project :settings evaluation-context) ["project" "title"])]
        {:processId (.pid (ProcessHandle/current))
         :rootUri uri
         :capabilities {:workspace {:diagnostics {}}}
         :workspaceFolders [{:uri uri :name title}]}))
    (* 60 1000)))

(defn make
  "Start language server process and perform its lifecycle as defined by LSP

  The process will perform server initialization, then it will execute all
  server actions received from in until the channel closes, then it will shut
  the server down. The output channel will close when the server shuts down,
  either because it was requested or due to an error.

  Required args:
    project     defold project node id
    launcher    the server launcher, e.g. a map with following keys:
                  :command    a shell command to launch the language process,
                              vector of strings, required
    in          input channel that server will take items from to execute,
                items are server actions created by other public fns in this ns
    out         output channel that server can submit values to, valid values
                are those produced by calling kv-arg callbacks; will be closed
                when the server shuts down (either because it was requested or
                due to an error)

  Required kv-args:
    :on-publish-diagnostics    a function of 2 args, resource and diagnostics
                               vector, produces a value that can be submitted to
                               out channel to notify the process higher in the
                               hierarchy about published diagnostics
    :on-initialized            a function of 1 arg, a server capabilities map,
                               produces a value for out channel
    :on-response               a function of 2 args: request id and response,
                               produces a value for out channel

  Returns a channel that will close when the LSP server closes.

  See also:
    https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#lifeCycleMessages"
  [project launcher in out & {:keys [on-publish-diagnostics
                                     on-initialized
                                     on-response]}]
  (a/go
    (try
      (let [directory (lsp.async/with-auto-evaluation-context evaluation-context
                        (let [workspace (g/node-value project :workspace evaluation-context)]
                          (workspace/project-path workspace evaluation-context)))
            connection (<! (a/thread (try (launch launcher directory) (catch Throwable e e))))]
        (if (instance? Throwable connection)
          (log/error :message "Language server process failed to start"
                     :launcher launcher
                     :exception connection)
          (try
            (let [[base-source base-sink base-err] (lsp.base/make (input-stream connection) (output-stream connection))
                  jsonrpc (lsp.jsonrpc/make
                            {"textDocument/publishDiagnostics" (diagnostics-handler project out on-publish-diagnostics)
                             "workspace/diagnostic/refresh" (constantly nil)
                             "workspace/configuration" (configuration-handler project)
                             "window/showMessageRequest" (constantly nil)
                             "window/workDoneProgress/create" (constantly nil)}
                            base-source
                            base-sink)
                  on-response #(a/put! out (on-response %1 %2))]
              (a/alt!
                (a/go
                  (try
                    ;; LSP lifecycle: initialization
                    (let [{:keys [capabilities]} (lsp.jsonrpc/unwrap-response (<! (initialize jsonrpc project)))]
                      (>! out (on-initialized (lsp-capabilities->editor-capabilities capabilities)))
                      (>! jsonrpc (lsp.jsonrpc/notification "initialized"))
                      ;; LSP lifecycle: serve requests
                      (<! (lsp.async/pipe (a/map #(->jsonrpc % project on-response) [in]) jsonrpc false))
                      ;; LSP lifecycle: shutdown
                      (lsp.jsonrpc/unwrap-response (<! (lsp.jsonrpc/request! jsonrpc "shutdown" (* 10 1000))))
                      (>! jsonrpc (lsp.jsonrpc/notification "exit"))
                      (a/close! jsonrpc)
                      ;; give server some time to exit
                      (<! (a/timeout 5000)))
                    nil
                    (catch Throwable e e)))
                ([maybe-e]
                 (when maybe-e (throw maybe-e)))

                base-err
                ([e] (throw e))))

            (catch Throwable e
              (log/warn :message "Language server failed"
                        :launcher launcher
                        :exception e))
            (finally
              (when-let [e (<! (a/thread (try (dispose connection) nil (catch Throwable e e))))]
                (log/warn :message "Failed to dispose language server"
                          :launcher launcher
                          :exception e))))))
      (finally
        (a/close! out)))))

(defn- full-or-unchanged-diagnostic-result:lsp->editor [{:keys [kind] :as result}]
  (case kind
    "full" {:type :full
            :result (lsp-diagnostic-result->editor-diagnostic-result result :items)}
    "unchanged" {:type :unchanged
                 :result-id (:resultId result)}))

(defn pull-workspace-diagnostics [resource->previous-result-id result-converter]
  (raw-request
    (lsp.jsonrpc/notification
      "workspace/diagnostic"
      {:previousResultIds (into []
                                (map (fn [[resource previous-result-id]]
                                       {:uri (resource-uri resource)
                                        :value previous-result-id}))
                                resource->previous-result-id)})
    ;; bound-fn only needed for tests to pick up the test system
    (bound-fn convert-result [{:keys [items]} project]
      (result-converter
        (lsp.async/with-auto-evaluation-context evaluation-context
          (into {}
                (keep
                  (fn [{:keys [uri] :as item}]
                    (when-let [resource (maybe-resource project uri evaluation-context)]
                      [resource (full-or-unchanged-diagnostic-result:lsp->editor item)])))
                items))))))

(defn pull-document-diagnostics
  [resource previous-result-id result-converter]
  (raw-request
    (lsp.jsonrpc/notification
      "textDocument/diagnostic"
      (cond-> {:textDocument {:uri (resource-uri resource)}}
              previous-result-id
              (assoc :previousResultId previous-result-id)))
    (fn convert-result [result _]
      (result-converter (full-or-unchanged-diagnostic-result:lsp->editor result)))))

(defn open-text-document
  "See also:
    https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#textDocument_didOpen"
  [resource lines]
  (lsp.jsonrpc/notification
    "textDocument/didOpen"
    {:textDocument {:uri (resource-uri resource)
                    :languageId (resource/language resource)
                    :version 0
                    :text (slurp (data/lines-reader lines))}}))

(defn close-text-document
  "See also:
    https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#textDocument_didClose"
  [resource]
  (lsp.jsonrpc/notification
    "textDocument/didClose"
    {:textDocument {:uri (resource-uri resource)}}))

(defn full-text-document-change
  "See also:
    https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#textDocument_didChange"
  [resource lines version]
  (lsp.jsonrpc/notification
    "textDocument/didChange"
    {:textDocument {:uri (resource-uri resource)
                    :version version}
     :contentChanges [{:text (slurp (data/lines-reader lines))}]}))

(defn incremental-document-change
  "See also:
    https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#textDocument_didChange"
  [resource incremental-diff version]
  (lsp.jsonrpc/notification
    "textDocument/didChange"
    {:textDocument {:uri (resource-uri resource)
                    :version version}
     :contentChanges (into []
                           (map (fn [[cursor-range replacement]]
                                  {:range (editor-cursor-range->lsp-range cursor-range)
                                   :text (string/join "\n" replacement)}))
                           incremental-diff)}))

(defn watched-file-change
  "See also:
    https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#workspace_didChangeWatchedFiles"
  [resource+change-types]
  (lsp.jsonrpc/notification
    "workspace/didChangeWatchedFiles"
    {:changes (mapv (fn [[resource change-type]]
                      {:uri (resource-uri resource)
                       :type (case change-type
                               :created 1
                               :changed 2
                               :deleted 3)})
                    resource+change-types)}))