(ns portable.random.sampling
  "Private exact integer sampling without 32-bit or modulo bias.")

(defn create-plan [upper-bound]
  (let [bits (loop [remaining (dec upper-bound) bits 0]
               (if (zero? remaining) bits (recur (quot remaining 2) (inc bits))))
        high-bits (if (zero? bits) 0 (inc (mod (dec bits) 8)))]
    {:byte-count (quot (+ bits 7) 8) :mask (dec (bit-shift-left 1 high-bits))}))

(defn decode-sample [data mask]
  (if (seq data)
    (reduce (fn [value byte] (+ (* value 256) byte)) (bit-and (first data) mask) (rest data))
    0))
