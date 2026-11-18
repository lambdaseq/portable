(ns portable.dns-test
  (:require [portable.dns :as dns]
            [portable.dns-codec-test]
            [portable.dns.client :as client]
            [portable.dns-fixture :as fixture]
            [portable.random :as random]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is async]])))

(defn- run-contract! [scenario]
  #?(:clj (let [done (promise)] (scenario #(deliver done true)) (is (true? (deref done 15000 false))))
     :cljs (async done (scenario done))))

(defn- get-error-data [f & args]
  (try (apply f args)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) error (ex-data error))))

(defn- with-server! [scenario done]
  (fixture/start!
   (fn [{:keys [server stop!]}]
     (let [completed (atom false)
           cancellations (atom [])
           watchdog (atom nil)
           finish! (fn []
                     (when (compare-and-set! completed false true)
                       (when-let [cancel @watchdog] (cancel))
                       (doseq [cancel! @cancellations] (cancel!))
                       (stop! done)))
           query! (fn [options callback]
                    (let [cancel! (dns/query! (merge {:name "example.test" :server server :timeout-ms 2000} options) callback)]
                      (swap! cancellations conj cancel!)
                      (when @completed (cancel!))
                      cancel!))]
       (reset! watchdog (fixture/after! 6000 #(do (is false "DNS fixture contract timed out.") (finish!))))
       (scenario query! finish!)))))

(deftest shared-query-records-over-both-transports
  (when-not (fixture/is-browser?)
    (run-contract!
     #(with-server!
       (fn [query! finish!]
         (let [cases (for [transport [:udp :tcp]
                          [type expected] [[:a {:address "192.0.2.1"}] [:aaaa {:address "2001:db8::1"}]
                                           [:cname {:name "alias.example.test."}] [:ns {:name "alias.example.test."}]
                                           [:ptr {:name "alias.example.test."}]
                                           [:mx {:preference 10 :exchange "mail.example.test."}]
                                           [:txt {:strings [[0 255 65] []]}]
                                           [:srv {:priority 10 :weight 20 :port 443 :target "target."}]
                                           [65000 {:bytes [0 255]}]]]
                      [transport type expected])]
           (letfn [(check! [remaining]
                     (if-let [[transport type expected] (first remaining)]
                       (query! {:transport transport :type type :recursion-desired? false}
                               (fn [error response]
                                 (is (nil? error))
                                 (is (= expected (get-in response [:answers 0 :data])))
                                 (is (= [{:name "example.test." :type type :class 1}] (:questions response)))
                                 (is (false? (:recursion-desired? response)))
                                 (is (<= 0 (:id response) 65535))
                                 (check! (next remaining))))
                       (finish!)))]
             (check! cases))))
       %))))

(deftest matched-truncation-falls-back-before-decoding-partial-records
  (when-not (fixture/is-browser?)
    (run-contract!
     #(with-server!
       (fn [query! finish!]
         (query! {:name "fallback.example.test" :type :aaaa}
                 (fn [error response]
                   (is (nil? error))
                   (is (false? (:truncated? response)))
                   (is (= "2001:db8::1" (get-in response [:answers 0 :data :address])))
                   (finish!))))
       %))))

(deftest mismatched-replies-cannot-supply-answers-or-trigger-tcp
  (when-not (fixture/is-browser?)
    (run-contract!
     #(with-server!
       (fn [query! finish!]
         (query! {:name "ignored.example.test"}
                 (fn [error response]
                   (is (nil? error))
                   (is (= "192.0.2.1" (get-in response [:answers 0 :data :address])))
                   (query! {:name "mismatch-only.example.test" :timeout-ms 100}
                           (fn [error response]
                             (is (= :timeout (:reason (ex-data error))))
                             (is (nil? response)) (finish!))))))
       %))))

(deftest negative-and-empty-dns-responses-remain-data
  (when-not (fixture/is-browser?)
    (run-contract!
     #(with-server!
       (fn [query! finish!]
         (letfn [(check! [cases]
                   (if-let [[name rcode] (first cases)]
                     (query! {:name name}
                             (fn [error response]
                               (is (nil? error)) (is (= rcode (:rcode response))) (is (= [] (:answers response)))
                               (check! (next cases))))
                     (finish!)))]
           (check! [["nxdomain.example.test" 3] ["servfail.example.test" 2] ["empty.example.test" 0]])))
       %))))

(deftest malformed-frames-and-response-limits-fail-explicitly
  (when-not (fixture/is-browser?)
    (run-contract!
     #(with-server!
       (fn [query! finish!]
         (letfn [(check! [cases]
                   (if-let [[options reason] (first cases)]
                     (query! options
                             (fn [error response]
                               (is (= {:type :portable/dns-error :operation :portable.dns/query! :reason reason} (ex-data error)))
                               (is (nil? response))
                               (check! (next cases))))
                     (finish!)))]
           (check! [[{:name "close.example.test" :transport :tcp} :incomplete-response]
                    [{:name "short-frame.example.test" :transport :tcp} :invalid-frame-length]
                    [{:name "huge-frame.example.test" :transport :tcp :max-message-bytes 128} :message-too-large]
                    [{:name "tcp-mismatch.example.test" :transport :tcp} :response-mismatch]
                    [{:name "tcp-truncated.example.test" :transport :tcp} :truncated-response]
                    [{:name "bad-compression.example.test"} :invalid-compression]
                    [{:max-message-bytes 32} :message-too-large]
                    [{:name "large-udp.example.test"} :message-too-large]])))
       %))))

