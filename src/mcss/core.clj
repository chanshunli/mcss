(ns mcss.core
  (:require [clojure.string :as str]
            [mcss.defaults :refer [default-vendors default-media]]
            [mcss.specs :as specs]
            [clojure.spec.alpha :as s]))

;;; Errors
;;; TODO add more errors

(defn- property-fn-arity-error [arity]
  (throw (ex-info "Property function only support 0 or 1 arity."
                  {:arity arity})))

(defn- invalid-property-value-error [v]
  (throw (ex-info (str (prn-str v) " is not allowed here, to use dynamic style provide a function as property value.")
                  {:v v})))

(defn- no-fallback-error [p]
  (throw (ex-info "No fallback value for property" {:property p})))

(defn- invalid-component-position-error [c]
  (throw (ex-info "Invalid component here." {:component c})))

(defn- invalid-symbol-error [s]
  (throw (ex-info "Invalid symbol here." {:symbol s})))



;;; Transform

(defn- ->css-combinator
  [s]
  s)

(defn- ->css-selector
  [sel]
  (name sel))

(defn- ->css-property
  [p opts]
  (let [{:keys [replaces*]} opts]
    (cond
      (symbol? p)
      (do (swap! replaces* conj p)
          "%s")

      (keyword? p)
      (name p)

      :else p)))

