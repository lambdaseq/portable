(ns portable.fs
  "Synchronous filesystem operations on native path strings. Browsers are unsupported."
  (:require [clojure.string :as str]
            [portable.bytes :as bytes])
  #?(:clj (:import [java.io IOException]
                   [java.nio.file Files LinkOption NoSuchFileException OpenOption Paths]
                   [java.nio.file.attribute BasicFileAttributes FileAttribute])))

#?(:cljs
   (def ^:private NODE_FS
     (when (and (= "nodejs" *target*)
                (not (exists? js/window))
                (not (exists? js/importScripts))
                (exists? js/require))
       (js/require "node:fs"))))

(defn- throw-invalid-input [operation reason]
  (throw (ex-info "Invalid filesystem input."
                  {:type :portable/invalid-input :operation operation :reason reason})))

(defn- validate-path [operation path]
  (when-not (string? path)
    (throw-invalid-input operation :expected-string))
  (when (or (empty? path) (str/includes? path "\u0000"))
    (throw-invalid-input operation :invalid-path)))

(defn- throw-io-error [operation reason cause]
  (throw (ex-info "Filesystem operation failed."
                  {:type :portable/fs-error :operation operation :reason reason} cause)))

(defn- get-error-reason [error]
  #?(:clj (case (.getName (class error))
            "java.nio.file.NoSuchFileException" :not-found
            "java.nio.file.FileAlreadyExistsException" :already-exists
            "java.nio.file.NotDirectoryException" :not-directory
            "java.nio.file.DirectoryNotEmptyException" :not-empty
            ("java.nio.file.AccessDeniedException" "java.lang.SecurityException") :access-denied
            :io-error)
     :cljs (case (.-code error)
             "ENOENT" :not-found
             "EEXIST" :already-exists
             "ENOTDIR" :not-directory
             "EISDIR" :is-directory
             "ENOTEMPTY" :not-empty
             ("EACCES" "EPERM" "ERR_ACCESS_DENIED") :access-denied
             :io-error)
     :default :io-error))

(defn- describe-file [attributes]
  (cond
    #?(:clj (.isRegularFile ^BasicFileAttributes attributes) :cljs (.isFile attributes))
    {:type :file :size #?(:clj (.size ^BasicFileAttributes attributes) :cljs (.-size attributes))}
    #?(:clj (.isDirectory ^BasicFileAttributes attributes) :cljs (.isDirectory attributes)) {:type :directory}
    #?(:clj (.isSymbolicLink ^BasicFileAttributes attributes) :cljs (.isSymbolicLink attributes)) {:type :symlink}
    :else {:type :other}))

(defn- require-regular-file! [operation path]
  (let [attributes #?(:clj (try (Files/readAttributes path BasicFileAttributes (make-array LinkOption 0))
                                (catch NoSuchFileException _ nil))
                       :cljs (.statSync NODE_FS path #js {:throwIfNoEntry false})
                       :default nil)]
    (when attributes
      (case (:type (describe-file attributes))
        :file nil
        :directory (throw-io-error operation :is-directory nil)
        (throw-io-error operation :not-file nil)))))

