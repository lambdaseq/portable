(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util.concurrent TimeUnit]))

(def project
  {:version "0.1.0"
   :url "https://github.com/lambdaseq/portable"
   :license nil
   :scm nil})

(def modules
  '{:portable {:kind :lib
               :dir "libs/portable"
               :platform :clj
               :lib lambdaseq/portable
               :test? false
               :description "All portable API families."}
    :portable-clock {:kind :lib
                     :dir "libs/portable-clock"
                     :platform :cljc
                     :test-runner portable.clock-test-runner
                     :browser-tests "target/browser.html"
                     :lib lambdaseq/portable-clock
                     :description "Portable wall and monotonic clocks."}
    :portable-bytes {:kind :lib
                     :dir "libs/portable-bytes"
                     :platform :cljc
                     :test-runner portable.bytes-test-runner
                     :browser-tests "target/browser.html"
                     :lib lambdaseq/portable-bytes
                     :description "Portable UTF-8, Base64, and hex conversion."}
    :portable-env {:kind :lib
                   :dir "libs/portable-env"
                   :platform :cljc
                   :test-runner portable.env-test-runner
                   :browser-tests "target/browser.html"
                   :test-env {"PORTABLE_ENV_TEST_VALUE" "  portable-env λ=one\nline two  "
                              "PORTABLE_ENV_TEST_EMPTY" ""
                              "PORTABLE_ENV_TEST_NUMBER" "00123"
                              "PORTABLE_ENV_TEST_FALSE" "false"}
                   :lib lambdaseq/portable-env
                   :description "Portable environment variable lookup."}
    :portable-paths {:kind :lib
                     :dir "libs/portable-paths"
                     :platform :cljc
                     :test-runner portable.paths-test-runner
                     :browser-tests "target/browser.html"
                     :lib lambdaseq/portable-paths
                     :description "Lexical POSIX and Windows path operations."}})

