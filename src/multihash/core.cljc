(ns multihash.core
  "Core multihash type definition and helper methods."
  (:require
    [alphabase.base58 :as b58]
    [alphabase.bytes :as bytes]
    [alphabase.hex :as hex])
  #?(:clj (:import
            (clojure.lang ILookup IMeta IObj)
            java.io.InputStream)))


;; ## Hash Function Algorithms

(def ^:const algorithm-codes
  "Map of information about the available content hashing algorithms."
  {:sha1     0x11
   :sha2-256 0x12
   :sha2-512 0x13
   :sha3     0x14
   :blake2b  0x40
   :blake2s  0x41})


(defn app-code?
  "True if the given code number is assigned to the application-specfic range.
  Returns nil if the argument is not an integer."
  [code]
  (when (integer? code)
    (< 0 code 0x10)))


(defn get-algorithm
  "Looks up an algorithm by keyword name or code number. Returns `nil` if the
  value does not map to any valid algorithm."
  [value]
  (cond
    (keyword? value)
      (when-let [code (get algorithm-codes value)]
        {:code code, :name value})

    (not (integer? value))
      nil

    (app-code? value)
      {:code value, :name (keyword (str "app-" value))}

    :else
      (some #(when (= value (val %))
               {:code value, :name (key %)})
            algorithm-codes)))

(defn length [mhash]
  (/ (count (:hex-digest mhash)) 2))

(defn digest [mhash]
  (hex/decode (:hex-digest mhash)))

(defn algorithm [mhash]
  (:name (get-algorithm (:code mhash))))

;; ## Multihash Type

;; Multihash identifiers have two properties:
;;
;; - `code` is a numeric code for an algorithm entry in `algorithm-codes` or an
;;   application-specific algorithm code.
;; - `hex-digest` is a string holding the hex-encoded algorithm output.
;;
;; Multihash values also support metadata.
#?(:bb
   ;; In babashka a multihash is a record
   (defrecord Multihash [^long code ^String hex-digest]
     Object
     (toString
       [_]
       (str "hash:" (name (:name (get-algorithm code))) \: hex-digest)))

   :default
   ;; In CLJ and CLJS a multihash is a deftype with custom lookup, etc.
   (deftype Multihash
       [^long code ^String hex-digest _meta]

     Object

     (toString
       [this]
       (str "hash:" (name (:name (get-algorithm code))) \: hex-digest))

     #?(:clj java.io.Serializable)

     #?(:cljs IEquiv)

     (#?(:clj equals, :cljs -equiv)
       [this that]
       (cond
         (identical? this that) true
         (instance? Multihash that)
         (and (= code (:code that))
              (= hex-digest (:hex-digest that)))
         :else false))


     #?(:cljs IHash)

     (#?(:clj hashCode, :cljs -hash)
       [this]
       (hash-combine code hex-digest))


     #?(:clj Comparable, :cljs IComparable)

     (#?(:clj compareTo, :cljs -compare)
       [this that]
       (cond
         (= this that) 0
         (< code (:code that)) -1
         (> code (:code that))  1
         :else (compare hex-digest (:hex-digest that))))


     ILookup

     (#?(:clj valAt, :cljs -lookup)
       [this k]
       (#?(:clj .valAt, :cljs -lookup) this k nil))

     (#?(:clj valAt, :cljs -lookup)
       [this k not-found]
       (case k
         :code code
         :algorithm (or (algorithm this)
                        not-found)
         :length (length this)
         :digest (digest this)
         :hex-digest hex-digest
         not-found))


     IMeta

     (#?(:clj meta, :cljs -meta)
       [this]
       _meta)


     #?(:clj IObj, :cljs IWithMeta)

     (#?(:clj withMeta, :cljs -with-meta)
       [this meta-map]
       (Multihash. code hex-digest meta-map))))

(defn create
  "Constructs a new Multihash identifier. Accepts either a numeric algorithm
  code or a keyword name as the first argument. The digest may either by a byte
  array or a hex string."
  [algorithm digest]
  (let [algo (get-algorithm algorithm)]
    (when-not (integer? (:code algo))
      (throw (ex-info
               (str "Argument " (pr-str algorithm) " does not "
                    "represent a valid hash algorithm.")
               {:algorithm algorithm})))
    (let [hex-digest (if (string? digest) digest (hex/encode digest))
          byte-len (/ (count hex-digest) 2)]
      (when (< 127 byte-len)
        (throw (ex-info (str "Digest length must be less than 128 bytes: "
                             byte-len)
                        {:length byte-len})))
      (when-let [err (hex/validate hex-digest)]
        (throw (ex-info err {:hex-digest hex-digest})))
      #?(:bb (->Multihash (:code algo) hex-digest)
         :default (->Multihash (:code algo) hex-digest nil)))))

