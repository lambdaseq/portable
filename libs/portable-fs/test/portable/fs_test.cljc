(ns portable.fs-test
  (:require [portable.fs :as fs]
            #?(:clj [clojure.test :as test :refer [deftest is]]
               :cljs [cljs.test :as test :refer-macros [deftest is]]))
  #?(:clj (:import [java.nio.file Files Path Paths]
                   [java.nio.file.attribute FileAttribute])))

#?(:cljs
   (def ^:private NODE_FS (when (= "nodejs" *target*) (js/require "node:fs"))))

(defn- is-browser? []
  #?(:clj false :cljs (exists? js/window)))

(defn- get-error-data [operation & args]
  (try (apply operation args)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) error
         (ex-data error))))

(defn- with-test-directory! [run-test!]
  (let [root #?(:clj (str (Files/createTempDirectory "portable-fs-" (make-array FileAttribute 0)))
                :cljs (.mkdtempSync NODE_FS (str (.tmpdir (js/require "node:os")) "/portable-fs-")))]
    (try
      (run-test! root)
      (finally
        #?(:clj (with-open [entries (Files/walk (Paths/get root (make-array String 0))
                                               (make-array java.nio.file.FileVisitOption 0))]
                  (doseq [entry (sort-by #(.getNameCount ^Path %) > (iterator-seq (.iterator entries)))]
                    (Files/deleteIfExists entry)))
           :cljs (.rmSync NODE_FS root #js {:recursive true :force true}))))))

(def ^:private OPERATIONS
  [[fs/read-bytes :portable.fs/read-bytes []]
   [fs/write-bytes! :portable.fs/write-bytes! [[0 255]]]
   [fs/read-text :portable.fs/read-text []]
   [fs/write-text! :portable.fs/write-text! ["text"]]
   [fs/create-dirs! :portable.fs/create-dirs! []]
   [fs/list-dir :portable.fs/list-dir []]
   [fs/stat :portable.fs/stat []]
   [fs/delete! :portable.fs/delete! []]])

(deftest filesystem-lifecycle-contract
  (if (is-browser?)
    (doseq [[convert operation args] OPERATIONS]
      (is (= {:type :portable/unsupported-operation :operation operation}
             (apply get-error-data convert "portable-fs-test" args))))
    (with-test-directory!
      (fn [root]
        (let [directory (str root "/nested/child")
              file (str directory "/λ.txt")
              data (vec (range 256))
              text "\uFEFFλ😀\u0000line\n"]
          (is (nil? (fs/stat file)))
          (is (false? (fs/delete! file)))
          (is (nil? (fs/create-dirs! directory)))
          (is (nil? (fs/create-dirs! directory)))
          (is (= {:type :directory} (fs/stat directory)))
          (is (= [] (fs/list-dir directory)))
          (is (nil? (fs/write-bytes! file data)))
          (is (= data (fs/read-bytes file)))
          (is (= {:type :file :size 256} (fs/stat file)))
          (is (nil? (fs/write-bytes! file data)))
          (is (= data (fs/read-bytes file)))
          (is (nil? (fs/write-bytes! file [1])))
          (is (= [1] (fs/read-bytes file)))
          (is (= {:type :file :size 1} (fs/stat file)))
          (is (nil? (fs/write-text! file text)))
          (is (= text (fs/read-text file)))
          (is (= ["λ.txt"] (fs/list-dir directory)))
          (fs/write-text! file "")
          (is (= [] (fs/read-bytes file)))
          (is (= "" (fs/read-text file)))
          (is (true? (fs/delete! file)))
          (is (false? (fs/delete! file)))
          (is (true? (fs/delete! directory)))
          (is (nil? (fs/stat directory))))))))

(deftest sorted-directory-contract
  (when-not (is-browser?)
    (with-test-directory!
      (fn [root]
        (doseq [name ["z" "A" ".hidden" "λ"]]
          (fs/write-text! (str root "/" name) ""))
        (fs/create-dirs! (str root "/directory"))
        (is (= [".hidden" "A" "directory" "z" "λ"] (fs/list-dir root)))))))

(deftest filesystem-errors-contract
  (when-not (is-browser?)
    (with-test-directory!
      (fn [root]
        (let [file (str root "/file")
              absent (str root "/missing")]
          (fs/write-text! file "original")
          (doseq [[convert args operation reason]
                  [[fs/read-bytes [absent] :portable.fs/read-bytes :not-found]
                   [fs/read-text [absent] :portable.fs/read-text :not-found]
                   [fs/list-dir [absent] :portable.fs/list-dir :not-found]
                   [fs/list-dir [file] :portable.fs/list-dir :not-directory]
                   [fs/create-dirs! [file] :portable.fs/create-dirs! :already-exists]
                   [fs/delete! [root] :portable.fs/delete! :not-empty]
                   [fs/read-bytes [root] :portable.fs/read-bytes :is-directory]
                   [fs/read-text [root] :portable.fs/read-text :is-directory]
                   [fs/write-bytes! [root []] :portable.fs/write-bytes! :is-directory]
                   [fs/write-text! [root "text"] :portable.fs/write-text! :is-directory]
                   [fs/write-bytes! [(str absent "/file") []] :portable.fs/write-bytes! :not-found]]]
            (is (= {:type :portable/fs-error :operation operation :reason reason}
                   (apply get-error-data convert args))))
          (is (= "original" (fs/read-text file))))))))

(deftest invalid-path-contract
  (doseq [[convert operation args] OPERATIONS
          path [nil 0 false :path [] {} "" "a\u0000b"]]
    (is (= {:type :portable/invalid-input :operation operation
            :reason (if (string? path) :invalid-path :expected-string)}
           (apply get-error-data convert path args)))))

(deftest invalid-payload-contract
  (doseq [data [nil "text" '(0) [nil] [-1] [256] [1.5] ["0"]]]
    (is (= {:type :portable/invalid-input :operation :portable.fs/write-bytes! :reason :expected-byte-vector}
           (get-error-data fs/write-bytes! "unused" data))))
  (doseq [text [nil [] {} 0 false]]
    (is (= {:type :portable/invalid-input :operation :portable.fs/write-text! :reason :expected-string}
           (get-error-data fs/write-text! "unused" text)))))

(deftest validation-precedes-writing
  (let [invalid-text #?(:clj (str (char 0xD800)) :cljs (js/String.fromCharCode 0xD800))]
    (if (is-browser?)
      (is (= :invalid-unicode (:reason (get-error-data fs/write-text! "unused" invalid-text))))
      (with-test-directory!
        (fn [root]
          (let [file (str root "/file")]
            (fs/write-text! file "original")
            (is (= :invalid-unicode (:reason (get-error-data fs/write-text! file invalid-text))))
            (is (= "original" (fs/read-text file)))
            (is (= :expected-byte-vector (:reason (get-error-data fs/write-bytes! file [256]))))
            (is (= "original" (fs/read-text file)))
            (fs/write-bytes! file [255])
            (is (= {:type :portable/invalid-input :operation :portable.fs/read-text :reason :invalid-utf8}
                   (get-error-data fs/read-text file)))
            (is (= [255] (fs/read-bytes file)))))))))

(deftest symbolic-link-contract
  (when-not (is-browser?)
    (with-test-directory!
      (fn [root]
        (let [file (str root "/file")
              link (str root "/link")]
          (fs/write-text! file "target")
          #?(:clj (Files/createSymbolicLink (Paths/get link (make-array String 0))
                                            (Paths/get file (make-array String 0))
                                            (make-array FileAttribute 0))
             :cljs (.symlinkSync NODE_FS file link))
          (is (= {:type :symlink} (fs/stat link)))
          (is (= "target" (fs/read-text link)))
          (fs/write-text! link "changed")
          (is (= "changed" (fs/read-text file)))
          (fs/delete! file)
          (is (= {:type :symlink} (fs/stat link)))
          (is (= :not-found (:reason (get-error-data fs/read-bytes link))))
          (fs/write-text! link "recreated")
          (is (= "recreated" (fs/read-text file)))
          (is (true? (fs/delete! link)))
          (is (= "recreated" (fs/read-text file))))))))

#?(:cljs
   (deftest browser-shims-cannot-enable-filesystem-access
     (when (is-browser?)
       (let [original-process (when (exists? js/process) js/process)
             original-require (when (exists? js/require) js/require)
             calls (atom 0)]
         (try
           (set! js/globalThis.process #js {:versions #js {:node "24.0.0"}})
           (set! js/globalThis.require (fn [_] (swap! calls inc)))
           (doseq [[convert operation args] OPERATIONS]
             (is (= {:type :portable/unsupported-operation :operation operation}
                    (apply get-error-data convert "unused" args))))
           (is (zero? @calls))
           (finally
             (if original-process (set! js/globalThis.process original-process) (js-delete js/globalThis "process"))
             (if original-require (set! js/globalThis.require original-require) (js-delete js/globalThis "require"))))))))
