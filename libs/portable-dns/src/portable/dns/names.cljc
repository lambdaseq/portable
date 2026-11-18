(ns portable.dns.names
  "Private DNS presentation names and bounded compression decoding."
  (:require [clojure.string :as str]))

(defn fail! [reason]
  (throw (ex-info "Invalid DNS message."
                  {:type :portable/dns-error :operation :portable.dns/decode-message :reason reason})))

(defn require-range [data offset size limit]
  (when-not (and (<= 0 offset) (<= (+ offset size) limit (count data))) (fail! :incomplete-message)))

(defn- get-code [character] #?(:clj (int character) :cljs (.charCodeAt character 0)))
(defn- get-character [code] #?(:clj (char code) :cljs (js/String.fromCharCode code)))
(defn- lower-byte [value] (if (<= 65 value 90) (+ value 32) value))

(defn- format-label [label]
  (str/join
   (map (fn [value]
          (let [value (lower-byte value)]
            (if (and (<= 33 value 126) (not (#{46 92} value)))
              (get-character value)
              (str "\\" (subs (str "000" value) (count (str value))))))) label)))

(defn format-name [labels]
  (if (empty? labels) "." (str (str/join "." (map format-label labels)) ".")))

(defn prepare-name [operation text]
  (let [invalid! #(throw (ex-info "Invalid DNS name."
                                  {:type :portable/invalid-input :operation operation :reason :invalid-name}))]
    (when-not (and (string? text) (<= 1 (count text) 1024)) (invalid!))
    (let [labels
          (if (= text ".")
            []
            (loop [index 0 current [] labels []]
              (if (= index (count text))
                (cond-> labels (seq current) (conj current))
                (let [code (get-code (nth text index))]
                  (cond
                    (= code 46)
                    (do (when (empty? current) (invalid!))
                        (recur (inc index) [] (conj labels current)))

                    (= code 92)
                    (let [next-index (inc index)]
                      (when (= next-index (count text)) (invalid!))
                      (let [next-code (get-code (nth text next-index))]
                        (if (<= 48 next-code 57)
                          (let [end (+ next-index 3)]
                            (when (> end (count text)) (invalid!))
                            (let [digits (mapv get-code (subs text next-index end))]
                              (when-not (every? #(<= 48 % 57) digits) (invalid!))
                              (let [value (reduce #(+ (* %1 10) (- %2 48)) 0 digits)]
                                (when (> value 255) (invalid!))
                                (recur end (conj current (lower-byte value)) labels))))
                          (do (when-not (<= 33 next-code 126) (invalid!))
                              (recur (inc next-index) (conj current (lower-byte next-code)) labels)))))

                    :else
                    (do (when-not (<= 33 code 126) (invalid!))
                        (recur (inc index) (conj current (lower-byte code)) labels)))))))
          wire (into [] (concat (mapcat #(cons (count %) %) labels) [0]))]
      (when (or (some #(not (<= 1 (count %) 63)) labels) (> (count wire) 255)) (invalid!))
      {:name (format-name labels) :wire wire})))

(defn read-name
  ([data state limit] (read-name data state limit true))
  ([data state limit allow-compression?]
   (loop [position (:offset state) labels [] expanded-size 1 next-offset nil visited #{}
          jumps 0 known (:name-offsets state)]
     (let [boundary (if next-offset (count data) limit)]
       (require-range data position 1 boundary)
       (when (contains? visited position) (fail! :invalid-compression))
       (let [size (nth data position)
             visited (conj visited position)
             known (conj known position)]
         (cond
           (zero? size)
           [(format-name labels) (assoc state :offset (or next-offset (inc position)) :name-offsets known)]

           (<= size 63)
           (do (require-range data (inc position) size boundary)
               (when (> (+ expanded-size size 1) 255) (fail! :invalid-name))
               (recur (+ position size 1) (conj labels (subvec data (inc position) (+ position size 1)))
                      (+ expanded-size size 1) next-offset visited jumps known))

           (>= size 192)
           (do (require-range data position 2 boundary)
               (let [target (+ (* (- size 192) 256) (nth data (inc position)))]
                 (when-not (and allow-compression? (< jumps 128) (<= 12 target) (< target position)
                                (contains? known target))
                   (fail! :invalid-compression))
                 (recur target labels expanded-size (or next-offset (+ position 2)) visited (inc jumps) known)))

           :else (fail! :invalid-name)))))))
