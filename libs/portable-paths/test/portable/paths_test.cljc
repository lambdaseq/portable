(ns portable.paths-test
  (:require [portable.paths :as paths]
            #?(:clj [clojure.test :as test :refer [deftest is testing]]
               :cljs [cljs.test :as test :refer-macros [deftest is testing]])))

(defn- get-error-data [convert & args]
  (try (apply convert args)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) error
         (ex-data error))))

(def ^:private POSIX_CASES
  [["" "." "" "." "" false]
   ["." "." "." "." "" false]
   [".." ".." ".." "." "" false]
   ["a/.." "." ".." "a" "" false]
   ["a/../" "./" ".." "a" "" false]
   ["a//b/../c/" "a/c/" "c" "a/b/.." "" false]
   ["/../../a//" "/a/" "a" "/../.." "" true]
   ["a/.hidden" "a/.hidden" ".hidden" "a" "" false]
   ["/tmp/archive.tar.gz" "/tmp/archive.tar.gz" "archive.tar.gz" "/tmp" ".gz" true]
   ["/a/.." "/" ".." "/a" "" true]
   ["/" "/" "" "/" "" true]
   ["//server/share" "/server/share" "share" "/server" "" true]
   ["C:/a" "C:/a" "a" "C:" "" false]
   ["\\tmp\\a" "\\tmp\\a" "\\tmp\\a" "." "" false]
   ["λ/中/😀.txt" "λ/中/😀.txt" "😀.txt" "λ/中" ".txt" false]])

(def ^:private WINDOWS_CASES
  [["" "." "" "." "" false]
   ["C:" "C:." "" "C:" "" false]
   ["C:foo\\..\\bar" "C:bar" "bar" "C:foo\\.." "" false]
   ["C:\\a\\..\\b\\" "C:\\b\\" "b" "C:\\a\\.." "" true]
   ["c:/a/../../b" "c:\\b" "b" "c:\\a\\..\\.." "" true]
   ["\\a" "\\a" "a" "\\" "" true]
   ["C:/" "C:\\" "" "C:\\" "" true]
   ["\\\\server\\share" "\\\\server\\share\\" "" "\\\\server\\share\\" "" true]
   ["\\\\server\\share\\..\\dir\\x.txt" "\\\\server\\share\\dir\\x.txt" "x.txt" "\\\\server\\share\\..\\dir" ".txt" true]
   ["//server/share//a/../" "\\\\server\\share\\" ".." "\\\\server\\share\\a" "" true]
   ["../../../a" "..\\..\\..\\a" "a" "..\\..\\.." "" false]
   ["a\\..." "a\\..." "..." "a" "." false]
   ["C:..\\.." "C:..\\.." ".." "C:.." "" false]
   ["λ\\中/😀.txt" "λ\\中\\😀.txt" "😀.txt" "λ\\中" ".txt" false]])

(deftest path-examples
  (doseq [[style cases] [[:posix POSIX_CASES] [:windows WINDOWS_CASES]]
          [path normalized name parent extension absolute?] cases]
    (testing (str style " " path)
      (is (= normalized (paths/normalize path style)))
      (is (= name (paths/basename path style)))
      (is (= parent (paths/dirname path style)))
      (is (= extension (paths/extname path style)))
      (is (= absolute? (paths/absolute? path style))))))

(deftest join-examples
  (doseq [[style parts expected]
          [[:posix [] "."] [:posix ["" ""] "."]
           [:posix ["a" "/b" ".." "c/"] "a/c/"]
           [:posix ["/" "a" ".." ".." "b"] "/b"]
           [:posix ["/a" "" "b"] "/a/b"]
           [:windows ["C:\\a" ".." "b/"] "C:\\b\\"]
           [:windows ["C:dir" "child"] "C:dir\\child"]
           [:windows ["\\\\server\\share" "a" ".."] "\\\\server\\share\\"]
           [:windows ["a" "\\b"] "a\\b"]
           [:windows [] "."]]]
    (is (= expected (paths/join parts style)))))

(deftest default-style-is-posix
  (doseq [[convert args] [[paths/normalize ["C:\\a\\..\\b"]]
                          [paths/join [["a" "b"]]]
                          [paths/basename ["C:\\a"]]
                          [paths/dirname ["C:\\a"]]
                          [paths/extname ["C:\\a.txt"]]
                          [paths/absolute? ["C:\\a"]]]]
    (is (= (apply convert args) (apply convert (conj args :posix))))))