(defn- ->css-value [v opts]
  (let [{:keys [vars* replaces*]} opts]
    (cond
      ;; string -> common css value.
      ;; number -> common css value.
      (or (string? v) (number? v))
      v

      ;; Vector is a list separate by comma,
      ;; Vector of vector is a list separate by space.
      (vector? v)
      (->> v
           (map #(->css-value % (assoc opts :inside-vec? true)))
           (str/join (if (:inside-vec? opts) " " ",")))

      ;; Keyword will be convert to css variable.
      (keyword? v)
      (let [s (symbol (name v))
            cssvar (format "var(--%s)" s)
            pair [v s]]
        (swap! vars* conj pair)
        cssvar)

      ;; Refer to a custom valiable or keyframes
      (symbol? v)
      (do
        (swap! replaces* conj v)
        "%s")

      ;; Function or symbol(refer to function) used to extract data
      (and (seq? v) (#{'fn 'fn*} (first v)))
      (let [s (or (get @vars* v) (gensym "cssvar__"))
            cssvar (format "var(--%s)" s)
            pair [v s]]
        (swap! vars* conj pair)
        cssvar)

      ;; Map will rewrite to a css function call.
      (map? v)
      (let [e (first v)]
        (format "%s(%s)" (name (key e)) (->css-value (val e)
                                                     (assoc opts
                                                            :inside-vec?
                                                            false))))

      (and (list? v) (symbol? (first v)))
      (do
        (swap! replaces* conj (first v))
        "var(%s)")

      :else
      (invalid-property-value-error v))))

(defn- ->css-media [media]
  (->> (for [[k v] media]
         (case k
           :screen           (format "%s %s" (name v) (name k))
           :min-width        (format "(%s:%s)" (name k) (name v))
           :max-width        (format "(%s:%s)" (name k) (name v))
           :min-device-width (format "(%s:%s)" (name k) (name v))
           :max-device-width (format "(%s:%s)" (name k) (name v))))
       (str/join " and ")))

(defn- ->css-stmt [p v opts]
  (format "%s:%s;" (->css-property p opts) (->css-value v opts)))



;;; Compiler

(defn- build-style-input [selector style]
  (let [{:keys [vendors media pseudo combinators]}
        (meta style)]
    {:body        style
     :media       media
     :pseudo      pseudo
     :vendors     (merge default-vendors vendors)
     :combinators combinators
     :selector    selector}))

(defn- style-merge [s1 s2]
  (cond (map? s2) (merge-with style-merge s1 s2)
        s2 s2
        :else s1))

(defn- compile-source
  [{:keys [selector body media-key]} opts]
  (let [css-stmts (map (fn [[k v]] (->css-stmt k v opts)) body)
        css-sel (->css-selector selector)
        media (get default-media media-key)]
    (if media
      (format "@media %s{%s{%s}}" (->css-media media) css-sel (apply str css-stmts))
      (format "%s{%s}" css-sel (apply str css-stmts)))))

(defn- expand-media [base]
  (let [media (:media base)
        base (dissoc base :media)]
    (for [[media-key v] (cons nil media)
          :let
          [p (:pseudo (meta v))]]
      (-> base
          (assoc :media-key media-key)
          (update :body style-merge v)
          (update :pseudo style-merge p)))))

(defn- expand-combinators
  "Extract combinators from base style."
  [{:keys [selector combinators] :as style}]
  (cons
   (dissoc style :combinators)
   (for [[c sub-style] combinators]
     (build-style-input (str selector " " (->css-combinator c))
                        sub-style))))

(defn- expand-pseudo
  [{:keys [selector pseudo] :as style}]
  (cons
   (dissoc style :pseudo)
   (for [[p v] pseudo]
     (build-style-input (str selector ":" (name p))
                        v))))

(defn- expand-question-mark [{:keys [selector body] :as source} opts]
  (let [base
        (->> body
             (filter
              (fn [[k _]]
                (not (str/ends-with? (name k) "?"))))
             (into {}))

        base-source
        {:body base}

        ext-source-list
        (->> body
             (keep
              (fn [[k v]]
                (when (str/ends-with? (name k) "?")
                  (let [postfix (str "___" (str/replace (name k) #"\?" ""))]

                    (swap! (:toggles* opts) assoc k postfix)
                    {:body v
                     :selector
                     (str selector postfix)})))))]
    (into [(merge source base-source)]
          (map #(merge source %) ext-source-list))))

(defn- convert-vendors [{:keys [body vendors] :as source}]
  (let [new-body (->> body
                      (mapcat
                       (fn [[p v]]
                         (if-let [vendor (get vendors p)]
                           (into [[p v]]
                                 (map #(vector (str "-" (name %) "-" (name p)) v)
                                      vendor))
                           [[p v]])))
                      (into {}))]
    (assoc source :body new-body)))

(defn- compile-css
  "Compile Clojure style, return CSS string and a mapping of CSS variables."
  [cls style opts]
  (let [base  (build-style-input (str "." cls) style)
        css   (->> [base]
                   (mapcat expand-combinators)
                   (mapcat expand-media)
                   (mapcat expand-pseudo)
                   (mapcat #(expand-question-mark % opts))
                   (map convert-vendors)
                   (map #(compile-source % opts)))]
    (apply str css)))

(defn- compile-rule
  "Compile a single Clojure style, return CSS string."
  [sel style opts]
  (let [{:keys [vendors media pseudo combinators]}
        (meta style)

        base  {:body        style
               :media       media
               :pseudo      pseudo
               :vendors     (merge default-vendors vendors)
               :combinators combinators
               :selector    sel}
        css   (->> [base]
                   (mapcat expand-combinators)
                   (mapcat expand-media)
                   (mapcat expand-pseudo)
                   (map convert-vendors)
                   (map #(compile-source % opts)))]
    (apply str css)))

(defn- compile-keyframes
  "Compile Clojure keyframe style, return CSS string.
  CSS variable are not supported here."
  [kf keyframes opts]
  (let [{:keys [replaces*]} opts
        base-list (for [[k style] keyframes]
                    {:body style :vendors default-vendors :selector (name k)})
        css       (->> base-list
                       (map convert-vendors)
                       (map #(compile-source % opts))
                       vec)

        kf-vendors (get default-vendors :keyframes)]
    ;; TODO simplify code
    (swap! replaces*
           (fn [v]
             (vec (mapcat (constantly v)
                          (range (inc (count kf-vendors)))))))
    (apply str
           (format "@keyframes %s{%s}" kf (apply str css))
           (map #(format "@-%s-keyframes %s{%s}" (name %) kf (apply str css))
                kf-vendors))))



;;; Output Generator

(defn- gen-protect-fn-from-dce [fname]
  `(do (defn- ~fname []
         (println "run protect  function" ~(str fname))
         (set! mcss.rt/counter (inc mcss.rt/counter)))))

(defn- sym->fname [sym]
  (symbol (str "-" (name sym))))

(defn- gen-fname->css-cls [fname]
  `(str/replace (.-name ^js ~fname) #"\$" "-_"))

(defn- gen-dynamic-component [c tag css atomics opts]
  (let [fname (sym->fname c)
        replaces @(:replaces* opts)
        vars @(:vars* opts)
        toggles @(:toggles* opts)
        props-sym (gensym "props__")
        css-sym (gensym "css__")
        cls-sym (gensym "cls__")
        bind-vec  (->> vars
                       (mapcat (fn [[expr v]]
                                 (cond (keyword? expr)
                                       [v (list expr css-sym)]

                                       :else
                                       (let [arity-cnt (count (second expr))]
                                         (case arity-cnt
                                           0 [v (list expr)]
                                           1 [v (list expr css-sym)]
                                           (property-fn-arity-error arity-cnt))))))
                       (concat [css-sym (list :css props-sym)])
                       vec)
        style (->> vars
                   (map (fn [[_expr v]] [(str "--" v) v]))
                   (into {}))]
    `(do
       ~(gen-protect-fn-from-dce fname)
       (let [~cls-sym ~(gen-fname->css-cls fname)
             addon-cls# (->> (map #(str "." (%)) ~atomics)
                             (apply str))
             tag# (keyword (str ~(name tag) "." ~cls-sym addon-cls#))]
         (mcss.rt/reg-style ~cls-sym ~css ~replaces ~fname)
         (defn ~c
           [~props-sym & children#]
           (let ~bind-vec
             (let [class# (if ~(boolean (seq toggles))
                            ~(vec
                              (keep
                               (fn [[t postfix]]
                                 `(if (~t ~css-sym)
                                    (str ~cls-sym ~postfix)
                                    ""))
                               toggles))
                            `[])]
               (if (map? ~props-sym)
                 (into [tag# (merge (dissoc ~props-sym :css)
                                    {:style (merge ~style (:style ~props-sym))
                                     :class class#})]
                       children#)
                 (into [tag# {:class class#} ~props-sym] children#)))))))))

(defn- gen-static-component [c tag css atomics opts]
  (let [fname (sym->fname c)
        replaces @(:replaces* opts)]
    `(do
       ~(gen-protect-fn-from-dce fname)
       (let [cls# ~(gen-fname->css-cls fname)
             addon-cls# (->> (map #(str "." (%)) ~atomics)
                             (apply str))]
         (def ~c
           (keyword (str ~(name tag) "." cls# addon-cls#)))
         (mcss.rt/reg-style cls# ~css ~replaces ~fname)))))

(defn- gen-keyframes [sym css opts]
  (let [fname (sym->fname sym)
        replaces @(:replaces* opts)]
    `(do
       ~(gen-protect-fn-from-dce fname)
       (defn ~sym []
         (let [kf# ~(gen-fname->css-cls fname)]
           (mcss.rt/reg-style kf# ~css ~replaces ~fname)
           kf#)))))

(defn- gen-custom [sym css opts]
  (let [fname (sym->fname sym)
        replaces @(:replaces* opts)]
    `(do
       ~(gen-protect-fn-from-dce fname)
       (defn ~sym []
         (let [pname# ~(gen-fname->css-cls fname)]
           (mcss.rt/reg-custom pname# ~css ~replaces ~fname)
           (str "--" pname#))))))

(defn- gen-style [sym css opts]
  (let [fname (sym->fname sym)
        replaces @(:replaces* opts)]
    `(do
       ~(gen-protect-fn-from-dce fname)
       (defn ~sym []
         (let [cls# ~(gen-fname->css-cls fname)]
           (mcss.rt/reg-style cls# ~css ~replaces ~fname)
           cls#)))))



;;; Public APIs

(defmacro defstyled
  "Define a styled hiccup component. "
  {:arglists '[(component tag atomics style)]}
  [& args]
  (let [opts {:env       (or &env {})
              :vars*     (atom {})
              :replaces* (atom [])
              :toggles*  (atom {})}

        {:keys [c tag atomics style]}
        (s/conform ::specs/defstyled args)

        style (or style {})
        cls   "{{}}"
        css   (compile-css cls style opts)]
    (if (or (seq @(:toggles* opts)) (seq @(:vars* opts)))
      ;; Dynamic
      (gen-dynamic-component c tag css atomics opts)
      ;; Static
      (gen-static-component c tag css atomics opts))))

(defmacro defkeyframes
  "Define a keyframes."
  [sym & frames]
  (let [opts {:env       (or &env {})
              :vars*     (atom {})
              :replaces* (atom [])}
        kf "{{}}"
        css (compile-keyframes kf frames opts)]
    (gen-keyframes sym css opts)))

(defmacro defcustom
  "Define a custom variable."
  [sym value]
  (let [opts {:env       (or &env {})
              :vars*     (atom {})
              :replaces* (atom [])}
        name "{{}}"
        css-val (->css-value value opts)
        css (format "--%s:%s;" name css-val)]
    (gen-custom sym css opts)))

(defmacro defa
  "Define a simple class based style."
  [sym style]
  (let [opts {:env       (or &env {})
              :vars*     (atom {})
              :replaces* (atom [])}
        sel ".{{}}"
        css (compile-rule sel style opts)]
    (gen-style sym css opts)))
