;;   Copyright (c) Dragan Djuric. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php) or later
;;   which can be found in the file LICENSE at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns uncomplicate.fluokitten.algo
  (:require [clojure.string :as cs]
            [uncomplicate.fluokitten
             [protocols :refer :all]
             [utils :refer [with-context deref?]]])
  (:require [clojure.core.reducers :as r]))

;;====================== Default functions ==========

(defn default-foldmap
  ([x g]
   (fold (fmap x g)))
  ([x g f init]
   (fold (fmap x g) f init))
  ([x g f init y]
   (fold (fmap x g [y]) f init))
  ([x g f init y z]
   (fold (fmap x g [y z]) f init))
  ([x g f init y z w]
   (fold (fmap x g [y z w]) f init))
  ([x g f init y z w ws]
   (fold (fmap x g (cons y (cons z (cons w ws)))) f init)))

(defn default-bind
  ([m g]
   (join (fmap m g)))
  ([m g ms]
   (join (fmap m g ms))))

;; ==================== Object =====================

(defn object-fmap
  ([o f]
   (f o))
  ([o f os]
   (apply f o os)))

(defn object-pure [o x]
  x)

(defn object-foldmap
  ([x g]
   (g x))
  ([x g f init]
   (f init (g x)))
  ([x g f init y]
   (f init (g x y)))
  ([x g f init y z]
   (f init (g x y z)))
  ([x g f init y z w]
   (f init (g x y z w)))
  ([x g f init y z w ws]
   (f init (g x y z w ws))))

(defn object-fold
  ([x]
   x)
  ([x f init]
   (f init x))
  ([x f init y]
   (f init (op x y)))
  ([x f init y z]
   (f init (op x y z)))
  ([x f init y z w]
   (f init (op x y z w)))
  ([x f init y z w ws]
   (f init (op x y z w ws))))

(extend Object
  Functor
  {:fmap object-fmap}
  Applicative
  {:fapply object-fmap
   :pure object-pure}
  Monad
  {:join identity
   :bind default-bind}
  Foldable
  {:fold object-fold
   :foldmap object-foldmap})

;;=============== Functor implementations =========================
;;--------------- fmap implementations ---------------

(defn collreduce-fmap
  ([cr g]
   (r/map g cr))
  ([_ _ _]
   (throw (java.lang.UnsupportedOperationException.
           "fmap for reducibles does not support varargs."))))

(defn reducible-fmap
  ([c g]
   (into (empty c)
         (r/map g c)))
  ([c g cs]
   (into (empty c)
         (apply map g c cs))))