(deftest fallback-shares-the-original-deadline
  (when-not (fixture/is-browser?)
    (run-contract!
     #(with-server!
       (fn [query! finish!]
         (query! {:name "delayed-fallback.example.test" :timeout-ms 140}
                 (fn [error response]
                   (is (= :timeout (:reason (ex-data error)))) (is (nil? response)) (finish!))))
       %))))

(deftest deadlines-and-cancellation-on-both-transports
  (when-not (fixture/is-browser?)
    (run-contract!
     #(with-server!
       (fn [query! finish!]
         (letfn [(check! [cases]
                   (if-let [[transport cancel?] (first cases)]
                     (let [calls (atom 0)
                           cancel! (query! {:name "silent.example.test" :transport transport :timeout-ms (if cancel? 2000 40)}
                                           (fn [error response]
                                             (is (= 1 (swap! calls inc)))
                                             (is (= (if cancel? :cancelled :timeout) (:reason (ex-data error))))
                                             (is (nil? response))
                                             (check! (next cases))))]
                       (is (fn? cancel!))
                       (when cancel? (is (nil? (cancel!))) (is (nil? (cancel!)))))
                     (finish!)))]
           (check! [[:udp false] [:tcp false] [:udp true] [:tcp true]])))
       %))))

(deftest duplicate-responses-settle-once
  (when-not (fixture/is-browser?)
    (run-contract!
     #(with-server!
       (fn [query! finish!]
         (let [calls (atom 0)]
           (query! {:name "duplicate.example.test"}
                   (fn [error response]
                     (swap! calls inc)
                     (is (nil? error)) (is (= "192.0.2.1" (get-in response [:answers 0 :data :address])))
                     (fixture/after! 30 (fn [] (is (= 1 @calls)) (finish!)))))))
       %))))

(deftest query-input-validation-precedes-runtime-support
  (let [base {:name "example.test" :server {:host "127.0.0.1"}}
        callback (fn [_ _])]
    (doseq [[options reason] [[nil :expected-options-map] [{:name "test"} :expected-options-map]
                              [(assoc base :server {:host "localhost"}) :invalid-address]
                              [(assoc base :server {:host "::1"}) :invalid-address]
                              [(assoc base :server {:host "127.0.0.1" :port 0}) :invalid-port]
                              [(assoc base :server {:host "127.0.0.1" :extra true}) :unknown-option]
                              [(assoc base :type :bad) :invalid-type] [(assoc base :name "a b") :invalid-name]
                              [(assoc base :transport :tls) :invalid-transport]
                              [(assoc base :timeout-ms 0) :invalid-timeout]
                              [(assoc base :max-message-bytes 11) :invalid-message-limit]
                              [(assoc base :max-message-bytes 65536) :invalid-message-limit]
                              [(assoc base :id 0) :unknown-option]]]
      (is (= {:type :portable/invalid-input :operation :portable.dns/query! :reason reason}
             (get-error-data dns/query! options callback))))
    (is (= :expected-callback (:reason (get-error-data dns/query! base nil))))))

(deftest browser-network-queries-are-explicitly-unsupported
  (when (fixture/is-browser?)
    (let [calls (atom 0)]
      (with-redefs [random/generate-int! (fn [_] (throw (ex-info "Browser DNS must not request entropy." {})))]
        (is (= {:type :portable/unsupported-operation :operation :portable.dns/query!}
               (get-error-data dns/query! {:name "example.test" :server {:host "127.0.0.1"}}
                               (fn [_ _] (swap! calls inc))))))
      (is (zero? @calls)))))

(deftest dns-uses-shared-random-id-across-fallback
  (when-not (fixture/is-browser?)
    (run-contract!
     #(with-server!
       (fn [query! finish!]
         (let [draws (atom 0)]
           (with-redefs [random/generate-int! (fn [upper-bound]
                                               (is (= 65536 upper-bound))
                                               (swap! draws inc)
                                               4660)]
             (query! {:name "fallback.example.test"}
                     (fn [error response]
                       (is (nil? error)) (is (= 4660 (:id response)))
                       (is (= "192.0.2.1" (get-in response [:answers 0 :data :address])))
                       (is (= 1 @draws)) (finish!))))))
       %))))

(deftest entropy-failures-reach-dns-callback-before-socket-creation
  (when-not (fixture/is-browser?)
    (run-contract!
     (fn [done]
       (let [starts (atom 0)
             failure (ex-info "Entropy failed."
                              {:type :portable/random-error :operation :portable.random/generate-int! :reason :entropy-failure})]
         (with-redefs [random/generate-int! (fn [_] (throw failure))
                       client/start! (fn [& _] (swap! starts inc))]
           (dns/query! {:name "example.test" :server {:host "127.0.0.1" :port 9}}
                       (fn [error response]
                         (is (= {:type :portable/dns-error :operation :portable.dns/query! :reason :entropy-failure} (ex-data error)))
                         (is (identical? failure (ex-cause error)))
                         (is (nil? response)) (is (zero? @starts)) (done)))))))))
