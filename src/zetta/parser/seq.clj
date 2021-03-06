(ns zetta.parser.seq
  (:refer-clojure
   :exclude [ensure get take take-while char some replicate])
  (:require [clojure.core :as core]
            [clojure.string :as str])

  (:use zetta.core)
  (:use zetta.combinators))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; ## Utility Functions
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- span
  [pred xs]
  ((core/juxt #(core/take-while pred %) #(core/drop-while pred %)) xs))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; ## Basic Parsers
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def demand-input
  "Basic parser that will ensure the request of more input via a continuation."
  (fn [input0 more0 err-fn0 ok-fn0]
    (if (complete? more0)
      #(err-fn0 input0 more0 ["demand-input"] "not enough input")
      (letfn [
        (err-fn [input more]
          #(err-fn0 input more ["demand-input"] "not enough input"))
        (ok-fn [input more]
          #(ok-fn0 input more nil))]
      (prompt input0 more0 err-fn ok-fn)))))

(def want-input?
  "Parser that returns `true` if any input is available either immediately or
  on demand, and `false` if the end of all input has been reached.

  **WARNING**: This parser always succeeds."
  (fn [input0 more0 _err-fn ok-fn0]
    (cond
      (not (empty? input0)) #(ok-fn0 input0 more0 true)
      (complete? more0) #(ok-fn0 input0 more0 false)
      :else
        (letfn [(err-fn [input more] #(ok-fn0 input more false))
                (ok-fn [input more] #(ok-fn0 input more true))]
        (prompt input0 more0 err-fn ok-fn)))))

(defn ensure
  "If at least `n` items of input are available, return the current input,
  otherwise fail."
  [n]
  (fn [input0 more0 err-fn ok-fn]
    (if (>= (count input0) n)
      #(ok-fn input0 more0 input0)
      (with-parser
        ((>> demand-input (ensure n)) input0 more0 err-fn ok-fn)))))

(def get
  "Returns the input given in the `zetta.core/parse` function."
  (fn [input0 more0 _err-fn ok-fn]
    #(ok-fn input0 more0 input0)))

(defn put [s]
  "Sets a (possibly modified) input into the parser state."
  (fn [_input0 more0 _err-fn ok-fn]
    #(ok-fn s more0 nil)))

(defn satisfy?
  "Parser that succeeds for any item for which the predicate `pred` returns
  `true`. Returns the item that is actually parsed."
  [pred]
  (do-parser
    [input (ensure 1)
     :let  [item (first input)]
     :if (pred item)
       :then [
         _ (put (rest input))
       ]
       :else [
        _ (fail-parser "satisfy?")
       ]]
    item))

(defn skip
  "Parser that succeeds for any item for which the predicate `pred`, returns
  `nil`."
  [pred]
  (do-parser
    [input (ensure 1)
     :if (pred (first input))
       :then [_ (put (rest input))]
       :else [_ (fail-parser "skip")]]
     nil))

(defn take-with
  "Parser that matches `n` items of input, but succeed only if the predicate
  `pred` returns `true` on the parsed input. The matched input is returned
  as a seq."
  [n pred]
  (do-parser
    [input (ensure n)
     :let [[h t] (split-at n input)]
     :if (pred h)
       :then [_ (put t)]
       :else [_ (fail-parser "take-with")]]
     h))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; ## High Level Parsers
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn take
  "Parser that matches exactly `n` items from input. Returnes the matched
  input as a seq."
  [n]
  (take-with n (constantly true)))

(defn string
  "Parses a sequence of items that identically match a given string `s`.
  Returns the parsed string. This parser consumes no input if it fails (even
  with a partial match)."
  [#^java.lang.String s]
  (let [ch-vec (vec s)]
    (<$> str/join
         (take-with (count s) #(= ch-vec %)))))

(defn skip-while
  "Parser that skips input for as long as `pred` returns `true`."
  [pred]
  (let [skip-while-loop (do-parser
             [input0 get
              :let [input (drop-while pred input0)]
              _ (put input)
              :if (empty? input)
              :then [
                input-available? want-input?
                :if input-available?
                :then [_ (skip-while pred)]
                :else []
              ]
              :else []]
              nil)]
     skip-while-loop))

(defn take-while
  "Parser that matches input as long as pred returns `true`, and return
   the consumed input as a seq.

   This parser does not fail.  It will return an empty seq if the predicate
   returns `false` on the first token of input.

   **WARNING**: Because this parser does not fail, do not use it with
   combinators such as `many`, because such parsers loop until a failure
   occurs. Careless use will thus result in an infinite loop."
  [pred]
  (letfn [
    (take-while-loop [acc]
      (do-parser
        [input0 get
         :let [[pre post] (span pred input0)]
         _ (put post)
         :if (empty? post)
           :then [
             input-available? want-input?
             :if input-available?
               :then [result (take-while-loop (conj acc pre))]
               :else [result (always (conj acc pre))]
           ]
           :else [result (m-result (conj acc pre))]]
           result))]
  (<$> (comp #(apply core/concat %) core/reverse)
       (take-while-loop []))))

(defn take-till
  "Matches input as long as `pred` returns `false`
  (i.e. until it returns `true`), and returns the consumed input as a seq.

  This parser does not fail.  It will return an empty seq if the predicate
  returns `true` on the first item from the input.

  **WARNING**: Because this parser does not fail, do not use it with combinators
  such as `many`, because such parsers loop until a failure occurs. Careless
  use will thus result in an infinite loop."
  [pred]
  (take-while (complement pred)))

(def take-rest
  "Parser that returns the rest of the seqs that are given to the parser,
  the result will be a seqs of seqs where the number of seqs
  from the first level will represent the number of times a
  continuation was used to continue the parse process."
  (letfn [
    (take-rest-loop [acc]
      (do-parser
        [input-available? want-input?
         :if input-available?
           :then [
             input get
             _ (put [])
             result (take-rest-loop (conj acc input))
           ]
           :else [
             result (always (reverse acc))
           ]]
          result))]
  (take-rest-loop [])))

(defn take-while1
  "Parser that matches input as long as pred returns `true`. This parser
   returns the consumed input in a seq.

   This parser will fail if a first match is not accomplished."
  [pred]
  (do-parser
    [input get
     :if (empty? input)
       :then [_ demand-input]
       :else []
     input get
     :let [[pre post] (span pred input)]
     :if (empty? pre)
       :then [_ (fail-parser "take-while1")]
       :else [_ (put post)]
     :if (empty? post)
       :then [remainder (take-while pred)
              result (always (concat pre remainder))]
       :else [result (always pre)]]
     result))

(def any-token
  "Parser that matches any element from the input seq, it will return the
  parsed element from the seq."
  (satisfy? (constantly true)))

(defn char
  "Parser that matches only a token that is equal to character `c`, the
  character is returned."
  [#^java.lang.Character c]
  (cond
    (set? c)
      (<?> (satisfy? #(contains? c %))
           (str "failed parser char: " c))
    :else
      (<?> (satisfy? #(= % c))
           (str "failed parser char: " c))))

(def whitespace
  "Parser that matches any character that is considered a whitespace, it uses
  `Character/isWhitespace` internally. This parser returns the whitespace
  character."
  (satisfy? #(Character/isWhitespace #^java.lang.Character %)))

(def space
  "Parser that matches any character that is equal to the character `\\space`.
  This parser returns the `\\space` character."
  (char \space))

(def spaces
  "Parser that matches many spaces. Returns a seq of space characters"
  (many space))

(def skip-spaces
  "Parser that skips many spaces. Returns `nil`."
  (skip-many space))

(def skip-whitespaces
  "Parser that skips many whitespaces. Returns `nil`."
  (skip-many whitespace))

(defn not-char
  "Parser that matches only an item that is not equal to character `c`, the
  item is returned."
  [#^java.lang.Character c]
  (cond
    (set? c)
      (<?> (satisfy? #(not (contains? c %)))
           (str c))
    :else
      (<?> (satisfy? #(not (= % c)))
           (str c))))

(def letter
  "Parser that matches any character that is considered a letter, it uses
  `Character/isLetter` internally. This parser will return the matched
  character."

  (satisfy? #(Character/isLetter ^java.lang.Character %)))

(def word
  "Parser that matches a word, e.g `(many1 letter)`, returns the parsed word."
  (<$> str/join (many1 letter)))

(def digit
  "Parser that matches any character that is considered a digit, it uses
  `Character/isDigit` internally. This parser will return the matched digit
  character."
  (satisfy? #(Character/isDigit #^java.lang.Character %)))

; Using the clojure LispReader, code found on stackoverflow
; http://stackoverflow.com/questions/2640169/whats-the-easiest-way-to-parse-numbers-in-clojure
(let [m (.getDeclaredMethod clojure.lang.LispReader
                            "matchNumber"
                            (into-array [String]))]
  (.setAccessible m true)
  (defn- read-number [s]
    (.invoke m clojure.lang.LispReader (into-array [s]))))

(def double-or-long
  (letfn [
    (digit-or-dot []
      (do-parser [
        h (<|> digit (char \.))
        :if (= h \.)
        :then [ t (many digit) ]
        :else [ t (<|> (digit-or-dot) (always [])) ]]
        (cons h t)))]
  (do-parser [
    h digit
    t (<|> (digit-or-dot) (always []))]
    (cons h t))))

(def number
  "Parser that matches one or more digit characters and returns a number in
  base 10."
  (<$> (comp read-number str/join)
       double-or-long))

(def end-of-input
  "Parser that matches only when the end-of-input has been reached, otherwise
  it fails. Returns a nil value."
  (fn [input0 more0 err-fn0 ok-fn0]
    (if (empty? input0)
      (if (complete? more0)
        #(ok-fn0 input0 more0 nil)
        (letfn [
          (err-fn [input1 more1 _ _]
            (add-parser-stream input0 more0 input1 more1
                               (fn [input2 more2]
                                  (ok-fn0 input2 more2 nil))))
          (ok-fn [input1 more1 _]
            (add-parser-stream input0 more0 input1 more1
                               (fn [input2 more2]
                                  (err-fn input2 more2 [] "end-of-input"))))]
        (demand-input input0 more0 err-fn ok-fn)))
      #(err-fn0 input0 more0 [] "end-of-input"))))

(def at-end?
  "Parser that never fails, it returns `true` when the end-of-input
  is reached, `false` otherwise."
  (<$> not want-input?))

(def eol
  "Parser that matches different end-of-line characters/sequences.
  This parser returns a nil value."
  (<|> (*> (char \newline) (always nil))
       (*> (string "\r\n") (always nil))))