;; ## Encoding and Decoding

(defn encode
  "Encodes a multihash into a binary representation."
  ^bytes
  [mhash]
  (let [len (length mhash)
        encoded (bytes/byte-array (+ len 2))]
    (bytes/set-byte encoded 0 (:code mhash))
    (bytes/set-byte encoded 1 len)
    (bytes/copy (digest mhash) 0 encoded 2 len)
    encoded))


(defn hex
  "Encodes a multihash into a hexadecimal string."
  ^String
  [mhash]
  (when mhash
    (hex/encode (encode mhash))))


(defn base58
  "Encodes a multihash into a Base-58 string."
  ^String
  [mhash]
  (when mhash
    (b58/encode (encode mhash))))


(defn decode-array
  "Decodes a byte array directly into multihash. Throws `ex-info` with a `:type`
  of `:multihash/bad-input` if the data is malformed or invalid."
  [^bytes encoded]
  (let [encoded-size (alength encoded)
        min-size 3]
    (when (< encoded-size min-size)
      (throw (ex-info
               (str "Cannot read multihash from byte array: " encoded-size
                    " is less than the minimum of " min-size)
               {:type :multihash/bad-input}))))
  (let [code (bytes/get-byte encoded 0)
        length (bytes/get-byte encoded 1)
        payload (- (alength encoded) 2)]
    (when-not (pos? length)
      (throw (ex-info
               (str "Encoded length " length " is invalid")
               {:type :multihash/bad-input})))
    (when (< payload length)
      (throw (ex-info
               (str "Encoded digest length " length " exceeds actual "
                    "remaining payload of " payload " bytes")
               {:type :multihash/bad-input})))
    (let [digest (bytes/byte-array length)]
      (bytes/copy encoded 2 digest 0 length)
      (create code digest))))


#?(:clj
   (defn- read-stream-digest
     "Reads a byte digest array from an input stream. First reads a byte giving
     the length of the digest data to read. Throws an ex-info if the length is
     invalid or there is an error reading from the stream."
     ^bytes
     [^InputStream input]
     (let [length (.read input)]
       (when-not (pos? length)
         (throw (ex-info
                  (format "Byte %02x is not a valid digest length." length)
                  {:type :multihash/bad-input})))
       (let [digest (byte-array length)]
         (loop [offset 0
                remaining length]
           (let [n (.read input digest offset remaining)]
             (if (< n remaining)
               (recur (+ offset n) (- remaining n))
               digest)))))))


(defprotocol Decodable
  "This protocol provides a method for data sources which a multihash can be
  read from."

  (decode
    [source]
    "Attempts to read a multihash value from the data source."))


(extend-protocol Decodable

  #?(:clj (class (byte-array 0))
     :cljs js/Uint8Array)

  (decode
    [source]
    (decode-array source))


  #?(:clj java.lang.String
     :cljs string)

  (decode
    [source]
    (decode-array
      (if (hex/valid? (str source))
        (hex/decode (str source))
        (b58/decode (str source)))))


  #?@(:clj
      [InputStream

       (decode
         [source]
         (let [code (.read source)
               digest (read-stream-digest source)]
           (create code digest)))]))
