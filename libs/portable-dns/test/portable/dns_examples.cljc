(ns portable.dns-examples
  "Deterministic DNS wire examples independent of production encoders.")

(defn ascii [text] (mapv #?(:clj int :cljs #(.charCodeAt % 0)) text))
(defn u16 [value] [(quot value 256) (mod value 256)])
(defn u32 [value] (into (u16 (quot value 65536)) (u16 (mod value 65536))))
(defn pointer [offset] [(+ 192 (quot offset 256)) (mod offset 256)])

(def QUESTION [7 101 120 97 109 112 108 101 4 116 101 115 116 0 0 1 0 1])
(def QUERY (into [18 52 1 0 0 1 0 0 0 0 0 0] QUESTION))

(defn record
  ([type data] (record type data 1 60))
  ([type data class ttl] (vec (concat [192 12] (u16 type) (u16 class) (u32 ttl) (u16 (count data)) data))))

(defn message
  ([answers] (message answers [] [] 33152))
  ([answers authorities additionals flags]
   (vec (concat [18 52] (u16 flags) [0 1] (u16 (count answers)) (u16 (count authorities)) (u16 (count additionals))
                QUESTION (mapcat identity answers) (mapcat identity authorities) (mapcat identity additionals)))))

(def A_RESPONSE (message [(record 1 [192 0 2 1])]))
(def SOA_DATA (vec (concat [2 110 115 192 12 10] (ascii "hostmaster") [192 12]
                          (u32 4294967295) (u32 3600) (u32 600) (u32 86400) (u32 60))))

(def ALL_RECORDS
  [(record 1 [192 0 2 1])
   (record 28 [32 1 13 184 0 0 0 0 0 0 0 0 0 0 0 1])
   (record 5 (into [5] (concat (ascii "alias") [192 12])))
   (record 2 [2 110 115 192 12])
   (record 12 (into [3] (concat (ascii "ptr") [192 12])))
   (record 15 (vec (concat [0 10 4] (ascii "mail") [192 12])))
   (record 16 [3 0 255 65 0])
   (record 6 SOA_DATA)
   (record 33 (vec (concat [0 10 0 20 1 187 7] (ascii "service") [7] (ascii "example") [4] (ascii "test") [0])))
   (record 65000 [192 12 255 0])
   (record 1 [0 255] 3 4294967295)])
