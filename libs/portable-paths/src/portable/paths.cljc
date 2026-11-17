(ns portable.paths
  "Pure lexical paths with explicit :posix and :windows syntax."
  (:require [clojure.string :as str]))

(defn- throw-invalid-input [operation reason]
  (throw (ex-info "Invalid path input."
                  {:type :portable/invalid-input :operation operation :reason reason})))

(defn- validate-style [operation style]
  (when-not (#{:posix :windows} style)
    (throw-invalid-input operation :invalid-style)))

(defn- validate-path [operation path]
  (when-not (string? path)
    (throw-invalid-input operation :expected-string))
  (when (str/includes? path "\u0000")
    (throw-invalid-input operation :invalid-path)))

(defn- parse-path [operation path style]
  (validate-style operation style)
  (validate-path operation path)
  (let [text (if (= :windows style) (str/replace path "\\" "/") path)
        unc (when (= :windows style) (re-matches #"//([^/]+)/+([^/]+)(?:/([\s\S]*))?" text))
        drive (when (= :windows style) (re-matches #"([A-Za-z]:)(/)?([\s\S]*)" text))]
    (when (and (= :windows style) (re-find #"^//[?.](?:/|$)" text))
      (throw (ex-info "Windows device namespace paths are unsupported."
                      {:type :portable/unsupported-operation
                       :operation operation :reason :unsupported-path-form})))
    (let [[root body rooted?]
          (cond
            unc [(str "//" (nth unc 1) "/" (nth unc 2) "/") (or (nth unc 3) "") true]
            drive [(str (nth drive 1) (nth drive 2)) (nth drive 3) (some? (nth drive 2))]
            (str/starts-with? text "/") ["/" text true]
            :else ["" text false])]
      {:root root
       :segments (filterv #(not (empty? %)) (str/split body #"/+"))
       :rooted? rooted?
       :trailing-separator? (str/ends-with? text "/")})))

(defn- render-path [path style]
  (if (= :windows style) (str/replace path "/" "\\") path))

(defn- reduce-segment [rooted? segments segment]
  (cond
    (= "." segment) segments
    (not= ".." segment) (conj segments segment)
    (and (seq segments) (not= ".." (peek segments))) (pop segments)
    rooted? segments
    :else (conj segments "..")))

(defn- normalize-path [{:keys [root segments rooted? trailing-separator?]} style]
  (let [normalized (reduce (partial reduce-segment rooted?) [] segments)
        joined (str/join "/" normalized)
        ;; Keep a relative component from becoming a drive prefix after dot removal.
        body (if (and (= :windows style) (empty? root) (re-find #"^[A-Za-z]:" joined))
               (str "./" joined)
               joined)
        base (cond
               (seq body) (str root body)
               rooted? root
               :else (str root "."))
        result (if (and trailing-separator? (not (str/ends-with? base "/")))
                 (str base "/")
                 base)]
    (render-path result style)))

(defn normalize
  "Normalize separators and dot segments. Preserve trailing separators; empty becomes '.'."
  ([path] (normalize path :posix))
  ([path style]
   (normalize-path (parse-path :portable.paths/normalize path style) style)))

(defn join
  "Join a vector of path strings, then normalize. Later rooted parts do not replace earlier parts."
  ([paths] (join paths :posix))
  ([paths style]
   (validate-style :portable.paths/join style)
   (when-not (vector? paths)
     (throw-invalid-input :portable.paths/join :expected-path-vector))
   (doseq [path paths]
     (parse-path :portable.paths/join path style))
   (normalize-path (parse-path :portable.paths/join (str/join "/" (remove empty? paths)) style) style)))

(defn basename
  "Return the last component, ignoring trailing separators. Roots and empty paths return ''."
  ([path] (basename path :posix))
  ([path style]
   (or (peek (:segments (parse-path :portable.paths/basename path style))) "")))

(defn dirname
  "Return the lexical parent without resolving dot segments. Roots remain roots; no parent becomes '.'."
  ([path] (dirname path :posix))
  ([path style]
   (let [{:keys [root segments]} (parse-path :portable.paths/dirname path style)
         parent (str root (str/join "/" (when (seq segments) (pop segments))))]
     (render-path (if (empty? parent) "." parent) style))))

(defn extname
  "Return the last component's final dot suffix. Dotfiles, '.' and '..' have no extension."
  ([path] (extname path :posix))
  ([path style]
   (let [name (or (peek (:segments (parse-path :portable.paths/extname path style))) "")
         index (str/last-index-of name ".")]
     (if (and index (pos? index) (not= ".." name)) (subs name index) ""))))

(defn absolute?
  "Return whether a path has a root. Windows current-drive-rooted paths also return true."
  ([path] (absolute? path :posix))
  ([path style]
   (:rooted? (parse-path :portable.paths/absolute? path style))))