#?(:clj
   (defn- run-jvm! [operation path data]
     (let [native (Paths/get path (make-array String 0))]
       (case operation
         (:portable.fs/read-bytes :portable.fs/read-text)
         (do (require-regular-file! operation native)
             (mapv #(bit-and % 255) (Files/readAllBytes native)))

         (:portable.fs/write-bytes! :portable.fs/write-text!)
         (do (require-regular-file! operation native)
             (Files/write native (byte-array (map unchecked-byte data)) (make-array OpenOption 0))
             nil)

         :portable.fs/create-dirs!
         (do (Files/createDirectories native (make-array FileAttribute 0)) nil)

         :portable.fs/list-dir
         (with-open [entries (Files/list native)]
           (->> (iterator-seq (.iterator entries))
                (map #(str (.getFileName ^java.nio.file.Path %)))
                sort
                vec))

         :portable.fs/stat
         (describe-file (Files/readAttributes native BasicFileAttributes
                                              (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))

         :portable.fs/delete! (Files/deleteIfExists native)))))

#?(:cljs
   (defn- run-node! [operation path data]
     (case operation
       (:portable.fs/read-bytes :portable.fs/read-text)
       (do (require-regular-file! operation path) (vec (.readFileSync NODE_FS path)))

       (:portable.fs/write-bytes! :portable.fs/write-text!)
       (do (require-regular-file! operation path)
           (.writeFileSync NODE_FS path (js/Uint8Array. (to-array data)))
           nil)

       :portable.fs/create-dirs!
       (do (.mkdirSync NODE_FS path #js {:recursive true}) nil)

       :portable.fs/list-dir (vec (sort (array-seq (.readdirSync NODE_FS path))))

       :portable.fs/stat (describe-file (.lstatSync NODE_FS path))

       :portable.fs/delete!
       (let [attributes (.lstatSync NODE_FS path)]
         (if (.isDirectory attributes) (.rmdirSync NODE_FS path) (.unlinkSync NODE_FS path))
         true))))

(defn- execute! [operation path data]
  (try
    #?(:clj (run-jvm! operation path data)
       :cljs (if NODE_FS
               (run-node! operation path data)
               (throw (ex-info "Filesystem operations are unsupported on this host."
                               {:type :portable/unsupported-operation :operation operation})))
       :default (throw (ex-info "Filesystem operations are unsupported on this runtime."
                                {:type :portable/unsupported-operation :operation operation})))
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo :default Exception) error
      (throw error))
    #?(:clj (catch IllegalArgumentException error
              (if (= "java.nio.file.InvalidPathException" (.getName (class error)))
                (throw-invalid-input operation :invalid-path)
                (throw error))))
    (catch #?(:clj IOException :cljs js/Error :default Exception) error
      (let [reason (get-error-reason error)]
        (if (and (= :not-found reason) (#{:portable.fs/stat :portable.fs/delete!} operation))
          (when (= :portable.fs/delete! operation) false)
          (throw-io-error operation reason error))))
    #?(:clj (catch SecurityException error (throw-io-error operation :access-denied error)))))

(defn- convert-text [operation convert value]
  (try (convert value)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo :default Exception) error
         (throw (ex-info "Filesystem text conversion failed."
                         (assoc (ex-data error) :operation operation) error)))))

(defn read-bytes
  "Read a regular file as an unsigned byte vector. Follow symbolic links."
  [path]
  (validate-path :portable.fs/read-bytes path)
  (execute! :portable.fs/read-bytes path nil))

(defn write-bytes!
  "Create or truncate a regular file using unsigned bytes. Do not create parent directories. Return nil."
  [path data]
  (validate-path :portable.fs/write-bytes! path)
  (when-not (bytes/byte-vector? data)
    (throw-invalid-input :portable.fs/write-bytes! :expected-byte-vector))
  (execute! :portable.fs/write-bytes! path data))

(defn read-text
  "Read strict UTF-8 text, preserving BOMs. Reject malformed UTF-8."
  [path]
  (validate-path :portable.fs/read-text path)
  (convert-text :portable.fs/read-text bytes/decode-utf8 (execute! :portable.fs/read-text path nil)))

(defn write-text!
  "Create or truncate a file as strict UTF-8. Reject unpaired surrogates before writing. Return nil."
  [path text]
  (validate-path :portable.fs/write-text! path)
  (let [data (convert-text :portable.fs/write-text! bytes/encode-utf8 text)]
    (execute! :portable.fs/write-text! path data)))

(defn create-dirs!
  "Create a directory and missing parents. Existing directories succeed. Return nil."
  [path]
  (validate-path :portable.fs/create-dirs! path)
  (execute! :portable.fs/create-dirs! path nil))

(defn list-dir
  "List immediate entry names as a sorted vector, including hidden names. Follow directory links."
  [path]
  (validate-path :portable.fs/list-dir path)
  (execute! :portable.fs/list-dir path nil))

(defn stat
  "Read metadata without following the final symbolic link. Return nil when missing.
  Return {:type :file :size bytes}, or {:type :directory}, {:type :symlink}, {:type :other}."
  [path]
  (validate-path :portable.fs/stat path)
  (execute! :portable.fs/stat path nil))

(defn delete!
  "Delete one entry without following its final symbolic link. Directories must be empty.
  Return true when deleted, false when missing. Never delete recursively."
  [path]
  (validate-path :portable.fs/delete! path)
  (execute! :portable.fs/delete! path nil))
