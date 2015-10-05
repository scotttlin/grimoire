(ns grimoire.web.util
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [markdown.core :as md]
            [markdown.transformers :as md.t]
            [me.raynes.conch :refer [let-programs]]
            [grimoire.things :as t]
            [grimoire.api :as api]
            [grimoire.api.web :as web]
            [grimoire.either :as e]
            [grimoire.web.config :as cfg])
  (:import (java.net URLEncoder)))

(defn cheatsheet
  "Slurps in the cheatsheet off of the resource path and does the final
  rendering to HTML."
  [{:keys [baseurl clojure-version]}]
  (-> "cheatsheet.html"
      io/resource
      slurp
      (string/replace #"\{\{ site.baseurl \}\}" "")))

(def cheatsheet-memo
  "Since the cheatsheet isn't expected to change and is the highest traffic page
  on the site just memoize it."
  (memoize cheatsheet))

(defn resource-file-contents
  "Slurps a file if it exists, otherwise returning nil."
  [file]
  (let [file (io/file file)]
    (when (.exists file)
      (some-> file slurp))))

(def header-regex
  #"^---\n((?:[a-z-]+: [^\n]+\n)*)---\n")

(defn parse-markdown-page-header
  "Attempts to locate a Jekyll style markdown header in a \"page\" given as a
  string and parse it out into a Clojure map. Returns a (possibly empty) map."
  [page]
  (when-let [header (some->> page
                             (re-find header-regex)
                             second)]
    (->> (string/split header #"\n")
         (map #(string/split % #": "))
         (map (juxt (comp keyword first) second))
         (into {}))))

(def maybe
  (fn [x]
    (when (e/succeed? x)
      (e/result x))))

(def mem-sts->t
  (memoize
   (fn [t]
     (let [res (api/resolve-short-string
                (cfg/lib-grim-config) t)]
       (when (e/fail? res)
         (println "Failed looking up" t))
       (maybe res)))))

(defn mem-sts->link
  [s]
  {:pre [(string? s)]}
  (when-let [t (mem-sts->t s)]
    [:a {:href (web/make-html-url (cfg/web-config) t)} s]))

(defn mem-sts->md-link
  [s]
  (if-let [t (-> s mem-sts->t)]
    (format "[%s](%s)"
            (string/replace s "*" "\\*")
            (web/make-html-url (cfg/web-config) t))
    s))

(declare highlight-text)

(defn markdown-highlight [text state]
  (let [text (string/join text)]
    [(string/replace text
                     #"(?si)```(\w*)\n\r?(.*?)```"
                     (fn [[_ lang code :as match]]
                       (highlight-text lang code)))
     state]))

(defn thing-string->link [s]
  (-> (get s 0)
      (mem-sts->md-link)))

(defn markdown-string [m]
  (-> m
      (string/replace t/short-string-pattern thing-string->link)
      (md/md-to-html-string :replacement-transformers
                            (list* markdown-highlight
                                   md.t/transformer-vector))))

(defn parse-markdown-page
  "Attempts to slurp a markdown file from the resource path, returning a
  pair [header-map, html-string]."
  [page]
  (when-let [raw (some-> page (str ".md") io/resource slurp)]
    [(or (parse-markdown-page-header raw) {})
     (-> raw (string/replace header-regex "") markdown-string)]))

(def markdown-file
  "Helper for rendering a file on the resource path to HTML via markdown."
  (comp markdown-string resource-file-contents))

(defn highlight-text
  "Helper for rendering a string of program source to syntax
  highlighted HTML via pygmentize."
  [lang text]
  (let-programs [pygmentize "pygmentize"]
                (pygmentize "-fhtml"
                            (str "-l" lang)
                            "-Ostripnl=False,encoding=utf-8"
                            {:in text})))

(def highlight-clojure
  (partial highlight-text "clojure"))

(defn highlight-example
  "Helper for rendering a Grimoire Example Thing to HTML, using a filesystem
  cache of rendered examples to improve performance."
  [ex]
  {:pre [(t/example? ex)]}
  (let [url        (:grimoire.things/url ex)
        ex-file    (:handle ex)
        cache-dir  (io/file "render-cache")
        cache-file (io/file cache-dir (str (hash url) ".html"))] ;; FIXME: use a different hash algo?
    (if-not (.exists cache-dir)
      (.mkdirs cache-dir))

    (if (.exists cache-file)
      (slurp cache-file)

      (let [text (e/result (api/read-example (cfg/lib-grim-config) ex))
            html (highlight-clojure text)]
        (spit cache-file html)
        html))))

(defn url-encode
  "Returns an UTF-8 URL encoded version of the given string."
  [unencoded]
  (URLEncoder/encode unencoded "UTF-8"))

(defn moved-permanently
  "Returns a Ring response for a HTTP 301 'moved permanently' redirect."
  {:added "1.3"}
  ;; FIXME: remove for ring-clojure/ring#181
  [url]
  {:status  301
   :headers {"Location" url}
   :body    ""})

(defn moved-temporary
  "Returns a Ring response for a HTTP 307 'moved temporarily' redirect."
  [url]
  {:status  307
   :headers {"Location" url}
   :body    ""})

(def normalize-type
  {:html              :text/html
   :text/html         :text/html
   "html"             :text/html
   "text/html"        :text/html

   :text              :text/plain
   :text/plain        :text/plain
   "text"             :text/plain
   "text/plain"       :text/plain

   "json"             :application/json
   :json              :application/json
   "application/json" :application/json
   :application/json  :application/json

   "edn"              :application/edn
   :edn               :application/edn
   "application/edn"  :application/edn
   :application/edn   :application/edn
   })