(defn- select-modules [opts accepts?]
  (let [requested (:modules opts)
        ids (cond (nil? requested) (sort (keys modules))
                  (keyword? requested) [requested]
                  (sequential? requested) (distinct requested)
                  :else (throw (ex-info ":modules must be a keyword or sequence of keywords." {})))]
    (->> ids
         (mapv (fn [id]
                 (let [module (or (get modules id) (throw (ex-info "Unknown module." {:module id :available (keys modules)})))]
                   (when (and requested (not (accepts? module)))
                     (throw (ex-info "Module does not support this task." {:module id})))
                   [id module])))
         (filterv #(accepts? (second %))))))

(defn- resolve-version [module opts]
  (let [value (or (:version opts) (:version module) (:version project))]
    (when-not (and (string? value) (re-matches #"[0-9][A-Za-z0-9.+-]*" value)
                   (not (str/includes? value "SNAPSHOT")))
      (throw (ex-info "Set an explicit release version, such as 0.1.0." {:version value})))
    value))

(defn version "Print the workspace version, or an explicit :version override." [opts]
  (let [value (resolve-version {} opts)] (println value) value))

(defn- resolve-module-directory [module]
  (let [root (.toPath (.getCanonicalFile (io/file ".")))
        directory (.toPath (.getCanonicalFile (io/file (:dir module))))]
    (when (or (= root directory) (not (.startsWith directory root)))
      (throw (ex-info "Module directory must be inside the workspace." {:dir (:dir module)})))
    (let [target (.toPath (.getCanonicalFile (io/file (.toFile directory) "target")))]
      (when (or (= target directory) (not (.startsWith target directory)))
        (throw (ex-info "Module target directory escapes the module." {:dir (:dir module)}))))
    (str (.toFile directory))))

(defn- run-command
  ([directory args] (run-command directory args nil))
  ([directory args environment]
   (let [result (b/process {:dir directory :command-args args :env environment})]
     (when-not (zero? (:exit result))
       (throw (ex-info "Command failed." (assoc result :dir directory :command args)))))))

(defn- create-publication-basis [basis modules-by-directory opts]
  ;; tools.deps resolves local roots to absolute paths before this conversion.
  (update basis :libs
          (fn [libs]
            (reduce-kv
             (fn [result lib coord]
               (if-let [local (:local/root coord)]
                 (let [module (or (get modules-by-directory local)
                                  (throw (ex-info "Register local dependencies in modules before packaging."
                                                  {:lib lib :directory local})))]
                   (assoc result (:lib module)
                          (assoc (select-keys coord [:dependents :exclusions :optional])
                                 :mvn/version (resolve-version module opts))))
                 (do
                   (when (and (empty? (:dependents coord)) (nil? (:mvn/version coord)))
                     (throw (ex-info "Published dependencies need Maven coordinates." {:lib lib})))
                   (assoc result lib coord))))
             {} libs))))

(defn- create-pom-data [module]
  (cond-> [[:description (or (:description module) (str (:lib module)))]]
    (:url project) (conj [:url (:url project)])
    (:license project) (conj [:licenses [:license [:name (get-in project [:license :name])]
                                         [:url (get-in project [:license :url])]]])))

(defn- build-artifact [id module opts uber?]
  (let [directory (resolve-module-directory module)]
    (b/with-project-root directory
      ;; Publish module dependencies without CLI defaults or user overrides.
      (let [basis (b/create-basis {:project "deps.edn" :root nil :user nil})
            registered (into {} (map (fn [[_ candidate]]
                                       [(resolve-module-directory candidate) candidate])) modules)
            paths (filterv #(.isDirectory (io/file directory %)) (:paths basis))
            artifact {:lib (:lib module) :version (resolve-version module opts)
                      :class-dir "target/classes" :src-dirs (filterv #(not= "resources" %) paths)
                      :resource-dirs (filterv #(= "resources" %) paths) :scm (:scm project)
                      :basis (if (= :lib (:kind module)) (create-publication-basis basis registered opts) basis) :pom-data (create-pom-data module)}
            artifact (assoc artifact :jar-file (str "target/" (name (:lib module)) "-" (:version artifact) ".jar"))]
        (b/delete {:path "target/classes"})
        (b/copy-dir {:src-dirs paths :target-dir "target/classes"})
        (b/write-pom artifact)
        (if uber?
          (do
            (b/compile-clj {:basis basis :class-dir "target/classes" :ns-compile [(:main module)]})
            (b/uber {:basis basis :class-dir "target/classes" :uber-file (:jar-file artifact) :main (:main module)}))
          (b/jar artifact))
        (println (name id) "->" (str (:dir module) "/" (:jar-file artifact)))
        (assoc artifact :directory directory :module id)))))

(defn clean "Delete selected modules' target directories." [opts]
  (doseq [[_ module] (select-modules opts (constantly true))]
    (b/with-project-root (resolve-module-directory module) (b/delete {:path "target"})))
  opts)

(defn- resolve-browser []
  (let [configured (System/getenv "CHROME_BIN")
        candidates (if configured
                     [configured]
                     (cons "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
                           (for [directory (str/split (or (System/getenv "PATH") "")
                                                     (re-pattern File/pathSeparator))
                                 executable ["google-chrome" "google-chrome-stable" "chromium" "chromium-browser" "chrome.exe"]]
                             (str (io/file directory executable)))))]
    (or (some #(let [file (io/file %)]
                 (when (and (.isFile file) (.canExecute file)) %)) candidates)
        (throw (ex-info "Chrome or Chromium is required. Set CHROME_BIN to its executable path."
                        {:chrome-bin configured})))))

(defn- run-browser-tests [directory page]
  (let [browser (resolve-browser)
        profile (Files/createTempDirectory "portable-browser-" (make-array FileAttribute 0))
        output (io/file directory "target/browser-results.html")
        errors (io/file directory "target/browser-stderr.log")]
    (try
      (let [command [browser "--headless" "--disable-gpu" "--no-first-run" "--no-default-browser-check"
                     (str "--user-data-dir=" profile)
                     "--dump-dom" "--virtual-time-budget=1000" "--timeout=10000"
                     (str (.toURI (io/file directory page)))]
            process (-> (ProcessBuilder. ^java.util.List command)
                        (.redirectOutput output)
                        (.redirectError errors)
                        (.start))]
        (when-not (.waitFor process 30 TimeUnit/SECONDS)
          (.destroyForcibly process)
          (.waitFor process 5 TimeUnit/SECONDS)
          (throw (ex-info "Browser tests timed out." {:output (str output) :errors (str errors)})))
        (let [document (slurp output)]
          (when-not (and (zero? (.exitValue process))
                         (str/includes? document "data-test-status=\"passed\""))
            (throw (ex-info "Browser contract tests failed or did not finish."
                            {:exit (.exitValue process) :output (str output)
                             :errors (str errors) :document document})))
          (println "Browser contracts passed. Results:" (str output))))
      (finally
        (b/delete {:path (str profile)})))))

(defn test "Run shared contracts on JVM, Babashka, Node, and headless Chrome." [opts]
  (doseq [[id module] (select-modules opts (constantly true))
          :when (not= false (:test? module))]
    (let [directory (resolve-module-directory module)]
      (println "Testing" (name id))
      (run-command directory ["clojure" "-M:test"] (:test-env module))
      (when (= :cljc (:platform module))
        (run-command directory ["bb" "--classpath" (str/join File/pathSeparator ["src" "test" "../../test-support"])
                                "-m" (str (:test-runner module))] (:test-env module))
        (run-command directory ["clojure" "-M:test-cljs"]))
      (when (#{:cljs :cljc} (:platform module))
        (run-command directory ["node" "target/tests.js"] (:test-env module)))
      (when-let [page (:browser-tests module)]
        (run-command directory ["clojure" "-M:test-browser"])
        (b/copy-file {:src "test-support/browser.html" :target (str (io/file directory page))})
        (run-browser-tests directory page))))
  opts)

(defn jar "Build source jars for libraries. Accepts :modules and :version." [opts]
  (mapv (fn [[id module]] (build-artifact id module opts false))
        (select-modules opts #(= :lib (:kind %)))))

(defn uber "Build executable uberjars for CLJ applications." [opts]
  (mapv (fn [[id module]] (build-artifact id module opts true))
        (select-modules opts #(and (= :app (:kind %)) (= :clj (:platform %))))))

(defn build "Build libraries, CLJ uberjars, and CLJS browser releases." [opts]
  (mapv (fn [[id module]]
          (if (and (= :app (:kind module)) (= :cljs (:platform module)))
            (let [directory (resolve-module-directory module)]
              (b/with-project-root directory
                (b/delete {:path "target/public"})
                (b/copy-dir {:src-dirs ["resources/public"] :target-dir "target/public"}))
              (run-command directory
                           (if (= :shadow (:compiler module))
                             ["clojure" "-M:cljs" "-m" "shadow.cljs.devtools.cli" "release" "app"]
                             ["clojure" "-M:cljs" "-m" "cljs.main" "-co" "build.edn" "-c" (str (:main module))]))
              {:module id :directory directory :output "target/public"})
            (build-artifact id module opts (= :app (:kind module)))))
        (select-modules opts (constantly true))))

(defn install "Build and install library jars. Optional :local-repo overrides the Maven destination." [opts]
  (doseq [artifact (jar opts)]
    (b/with-project-root (:directory artifact)
      (b/install (cond-> artifact
                   (:local-repo opts) (assoc-in [:basis :mvn/local-repo]
                                                (str (.getCanonicalFile (io/file (:local-repo opts)))))))))
  opts)

(defn deploy "Build and publish explicitly selected libraries to Clojars." [opts]
  (when (or (nil? (:modules opts)) (= [] (:modules opts)))
    (throw (ex-info "Deploy requires explicit :modules selection." {})))
  (when-not (and (:url project) (get-in project [:license :name]) (get-in project [:license :url]))
    (throw (ex-info "Set project :url and :license {:name ..., :url ...} before publishing." {})))
  (doseq [artifact (jar opts)]
    (b/with-project-root (:directory artifact)
      ((requiring-resolve 'deps-deploy.deps-deploy/deploy)
       {:installer :remote :artifact (b/resolve-path (:jar-file artifact))
        :pom-file (b/pom-path artifact)})))
  opts)

(defn run "Run one CLJ app from source. Accepts :args, a vector of program arguments." [opts]
  (let [selected (select-modules opts #(and (= :app (:kind %)) (= :clj (:platform %))))]
    (when-not (= 1 (count selected))
      (throw (ex-info "Select exactly one CLJ app with :modules." {})))
    (let [[_ module] (first selected)]
      (run-command (resolve-module-directory module)
                   (into ["clojure" "-M" "-m" (str (:main module))] (:args opts))))))

(defn repl "Run one module's foreground REPL alias. Select it with :modules." [opts]
  (let [selected (select-modules opts (constantly true))]
    (when-not (= 1 (count selected))
      (throw (ex-info "Select exactly one module with :modules." {})))
    (let [[_ module] (first selected)
          directory (resolve-module-directory module)]
      (when (and (= :app (:kind module)) (= :shadow (:compiler module)))
        (b/with-project-root directory
          (b/copy-dir {:src-dirs ["resources/public"] :target-dir "target/public"})))
      (run-command directory ["clojure" "-M:repl"]))))

(defn ci "Run tests and build selected modules. Publishing is a separate action." [opts]
  (test opts)
  (build opts))