(deftest extension-examples
  (doseq [style [:posix :windows]
          [path expected] [[".hidden" ""] [".hidden.txt" ".txt"] [".." ""]
                           ["." ""] ["..." "."] ["file." "."] ["..hidden" ".hidden"]]]
    (is (= expected (paths/extname path style)))))

(deftest normalization-is-idempotent
  (doseq [style [:posix :windows]
          prefix ["" "/" "C:" "C:/" "//server/share/"]
          first-segment ["a" "." ".." "λ" "D:"]
          second-segment ["b" "." ".." "D:"]
          suffix ["" "/"]]
    (let [path (str prefix first-segment "/" second-segment suffix)
          normalized (paths/normalize path style)]
      (is (= normalized (paths/normalize normalized style))))))

(deftest normalization-preserves-rootedness
  (doseq [style [:posix :windows]
          path ["./C:/foo" "a/../C:/foo" "a/../D:bar" "./C:" "C:foo/../bar"
                 "C:/foo/../bar" "/foo/.." "//host/share/a/../.."]]
    (is (= (paths/absolute? path style)
           (paths/absolute? (paths/normalize path style) style))))
  (is (= ".\\C:\\foo" (paths/normalize "a/../C:/foo" :windows)))
  (is (= ".\\D:bar" (paths/normalize "a/../D:bar" :windows))))

(def ^:private PATH_FUNCTIONS
  [[paths/normalize :portable.paths/normalize]
   [paths/basename :portable.paths/basename]
   [paths/dirname :portable.paths/dirname]
   [paths/extname :portable.paths/extname]
   [paths/absolute? :portable.paths/absolute?]])

(deftest invalid-paths
  (doseq [[convert operation] PATH_FUNCTIONS
          style [:posix :windows]
          value [nil 0 false :path [] {}]]
    (is (= {:type :portable/invalid-input :operation operation :reason :expected-string}
           (get-error-data convert value style))))
  (doseq [[convert operation] PATH_FUNCTIONS
          style [:posix :windows]]
    (is (= {:type :portable/invalid-input :operation operation :reason :invalid-path}
           (get-error-data convert "a\u0000b" style)))))

(deftest invalid-styles
  (doseq [[convert operation] (conj PATH_FUNCTIONS [paths/join :portable.paths/join])
          style [nil :native "posix" {}]]
    (is (= {:type :portable/invalid-input :operation operation :reason :invalid-style}
           (get-error-data convert (if (= operation :portable.paths/join) [] "a") style)))))

(deftest invalid-join-input
  (doseq [value [nil "a" '("a") {}]]
    (is (= :expected-path-vector (:reason (get-error-data paths/join value)))))
  (doseq [style [:posix :windows]]
    (is (= :expected-string (:reason (get-error-data paths/join ["a" nil] style))))
    (is (= :invalid-path (:reason (get-error-data paths/join ["a\u0000b"] style))))))

(deftest device-paths-are-unsupported
  (doseq [[convert operation] (conj PATH_FUNCTIONS [paths/join :portable.paths/join])
          path ["\\\\?\\C:\\a" "\\\\.\\pipe\\name" "//?/UNC/server/share" "//./device"]]
    (is (= {:type :portable/unsupported-operation :operation operation :reason :unsupported-path-form}
           (get-error-data convert (if (= operation :portable.paths/join) ["a" path] path) :windows)))))

(deftest traversal-stays-within-roots
  (doseq [[path style expected] [["/a/../../.." :posix "/"]
                                 ["C:/a/../../.." :windows "C:\\"]
                                 ["//server/share/a/../../.." :windows "\\\\server\\share\\"]
                                 ["../../a" :posix "../../a"]
                                 ["C:../../a" :windows "C:..\\..\\a"]]]
    (is (= expected (paths/normalize path style)))))

(deftest lexical-text-is-preserved
  (doseq [[path style expected] [["  a / b  " :posix "  a / b  "]
                                 ["C:/a\nb/file.txt" :windows "C:\\a\nb\\file.txt"]
                                 ["//host/share/a\nb/file.txt" :windows "\\\\host\\share\\a\nb\\file.txt"]
                                 ["a/~/file" :posix "a/~/file"]
                                 ["%2e%2e/file" :posix "%2e%2e/file"]
                                 ["$HOME/file" :posix "$HOME/file"]
                                 ["//?/C:/file" :posix "/?/C:/file"]]]
    (is (= expected (paths/normalize path style)))))