(defn ^:private group-entries-xf [k]
  (comp (map #(find % k))
        (remove nil?)
        (map val)))

(defn ^:private map-fmap-xf
  ([g ms]
   (comp (mapcat keys)
         (dedupe)
         (map (fn [k] [k (apply g (into [] (group-entries-xf k) ms))])))))

(defn map-fmap
  ([m g]
   (into (empty m) (r/map (fn [[k v]] [k (g v)]) m)))
  ([m g ms]
   (let [ms (cons m ms)]
     (into (empty m)
           (map-fmap-xf g ms)
           ms))))

(defn list-fmap
  ([l g]
   (with-meta
     (apply list (map g l))
     (meta l)))
  ([l g ss]
   (with-meta
     (apply list (apply map g l ss))
     (meta l))))

(defn seq-fmap
  ([s g]
   (with-meta
     (map g s)
     (meta s)))
  ([s g ss]
   (with-meta
     (apply map g s ss)
     (meta s))))

(defn coll-fmap
  ([c g]
   (into (empty c) (map g c)))
  ([c g ss]
   (into (empty c) (apply map g c ss))))

;;================ Applicative implementations ==================
;;-------------- fapply implementations ----------------

(defn collreduce-fapply [crv crg]
  (r/mapcat #(r/map % crv) crg))

(defn collreduce-pure [_ v]
  (r/map identity [v]))

(defn reducible-fapply
  ([cv cg]
   (into (empty cv)
         (collreduce-fapply cv cg)))
  ([cv cg cvs]
   (into (empty cv)
         (r/mapcat #(apply map % cv cvs) cg))))

(defn map-fapply
  ([mv mg]
   (if-let [f (mg nil)]
     (into (empty mv)
           (r/map (fn [[kv vv]]
                    (if-let [eg (find mg kv)]
                      [kv ((val eg) vv)]
                      [kv (f vv)]))
                  mv))
     (into mv
           (comp (map (fn [[kg vg]]
                        (if-let [ev (find mv kg)]
                          [kg (vg (val ev))])))
                 (remove nil?))
           mg)))
  ([mv mg mvs]
   (let [ms (cons mv mvs)]
     (if-let [f (mg nil)]
       (into (empty mv)
             (comp (mapcat keys)
                   (dedupe)
                   (map (fn [kv]
                          (let [vs (into [] (group-entries-xf kv) ms)
                                fun (if-let [eg (find mg kv)] (val eg) f)]
                            [kv (apply fun vs)]))))
             ms)
       (into (apply merge mv mvs)
             (comp (map (fn [[kg vg]]
                          (if-let [vs (seq (into [] (group-entries-xf kg) ms))]
                            [kg (apply vg vs)])))
                   (remove nil?))
             mg)))))

(defn list-fapply
  ([cv cg]
   (with-meta
     (apply list (mapcat #(map % cv) cg))
     (meta cv)))
  ([cv cg cvs]
   (with-meta
     (apply list (mapcat #(apply map % cv cvs) cg))
     (meta cv))))

(defn seq-fapply
  ([cv cg]
   (with-meta
     (mapcat #(map % cv) cg)
     (meta cv)))
  ([cv cg cvs]
   (with-meta
     (mapcat #(apply map % cv cvs) cg)
     (meta cv))))

(defn coll-fapply
  ([cv cg]
   (into (empty cv)
         (mapcat #(map % cv) cg)))
  ([cv cg cvs]
   (into (empty cv)
         (mapcat #(apply map % cv cvs) cg))))

(defn coll-pure
  ([cv v]
   (conj (empty cv) v))
  ([cv v vs]
   (into (coll-pure cv v) vs)))

(defn lazyseq-pure
  ([cv v]
   (lazy-seq (coll-pure cv v)))
  ([cv v vs]
   (lazy-seq
    (if v
      (cons v (lazyseq-pure cv (first vs) (rest vs)))
      (empty cv) ))))

(defn map-pure
  ([m v]
   (coll-pure m [nil v]))
  ([m v vs]
   (into (empty m)
         (if (vector? v)
           (cons v vs)
           (apply hash-map v vs)))))

;;================== Monad Implementations ======================

(defn collreduce-bind
  ([cr g]
   (r/mapcat g cr))
  ([cr g ss]
   (throw (ex-info "bind for reducibles does not support varargs."
                   {:exception-type :unsupported-operation}))))

(defn reducible-bind
  ([c g]
   (into (empty c)
         (r/mapcat g c)))
  ([c g ss]
   (into (empty c)
         (apply mapcat g c ss))))

(let [flatten-keys (fn [[k x :as e]]
                     (if (map? x)
                       (map (fn [[kx vx]]
                              [(if (and k kx)
                                 (vec (flatten [k kx]))
                                 (or k kx))
                               vx])
                            x)
                       [e]))]

  (defn map-join [m]
    (into (empty m) (r/mapcat flatten-keys m)))

  (defn map-bind
    ([m g]
     (into (empty m)
           (comp (map (fn [[k v]] [k (g v)]))
                 (mapcat flatten-keys))
           m))
    ([m g ms]
     (let [ms (cons m ms)]
       (into (empty m)
             (comp (map-fmap-xf g ms)
                   (mapcat flatten-keys))
             ms)))))

(defn coll-bind
  ([c g]
   (into (empty c)
         (mapcat g) c))
  ([c g ss]
   (into (empty c)
         (apply mapcat g c ss))))

(defn ^:private join-op [c e]
  (if (coll? e)
    (op c e)
    (conj c e)))

(defn seq-join [c]
  (with-meta
    (reduce join-op [] c)
    (meta c)))

(defn coll-join [c]
  (into (empty c) (seq-join c)))

(defn list-join [c]
  (with-meta
    (apply list (seq-join c))
    (meta c)))

(let [combinef (fn
                 ([] [])
                 ([x y]
                  (if (coll? x)
                    (join-op x y)
                    (if (coll? y)
                      (conj y x)
                      [x y]))))]
  (defn collreduce-join [c]
    (r/fold combinef c)))

(defn reducible-join [c]
  (into (empty c)
        (collreduce-join c)))

(defn list-bind
  ([c g]
   (with-meta
     (apply list (mapcat g c))
     (meta c)))
  ([c g ss]
   (with-meta
     (apply list (apply mapcat g c ss))
     (meta c))))

(defn seq-bind
  ([c g]
   (with-meta
     (mapcat g c)
     (meta c)))
  ([c g ss]
   (with-meta
     (apply mapcat g c ss)
     (meta c))))

(defn lazyseq-bind
  ([c g]
   (seq-bind c g))
  ([c g ss]
   (seq-bind c g ss)))

;;======== Algebraic structures implementations ==================

(defn coll-op* [zero]
  (fn coll-op
    ([]
     zero)
    ([x]
     coll-op)
    ([x y]
     (into x y))
    ([x y z]
     (into x cat [y z]))
    ([x y z w]
     (into x cat [y z w]))
    ([x y z w ws]
     (into x cat (into [y z w] ws)))))

(defn collreduce-op
  ([]
   [])
  ([x]
   collreduce-op)
  ([x y]
   (if (instance? clojure.core.protocols.CollReduce y)
     (r/cat x y)
     (into x y)))
  ([x y z]
   (collreduce-op x (collreduce-op y z)))
  ([x y z w]
   (collreduce-op (collreduce-op x y) (collreduce-op z w)))
  ([x y z w ws]
   (collreduce-op (collreduce-op x y z w) (r/fold collreduce-op ws))))

(defn reducible-op
  ([]
   [])
  ([x]
   reducible-op)
  ([x y]
   (into x y))
  ([x y z]
   (into x (collreduce-op y z)))
  ([x y z w]
   (into x (collreduce-op y z w)))
  ([x y z w ws]
   (into x (collreduce-op y z w (first ws) (rest ws)))))

(defn seq-op* [zero]
  (fn seq-op
    ([]
     zero)
    ([x]
     seq-op)
    ([x y]
     (concat x y))
    ([x y z]
     (concat x y z))
    ([x y z w]
     (concat x y z w))
    ([x y z w ws]
     (apply concat x y z w ws))))

(defn list-op
  ([]
   (list))
  ([x]
   list-op)
  ([x y]
   (apply list (concat x y)))
  ([x y z]
   (apply list (concat x y z)))
  ([x y z w]
   (apply list (concat x y z w)))
  ([x y z w ws]
   (apply list (apply concat x y z w ws))))

;;================== Foldable ===================================

(defn collection-foldmap
  ([c g]
   (if-let [e (first c)]
     (let [ge (g e)]
       (r/fold (op ge) (r/map g c)))))
  ([c g f init]
   (f init (r/fold f (r/map g c))))
  ([cx g f init cy]
   (loop [acc init cx cx cy cy]
     (if cx
       (recur (f acc (g (first cx) (first cy))) (next cx) (next cy))
       acc)))
  ([cx g f init cy cz]
   (loop [acc init cx cx cy cy cz cz]
     (if cx
       (recur (f acc (g (first cx) (first cy) (first cz)))
              (next cx) (next cy) (next cz))
       acc)))
  ([cx g f init cy cz cw]
   (loop [acc init cx cx cy cy cz cz cw cw]
     (if cx
       (recur (f acc (g (first cx) (first cy) (first cz) (first cw)))
              (next cx) (next cy) (next cz) (next cw))
       acc)))
  ([cx g f init cy cz cw cws]
   (loop [acc init cx cx cy cy cz cz cw cw cws cws]
     (if cx
       (recur (f acc (apply g (first cx) (first cy) (first cz) (first cw)
                            (map first cws)))
              (next cx) (next cy) (next cz) (next cw) (map next cws))
       acc))))

(defn collection-fold
  ([c]
   (if-let [e (first c)]
     (r/fold (op e) c)))
  ([c f init]
   (f init (r/fold f c)))
  ([cx f init cy]
   (collection-foldmap cx op f init cy))
  ([cx f init cy cz]
   (collection-foldmap cx op f init cy cz))
  ([cx f init cy cz cw]
   (collection-foldmap cx op f init cy cz cw))
  ([cx f init cy cz cw cws]
   (loop [acc init cx cx cy cy cz cz cw cw cws cws]
     (if (and cx cy cz cw (not-any? empty? cws))
       (recur (f acc (op (first cx) (first cy) (first cz) (first cw) (map first cws)))
              (next cx) (next cy) (next cz) (next cw) (map next cws))
       acc))))

(defn map-fold
  ([m]
   (collection-fold (vals m)))
  ([m f init]
   (collection-fold (vals m) f init))
  ([mx f init my]
   (collection-fold (vals mx) f init (vals my)))
  ([mx f init my mz]
   (collection-fold (vals mx) f init (vals my) (vals mz)))
  ([mx f init my mz mw]
   (collection-fold (vals mx) f init (vals my) (vals mz) (vals mw)))
  ([mx f init my mz mw mws]
   (collection-fold (vals mx) f init (vals my) (vals mz) (vals mw) (map vals mws))))

(defn collfold-fold
  ([c]
   (if-let [e (first (into [] (r/take 1 c)))]
     (collection-fold c (op e) (id e) )))
  ([c f init]
   (collection-fold c f init)))

;;================== Collections Extensions =====================

(defmacro extend-collection [t]
  `(extend ~t
     Functor
     {:fmap coll-fmap}
     Applicative
     {:pure coll-pure
      :fapply coll-fapply}
     Monad
     {:join coll-join
      :bind coll-bind}
     Foldable
     {:fold collection-fold
      :foldmap collection-foldmap}
     Magma
     {:op (coll-op* [])}
     Monoid
     {:id empty}))

(defmacro extend-vector [t]
  `(extend ~t
     Functor
     {:fmap reducible-fmap}
     Applicative
     {:pure coll-pure
      :fapply reducible-fapply}
     Monad
     {:join reducible-join
      :bind reducible-bind}
     Magma
     {:op reducible-op}))

(defmacro extend-list [t]
  `(extend ~t
     Functor
     {:fmap list-fmap}
     Applicative
     {:pure coll-pure
      :fapply list-fapply}
     Monad
     {:join list-join
      :bind list-bind}
     Magma
     {:op list-op}))

(defmacro extend-seq [t]
  `(extend ~t
     Functor
     {:fmap seq-fmap}
     Applicative
     {:pure coll-pure
      :fapply seq-fapply}
     Monad
     {:join seq-join
      :bind seq-bind}
     Magma
     {:op (seq-op* (list))}))

(defmacro extend-lazyseq [t]
  `(extend ~t
     Functor
     {:fmap seq-fmap}
     Applicative
     {:pure lazyseq-pure
      :fapply seq-fapply}
     Monad
     {:join seq-join
      :bind lazyseq-bind}
     Magma
     {:op (seq-op* (lazy-seq))}))

(defmacro extend-set [t]
  `(extend ~t
     Functor
     {:fmap reducible-fmap}
     Applicative
     {:pure coll-pure
      :fapply reducible-fapply}
     Monad
     {:join coll-join
      :bind reducible-bind}
     Magma
     {:op (coll-op* #{})}))

(defmacro extend-map [t]
  `(extend ~t
     Functor
     {:fmap map-fmap}
     Applicative
     {:pure map-pure
      :fapply map-fapply}
     Monad
     {:join map-join
      :bind map-bind}
     Foldable
     {:fold map-fold
      :foldmap default-foldmap}
     Magma
     {:op (coll-op* {})}))

(extend clojure.core.protocols.CollReduce
  Functor
  {:fmap collreduce-fmap}
  Applicative
  {:pure collreduce-pure
   :fapply collreduce-fapply}
  Monad
  {:join collreduce-join
   :bind collreduce-bind}
  Magma
  {:op collreduce-op})

(extend clojure.core.reducers.CollFold
  Foldable
  {:fold collfold-fold
   :foldmap default-foldmap})

(declare create-mapentry)

(defn mapentry-fmap
  ([[ke ve] g]
   (create-mapentry ke (g ve)))
  ([[ke ve] g es]
   (create-mapentry ke (apply g ve (vals es)))))

(defn mapentry-pure [e v]
  (create-mapentry nil v))

(defn mapentry-fapply
  ([[ke ve :as e] [kg vg]]
   (if (or (nil? kg) (= ke kg))
     (create-mapentry ke (vg ve))
     e))
  ([[ke ve :as e] [kg vg] es]
   (if (or (nil? kg)
           (not-any? (fn [[k _]]
                       (not= k kg))
                     (cons e es)))
     (create-mapentry ke (apply vg ve (map val es)))
     e)))

(defn mapentry-join [[k x :as e]]
  (if (vector? x)
    (let [[kx vx] x]
      (create-mapentry (if (and k kx)
                         (vec (flatten [k kx]))
                         (or k kx))
                       vx))
    e))

(defn mapentry-id [[kx vx]]
  (create-mapentry (id kx) (id vx)))

(defn ^:private op-id [x]
  (let [zero (id x)]
    (fn
      ([] zero)
      ([x]
       op-id)
      ([x y] (op x y))
      ([x y z] (op x y z))
      ([x y z v] (op x y z v))
      ([x y z v ws] (op x y z v ws)))))

(defn mapentry-op
  ([x]
   (op-id x))
  ([[kx vx] [ky vy]]
   (create-mapentry (op kx ky) (op vx vy)))
  ([[kx vx] [ky vy] [kz vz]]
   (create-mapentry (op kx ky kz) (op vx vy vz)))
  ([[kx vx] [ky vy] [kz vz] [kw vw]]
   (create-mapentry (op kx ky kz kw) (op vx vy vz vw)))
  ([[kx vx] [ky vy] [kz vz] [kw vw] es]
   (create-mapentry (op kx ky kz kw (map key es))
                    (op vx vy kz kw (map val es)))))

(defn mapentry-foldmap
  ([e g]
   (mapentry-foldmap e g op (id (g (val e)))))
  ([e g f init]
   (f init (g (val e))))
  ([e g f init e1]
   (f init (g (val e) (val e1))))
  ([e g f init e1 e2]
   (f init (g (val e) (val e1) (val e2))))
  ([e g f init e1 e2 e3]
   (f init (g (val e) (val e1) (val e2) (val e3))))
  ([e g f init e1 e2 e3 es]
   (f init (apply g (val e) (val e1) (val e2) (val e3) (map val es)))))

(defn mapentry-fold
  ([e]
   (val e))
  ([e f init]
   (f init (val e)))
  ([e f init e1]
   (mapentry-foldmap e op f init e1))
  ([e f init e1 e2]
   (mapentry-foldmap e op f init e1 e2))
  ([e f init e1 e2 e3]
   (mapentry-foldmap e op f init e1 e2 e3))
  ([e f init e1 e2 e3 es]
   (f init (op (val e) (val e1) (val e2) (val e3) (map val es)))))

(defmacro extend-mapentry [t]
  `(extend ~t
     Functor
     {:fmap mapentry-fmap}
     Applicative
     {:pure mapentry-pure
      :fapply mapentry-fapply}
     Monad
     {:join mapentry-join
      :bind default-bind}
     Magma
     {:op mapentry-op}
     Monoid
     {:id mapentry-id}
     Foldable
     {:fold mapentry-fold
      :foldmap mapentry-foldmap}))

;;===================== Literals Extensions ================

(defn ^:private deref-resolve [s]
  (deref (resolve (symbol (name s)))))

(defn ^:private to-string
  ([s]
   (if (sequential? s)
     (cs/join s)
     (str s)))
  ([s ss]
   (apply str (to-string s) (map to-string ss))))

(extend-type String
  Functor
  (fmap
    ([s g]
     (to-string (g s)))
    ([s g ss]
     (to-string (apply g s ss))))
  Applicative
  (fapply
    ([sv sg]
     (fmap sv (deref-resolve sg)))
    ([sv sg svs]
     (fmap sv (deref-resolve sg) svs)))
  (pure
    ([_ x]
     (to-string x)
     (str x))
    ([_ x xs]
     (to-string x xs)))
  Magma
  (op
    ([x]
     str)
    ([x y]
     (str x y))
    ([x y z]
     (str x y z))
    ([x y z w]
     (str x y z w))
    ([x y z w ws]
     (apply str x y z w ws)))
  Monoid
  (id [s] ""))

(extend-type Number
  Magma
  (op
    ([x] +)
    ([x y]
     (+ x y))
    ([x y z]
     (+ x y z))
    ([x y z w]
     (+ x y z w))
    ([x y z w ws]
     (apply + x y z w ws)))
  Monoid
  (id [x] 0))

(extend-type Double
  Magma
  (op
    ([x] +)
    ([x y]
     (+ (double x) (double y)))
    ([x y z]
     (+ (double x) (double y) (double z)))
    ([x y z w]
     (+ (double x) (double y) (double z) (double w)))
    ([x y z w ws]
     (apply + (+ (double x) (double y) (double z) (double w)) ws)))
  Monoid
  (id [x] 0.0))

(extend-type Float
  Magma
  (op
    ([x] +)
    ([x y]
     (+ (float x) (float y)))
    ([x y z]
     (+ (float x) (float y) (float z)))
    ([x y z w]
     (+ (float x) (float y) (float z) (float w)))
    ([x y z w ws]
     (apply + (+ (float x) (float y) (float z) (float w)) ws)))
  Monoid
  (id [x] 0.0))

(defn keyword-fmap
  ([k g]
   (keyword (fmap (name k) g)))
  ([k g ks]
   (keyword (fmap (name k) g (map name ks)))))

(defn keyword-pure
  ([k v]
   (keyword (str v)))
  ([k v vs]
   (keyword (apply str v vs))))

(defn keyword-fapply
  ([kv kg]
   (keyword-fmap kv (deref-resolve kg)))
  ([kv kg kvs]
   (keyword-fmap kv (deref-resolve kg) kvs)))

(def keyword-id (constantly (keyword "")))

(defn keyword-op
  ([] (keyword ""))
  ([x]
   keyword-op)
  ([x y]
   (keyword (str (name x) (name y))))
  ([x y z]
   (keyword (str (name x) (name y) (name z))))
  ([x y z w]
   (keyword (str (name x) (name y) (name z) (name w))))
  ([x y z w ws]
   (keyword (apply str (name x) (name y) (name z) (name w) ws))))

(defmacro extend-keyword [t]
  `(extend ~t
     Functor
     {:fmap keyword-fmap}
     Applicative
     {:fapply keyword-fapply
      :pure keyword-pure}
     Magma
     {:op keyword-op}
     Monoid
     {:id keyword-id}))

;;===================== Function ===========================

(defn function-op
  ([] identity)
  ([x] function-op)
  ([x y]
   (cond
     (identical? identity x) y
     (identical? identity y) x
     :default (comp x y)))
  ([x y z]
   (if (identical? identity z)
     (function-op x y)
     (comp (function-op x y) z)))
  ([x y z w]
   (function-op (function-op x y) (op z w)))
  ([x y z w ws]
   (apply comp (function-op x y z w) (filter (partial identical? identity) ws))))

(defn function-fmap
  ([f g]
   (function-op g f))
  ([f g hs]
   (fn
     ([]
      (apply g (f) (map #(%) hs)))
     ([x]
      (apply g (f x) (map #(% x) hs)))
     ([x & xs]
      (apply g (apply f x xs) (map #(apply % x xs) hs))))))

(defn function-fapply
  ([f g]
   (if (identical? identity g)
     f
     (fn
       ([]
        ((g) (f)))
       ([x]
        ((g x) (f x)))
       ([x & xs]
        ((apply g x xs) (apply f x xs))))))
  ([f g hs]
   (fn
     ([]
      (apply (g) (f) (map #(%) hs)))
     ([x]
      (apply (g x) (f x) (map #(% x) hs)))
     ([x & xs]
      (apply (apply g x xs) (apply f x xs) (map #(apply % x xs) hs))))))

(defn function-pure [f x]
  (fn [& _]
    x))

(defn function-join [f]
  (fn
    ([]
     (with-context f
       ((f))))
    ([x]
     (with-context f
       ((f x) x)))
    ([x & xs]
     (with-context f
       (apply (apply f x xs) x xs)))))

(defn function-bind
  ([f g]
   (join (fmap f g)))
  ([f g hs]
   (join (fmap f g hs))))

(defn function-fold
  ([fx]
   (fx))
  ([fx f init]
   (f init (fx)))
  ([fx f init fy]
   (f init (op (fx) (fy))))
  ([fx f init fy fz]
   (f init (op (fx) (fy) (fz))))
  ([fx f init fy fz fw]
   (f init (op (fx) (fy) (fz) (fw))))
  ([fx f init fy fz fw fws]
   (f init (op (fx) (fy) (fz) (fw) (map #(%) fws)))))

(defmacro extend-function [t]
  `(extend ~t
     Functor
     {:fmap function-fmap}
     Applicative
     {:fapply function-fapply
      :pure function-pure}
     Monad
     {:join function-join
      :bind function-bind}
     Foldable
     {:fold function-fold
      :foldmap default-foldmap}
     Magma
     {:op function-op}
     Monoid
     {:id (constantly identity)}))

;;====================== References =======================
;;----------------- Universal ------------------

(defn reference-fmap
  ([r g]
   (pure r (g (deref r))))
  ([r g rs]
   (pure r (apply g (deref r) (map deref rs)))))

(defn reference-fapply!
  ([rv rg]
   (fmap! rv (deref rg)))
  ([rv rg rvs]
   (apply fmap! rv (deref rg) rvs)))

(defn reference-fapply
  ([rv rg]
   (reference-fmap rv (deref rg)))
  ([rv rg rvs]
   (reference-fmap rv (deref rg) rvs)))

(defn reference-join! [r]
  (if (deref? @r)
    (fmap! r deref)
    r))

(defn reference-join [r]
  (if (deref? @r)
    (fmap r deref)
    r))

(defn reference-bind!
  ([mv g]
   (fmap! (fmap! mv g) deref))
  ([mv g mvs]
   (fmap! (apply fmap! mv g mvs) deref)))

(defn reference-bind
  ([mv g]
   (fmap! (fmap mv g) deref))
  ([mv g mvs]
   (fmap! (fmap mv g mvs) deref)))

(defn reference-op* [zero]
  (fn reference-op
    ([] zero)
    ([x]
     (reference-op* (id x)))
    ([rx ry]
     (pure rx (op @rx @ry)))
    ([rx ry rz]
     (pure rx (op @rx @ry @rz)))
    ([rx ry rz rw]
     (pure rx (op @rx @ry @rz @rw)))
    ([rx ry rz rw rws]
     (pure rx (op @rx @ry @rz @rw (map deref rws))))))

(defn reference-foldmap
  ([rx g]
   (g @rx))
  ([rx g f init]
   (f init (g @rx)))
  ([rx g f init ry]
   (f init (g @rx @ry)))
  ([rx g f init ry rz]
   (f init (g @rx @ry @rz)))
  ([rx g f init ry rz rw]
   (f init (g @rx @ry @rz @rw)))
  ([rx g f init ry rz rw rws]
   (f init (apply g @rx @ry @rz @rw (map deref rws)))))

(defn reference-fold
  ([r]
   (deref r))
  ([r f init]
   (f init (deref r)))
  ([rx f init ry]
   (reference-foldmap rx op f init ry))
  ([rx f init ry rz]
   (reference-foldmap rx op f init ry rz))
  ([rx f init ry rz rw]
   (reference-foldmap rx op f init ry rz rw))
  ([rx f init ry rz rw rws]
   (f init (f @rx @ry @rz @rw (map deref rws)))))

;;----------------- Atom -----------------------

(defn atom-fmap!
  ([a g]
   (do
     (swap! a g)
     a))
  ([ax g ry]
   (do
     (swap! ax g @ry)
     ax))
  ([ax g ry rz]
   (do
     (swap! ax g @ry @rz)
     ax))
  ([ax g ry rz rw]
   (do
     (swap! ax g @ry @rz @rw)
     ax))
  ([ax g ry rz rw rws]
   (do
     (apply swap! ax g @ry @rz @rw (map deref rws))
     ax)))

(defn atom-pure [_ v]
  (atom v))

(defn atom-id [a]
  (atom (id (deref a))))

;;------------------- Ref --------------------------

(defn ref-fmap!
  ([rx g]
   (do
     (alter rx g)
     rx))
  ([rx g ry]
   (do
     (alter rx g @ry)
     rx))
  ([rx g ry rz]
   (do
     (alter rx g @ry @rz)
     rx))
  ([rx g ry rz rw]
   (do
     (alter rx g @ry @rz @rw)
     rx))
  ([rx g ry rz rw rws]
   (do
     (apply alter rx g @ry @rz @rw (map deref rws))
     rx)))

(defn ref-pure [_ v]
  (ref v))

(defn ref-id [r]
  (ref (id @r)))

;;------------------ Extensions --------------------

(defmacro extend-atom [t]
  `(extend ~t
     Functor
     {:fmap reference-fmap}
     PseudoFunctor
     {:fmap! atom-fmap!}
     Applicative
     {:pure atom-pure
      :fapply reference-fapply}
     PseudoApplicative
     {:fapply! reference-fapply!}
     Monad
     {:join reference-join
      :bind reference-bind}
     PseudoMonad
     {:join! reference-join!
      :bind! reference-bind!}
     Foldable
     {:fold reference-fold
      :foldmap reference-foldmap}
     Magma
     {:op (reference-op* nil)}
     Monoid
     {:id atom-id}))

(defmacro extend-ref [t]
  `(extend ~t
     Functor
     {:fmap reference-fmap}
     PseudoFunctor
     {:fmap! ref-fmap!}
     Applicative
     {:pure ref-pure
      :fapply reference-fapply}
     PseudoApplicative
     {:fapply! reference-fapply!}
     Monad
     {:join reference-join
      :bind reference-bind}
     PseudoMonad
     {:join! reference-join!
      :bind! reference-bind!}
     Foldable
     {:fold reference-fold
      :foldmap reference-foldmap}
     Magma
     {:op (reference-op* nil)}
     Monoid
     {:id ref-id}))

;;================== Maybe  ===========================

(defn just-fmap
  ([jv g]
   (pure jv (g (value jv))))
  ([jv g jvs]
   (when-not (some nil? jvs)
     (pure jv (apply g (value jv) (map value jvs))))))

(defn just-fapply
  ([jv jg]
   (when jg
     (fmap jv (value jg))))
  ([jv jg jvs]
   (when jg
     (fmap jv (value jg) jvs))))

(defn just-bind
  ([jv g]
   (g (value jv)))
  ([jv g jvs]
   (when-not (some nil? jvs)
     (apply g (value jv) (map value jvs)))))

(defn just-join [jjv]
  (let [v (value jjv)]
    (if (or (not v) (satisfies? Maybe v)) v jjv)))

(defn just-fold
  ([x]
   (value x))
  ([x f init]
   (f init (value x)))
  ([x f init y]
   (f init (op x y)))
  ([x f init y z]
   (f init (op x y z)))
  ([x f init y z w]
   (f init (op x y z w)))
  ([x f init y z w ws]
   (f init (op x y z w ws))))

(defn just-foldmap
  ([x g]
   (g (value x)))
  ([x g f init]
   (f init (g (value x))))
  ([x g f init y]
   (when y
     (f init (g (value x) (value y)))))
  ([x g f init y z]
   (when (and y z)
     (f init (g (value x) (value y) (value y)))))
  ([x g f init y z w]
   (when (and y z w)
     (f init (g (value x) (value y) (value z) (value w)))))
  ([x g f init y z w ws]
   (when (and y z w (not-any? nil? ws))
     (f init (apply g (value x) (value y) (value z) (value w) (map value ws))))))

(defn ^:private just-op* [v y]
  (if y
    (op v (value y))
    v))

(defn just-op
  ([] nil)
  ([x] just-op)
  ([x y]
   (if y (pure x (just-op* (value x) y)) x))
  ([x y z]
   (let [v (value x)
         res (just-op* (just-op* v y) z)]
     (if (= v res) x (pure x res))))
  ([x y z w]
   (let [v (value x)
         res (-> (just-op* v y) (just-op* z) (just-op* w))]
     (if (= v res) x (pure x res))))
  ([x y z w ws]
   (let [v (value x)
         res (reduce just-op*
                     (-> (just-op* v y) (just-op* z) (just-op* w))
                     ws)]
     (if (= v res) x (pure x res)))))

(defmacro extend-just [t just-pure]
  `(extend ~t
     Functor
     {:fmap just-fmap}
     Applicative
     {:pure ~just-pure
      :fapply just-fapply}
     Monad
     {:join just-join
      :bind just-bind}
     Foldable
     {:fold just-fold
      :foldmap just-foldmap}
     Magma
     {:op just-op}
     Monoid
     {:id (constantly nil)}))

(extend-type nil
  Functor
  (fmap
    ([_ _] nil)
    ([_ _ _] nil))
  PseudoFunctor
  (fmap!
    ([_ _] nil)
    ([_ _ _] nil)
    ([_ _ _ _] nil)
    ([_ _ _ _ _] nil)
    ([_ _ _ _ _ _] nil))
  PseudoApplicative
  (fapply!
    ([_ _] nil)
    ([_ _ _] nil))
  Monad
  (bind
    ([_ _] nil)
    ([_ _ _] nil))
  (join [_] nil)
  PseudoMonad
  (bind!
    ([_ _] nil)
    ([_ _ _] nil))
  (join! [_] nil)
  Foldable
  (fold
    ([_] nil)
    ([_ _ _] nil)
    ([_ _ _ _] nil)
    ([_ _ _ _ _] nil)
    ([_ _ _ _ _ _] nil)
    ([_ _ _ _ _ _ _] nil))
  (foldmap
    ([_ _] nil)
    ([_ _ _ _] nil)
    ([_ _ _ _ _] nil)
    ([_ _ _ _ _ _] nil)
    ([_ _ _ _ _ _ _] nil)
    ([_ _ _ _ _ _ _ _] nil))
  Magma
  (op
    ([_] op)
    ([_ y] y)
    ([_ y z] (op y z))
    ([_ y z w] (op y z w))
    ([_ y z w ws] (op y z w (first ws) (rest ws))))
  Monoid
  (id [_] nil)
  Maybe
  (value [_] nil))
