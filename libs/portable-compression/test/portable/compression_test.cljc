(ns portable.compression-test
  (:require [portable.bytes :as bytes]
            [portable.bytes.operation :as operation]
            [portable.compression :as compression]
            [portable.compression-vectors :as vectors]
            #?(:clj [portable.compression.jvm :as backend]
               :cljs [portable.compression.javascript :as backend])
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is async]])))

(defn- run-contract! [scenario]
  #?(:clj (let [done (promise)] (scenario #(deliver done true)) (is (true? (deref done 20000 false))))
     :cljs (async done (scenario done))))

(defn- run-cases! [cases check!]
  (run-contract!
   (fn [done]
     (letfn [(next! [remaining]
               (if-let [example (first remaining)]
                 (check! example #(next! (next remaining)))
                 (done)))]
       (next! cases)))))

(defn- get-error [f & args]
  (try (apply f args) (catch #?(:clj Exception :cljs :default) error error)))

(deftest independent-gzip-zlib-and-block-format-vectors
  (run-cases!
   vectors/CASES
   (fn [{:keys [name format data encoded]} done]
     (is (nil? (compression/decompress! format encoded {:max-output-bytes (count data)}
                                       (fn [error result]
                                         (is (nil? error) name) (is (= data result) name) (done))))))))

(deftest round-trips-binary-empty-text-and-multiple-output-chunks
  (run-cases!
   (for [format [:gzip :deflate]
         data [[] (vec (range 256)) (bytes/encode-utf8 "λ🌍") (vec (take 100000 (cycle (range 251))))]]
     [format data])
   (fn [[format data] done]
     (compression/compress!
      format data
      (fn [error encoded]
        (is (nil? error)) (is (bytes/byte-vector? encoded))
        (when (bytes/byte-vector? encoded)
          (is (if (= :gzip format) (= [31 139 8] (subvec encoded 0 3)) (= 8 (bit-and 15 (first encoded))))))
        (compression/decompress! format (or encoded []) {:max-output-bytes (count data)}
                                 (fn [error result] (is (nil? error)) (is (= data result)) (done))))))))

(deftest output-limits-stop-compression-and-decompression
  (run-cases!
   (concat
    (for [{:keys [format data encoded]} vectors/CASES :when (seq data)]
      [compression/decompress! format encoded (dec (count data))])
    (for [format [:gzip :deflate] data [[] (vec (range 256))]]
      [compression/compress! format data 0]))
   (fn [[perform! format data limit] done]
     (perform! format data {:max-output-bytes limit}
               (fn [error value]
                 (is (= :portable/compression-error (:type (ex-data error))))
                 (is (= :output-too-large (:reason (ex-data error))))
                 (is (nil? value)) (done))))))

(deftest malformed-truncated-checksum-and-trailing-data-fail
  (let [examples (filter #(= "binary-6" (:name %)) vectors/CASES)
        optional (:encoded (first (filter #(= "optional-gzip-header" (:name %)) vectors/CASES)))]
    (run-cases!
     (concat
      (for [{:keys [format encoded]} examples
            bad (concat
                 (map #(subvec encoded 0 %) [0 1 2 5 10 (dec (count encoded))])
                 [(assoc encoded 0 0) (update encoded (- (count encoded) 5) bit-xor 1)
                  (update encoded (dec (count encoded)) bit-xor 1)
                  (conj encoded 0) (into encoded encoded)])]
        [format bad :invalid-data])
      [[:gzip (update optional 28 bit-xor 1) :invalid-data]
       [:gzip (assoc optional 10 255) :invalid-data]
       [:deflate [3 0] :invalid-data]
       [:gzip (into [31 139 8 8 0 0 0 0 0 255] (repeat 65540 97)) :invalid-data]
       [:deflate vectors/DICTIONARY_STREAM :dictionary-required]
       [:deflate [120 156 3 0 0 0 0 1 0] :invalid-data]
       [:gzip [31 139 8 224 0 0 0 0 0 255 3 0 0 0 0 0 0 0 0 0] :invalid-data]])
     (fn [[format data reason] done]
       (compression/decompress! format data
                                (fn [error value]
                                  (is (= :portable/compression-error (:type (ex-data error))))
                                  (is (= reason (:reason (ex-data error))))
                                  (is (nil? value)) (done)))))))

(deftest arguments-are-validated-before-native-work
  (let [started (atom 0) callback (fn [_ _])]
    (with-redefs [backend/start! (fn [& _] (swap! started inc))]
      (doseq [perform! [compression/compress! compression/decompress!]
              [format data options cb argument reason]
              [[:raw-deflate [] {} callback :format :unsupported-format]
               [nil [] {} callback :format :unsupported-format]
               [:gzip nil {} callback :data :expected-byte-vector]
               [:gzip '(1) {} callback :data :expected-byte-vector]
               [:gzip [-1] {} callback :data :expected-byte-vector]
               [:gzip [256] {} callback :data :expected-byte-vector]
               [:gzip [1.5] {} callback :data :expected-byte-vector]
               [:gzip [] nil callback :options :expected-map]
               [:gzip [] {:level 1} callback :options :unknown-option]
               [:gzip [] {:max-output-bytes -1} callback :max-output-bytes :out-of-range]
               [:gzip [] {:max-output-bytes nil} callback :max-output-bytes :out-of-range]
               [:gzip [] {:max-output-bytes 16777217} callback :max-output-bytes :out-of-range]
               [:gzip [] {:max-output-bytes 1.5} callback :max-output-bytes :out-of-range]
               [:gzip [] {} nil :callback :expected-callback]]]
        (let [error (get-error perform! format data options cb)]
          (is (= :portable/invalid-input (:type (ex-data error))))
          (is (= argument (:argument (ex-data error)))) (is (= reason (:reason (ex-data error))))))
      (is (zero? @started)))))

(deftest asynchronous-callback-and-independent-concurrent-engines
  (run-contract!
   (fn [done]
     (let [completed (atom 0) returned (atom false)
           #?@(:clj [caller (Thread/currentThread)])]
       (doseq [index (range 16)]
         (let [example (nth vectors/CASES (mod index (count vectors/CASES)))]
           (is (nil? (compression/decompress!
                      (:format example) (:encoded example)
                      (fn [error result]
                        #?(:clj (is (not (identical? caller (Thread/currentThread)))) :cljs (is @returned))
                        (is (nil? error)) (is (= (:data example) result))
                        (when (= 16 (swap! completed inc)) (done)))))))
       (reset! returned true))))))

(deftest completion-is-once-and-caller-exceptions-escape
  (let [tasks (atom []) calls (atom 0)
        failure #?(:clj (Exception. "Caller failed") :cljs (js/Error. "Caller failed"))]
    (with-redefs [operation/dispatch! (fn [task] (swap! tasks conj task))
                  backend/start! (fn [_ complete!]
                                   (complete! nil [1]) (complete! failure nil) (throw failure))]
      (compression/compress! :gzip [] (fn [error value]
                                       (swap! calls inc) (is (nil? error)) (is (= [1] value)) (throw failure)))
      ((first @tasks))
      (is (= 2 (count @tasks)))
      (is (identical? failure (get-error (second @tasks))))
      (is (= 1 @calls)))))

(deftest native-failures-and-invalid-results-are-normalized
  (run-cases!
   [:failure :invalid-result :oversize]
   (fn [kind done]
     (with-redefs [backend/start! (fn [_ complete!]
                                   (case kind
                                     :failure (throw (ex-info "Native failure" {}))
                                     :invalid-result (complete! nil [-1])
                                     :oversize (complete! nil [0 1])))]
       (compression/compress! :deflate [] {:max-output-bytes 1}
                              (fn [error result]
                                (is (= (case kind :failure :compression-failure
                                             :invalid-result :invalid-result :oversize :output-too-large)
                                       (:reason (ex-data error))))
                                (is (nil? result)) (done)))))))

#?(:cljs
   (deftest missing-native-apis-are-explicit-asynchronous-errors
     (run-contract!
      (fn [done]
        (let [node? (= "nodejs" *target*)
              provider (if node? (js/require "node:zlib") js/globalThis)
              names (if node? ["gzip" "deflate" "inflateRaw" "inflate"] ["CompressionStream" "DecompressionStream"])
              previous (mapv #(aget provider %) names)
              calls (atom 0)
              restore! (fn [] (doseq [[name value] (map vector names previous)] (aset provider name value)))
              sample (first (filter #(= :gzip (:format %)) vectors/CASES))]
          (doseq [name names] (aset provider name nil))
          (doseq [[perform! data] [[compression/compress! []] [compression/decompress! (:encoded sample)]]]
            (perform! :gzip data
                      (fn [error result]
                        (is (= :portable/unsupported-operation (:type (ex-data error))))
                        (is (= :compression-unavailable (:reason (ex-data error)))) (is (nil? result))
                        (when (= 2 (swap! calls inc)) (restore!) (done))))))))))
