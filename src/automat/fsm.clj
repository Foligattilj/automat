(ns automat.fsm
  (:refer-clojure :exclude
    [concat compile find])
  (:use
    [potemkin.types])
  (:require
    [primitive-math :as p]
    [clojure.set :as set]))

;;;

(let [cnt (atom 0)]
  (defn- new-generation []
    (swap! cnt inc)))

(defrecord State
  [generation
   descriptor
   sub-states
   handlers])

(defn- state [x]
  (if (instance? State x)
    x
    (->State nil x #{} #{})))

(defn- join-states
  ([a]
     a)
  ([a b]
     (->State nil nil [a b] (set/union (:handlers a) (:handlers b))))
  ([a b & rest]
     (apply join-states (join-states a b) rest)))

;;;

(definterface+ IAutomaton
  (deterministic? [_] "Returns true if the automata is a DFA, false otherwise.")
  (states [_] "The set of possible states within the automata.")
  (alphabet [_] "The set of possible inputs for the automata.")
  (start [_] "The start state for the automata.")
  (accept [_] "The set of accept states for the automata.")
  (transitions [_ state] "A map of inputs onto a state (if deterministic) or a set of states (if non-deterministic).")
  (gensym-states [_]))

(def ^:const epsilon "An input representing no input." ::epsilon)

(defn nfa
  "Creates an NFA."
  [start accept state->input->states]

  (assert
      (every? set? (->> state->input->states vals (mapcat vals)))
      "All target states within an NFA must be a set.")

  (let [map-states (fn [state->input->states f]
                     (zipmap
                       (map f (keys state->input->states))
                       (map
                         (fn [input->states]
                           (zipmap
                             (keys input->states)
                             (map
                               #(set (map f %))
                               (vals input->states))))
                         (vals state->input->states))))
        start (state start)
        accept (set (map state accept))
        state->input->states (map-states state->input->states state)
        states (set/union #{start} accept (set (keys state->input->states)))
        alphabet (apply set/union (->> state->input->states vals (map keys) (map set)))]

    (reify IAutomaton
      (deterministic? [_] false)
      (start [_] start)
      (accept [_] accept)
      (states [_] states)
      (alphabet [_] alphabet)
      (transitions [_ state] (get state->input->states state))
      (gensym-states [_]
        (let [gen (new-generation)
              f #(update-in % [:generation] conj gen)]
          (nfa (f start) (map f accept) (map-states state->input->states f)))))))

(defn dfa
  "Creates a DFA."
  [start accept state->input->state]
  (let [map-states (fn [state->input->state f]
                     (zipmap
                       (map f (keys state->input->state))
                       (map
                         (fn [input->state]
                           (zipmap
                             (keys input->state)
                             (map f (vals input->state))))
                         (vals state->input->state))))
        start (state start)
        accept (set (map state accept))
        state->input->state (map-states state->input->state state)
        states (set/union #{start} accept (set (keys state->input->state)))
        alphabet (apply set/union (->> state->input->state vals (map keys) (map set)))]

    (reify IAutomaton
      (deterministic? [_] true)
      (start [_] start)
      (accept [_] accept)
      (states [_] states)
      (alphabet [_] alphabet)
      (transitions [_ state] (get state->input->state state))
      (gensym-states [_]
        (let [gen (new-generation)
              f #(update-in % [:generation] conj gen)]
          (dfa (f start) (map f accept) (map-states state->input->state f)))))))

;;;

(defn- zipmap* [keys f]
  (zipmap keys (map f keys)))

(defn- intersects? [a b]
  (not (empty? (set/intersection a b))))

(defn- next-states
  "Gives all possible next states for given pair of state and input
   transitions."
  [nfa state input]
  (assert (not (deterministic? nfa)))
  (loop [traversed #{}
         pending (-> nfa (transitions state) (get input))]
    (if (empty? pending)
      traversed
      (let [state     (first pending)
            traversed (conj traversed state)
            pending   (set/union
                        (disj pending state)
                        (set/difference
                          (-> nfa (transitions state) (get epsilon))
                          traversed))]
        (recur traversed pending)))))

(defn ->nfa
  "Converts the given automaton into a non-deterministic finite automata. If it's already
   non-deterministic, this is a no-op."
  [fsm]
  (if-not (deterministic? fsm)
    fsm
    (nfa
      (start fsm)
      (accept fsm)
      (zipmap
        (states fsm)
        (map
          (fn [input->state]
            (zipmap
              (keys input->state)
              (map #(set [%]) (vals input->state))))
          (map #(transitions fsm %) (states fsm)))))))

(defn ->dfa
  "Converts the given automaton into a deterministic finite automata. If it's already
   deterministic, this is a no-op."
  [fsm]
  (if (deterministic? fsm)
    fsm
    (let [start-state (conj
                        (next-states fsm (start fsm) epsilon)
                        (start fsm))]
      (loop [explore #{start-state}
             state->input->state {}]
       (if (empty? explore)
          
         ;; we're done, wrap it up
         (let [accept (->> state->input->state
                        vals
                        (mapcat vals)
                        (clojure.core/concat (keys state->input->state))
                        (filter #(intersects? (accept fsm) %))
                        set)]
           (dfa
             (apply join-states start-state)
             (map #(apply join-states %) accept)
             (zipmap
               (map #(apply join-states %) (keys state->input->state))
               (map
                 (fn [input->state]
                   (zipmap
                     (keys input->state)
                     (map #(apply join-states %) (vals input->state))))
                 (vals state->input->state)))))
          
         (let [states (first explore)

               ;; all valid inputs for the compound state
               inputs
               (-> (mapcat #(keys (transitions fsm %)) states)
                 set
                 (disj epsilon))

               ;; a map of inputs onto the next compound state
               input->state
               (zipmap*
                 inputs
                 (fn [input]
                   (->> states
                     (map #(next-states fsm % input))
                     (apply set/union))))

               state->input->state
               (assoc state->input->state
                 states input->state)]
           (recur
             (set/union
               (disj explore states)
               (->> input->state
                 vals
                 (remove #(contains? state->input->state %))
                 set))
             state->input->state)))))))

;;;

;; assumes DFA
(defn- reachable-states
  "Returns states which can be reached from the start state."
  [fsm]
  (loop [reachable #{ (start fsm) }
         explore #{ (start fsm) }]
    (if (empty? explore)
      reachable
      (let [explore' (->> explore
                       (map #(->> % (transitions fsm) vals set))
                       (apply set/union))]
        (recur
          (set/union reachable explore')
          (set/difference explore' reachable))))))

;; assumes DFA
(defn- dead-states
  "Returns non-accept states which point only to themselves and other dead states."
  [fsm]
  (let [candidates (set/difference (states fsm) (accept fsm))]
    (loop [dead #{}]
      (if-let [dead' (seq
                       (filter
                         (fn [state]
                           (-> (transitions fsm state)
                             vals
                             set
                             (disj state)
                             (set/difference dead)
                             empty?))
                         (set/difference
                           candidates
                           dead)))]
        (recur (set/union (set dead') dead))
        dead))))

;; assumes DFA
;; http://en.wikipedia.org/wiki/DFA_minimization#Hopcroft.27s_algorithm
(defn- reduce-states [fsm]
  (let [accept (accept fsm)
        smallest #(if (< (count %1) (count %2)) %1 %2)]
    (loop [remaining-partitions #{accept}
           partitions #{accept (set/difference (states fsm) accept)}
           remaining-states nil]
      (if (empty? remaining-states)

        ;; pop partition off, recur with list of candidate states
        (if (empty? remaining-partitions)
          (->> partitions
            (map #(zipmap % (repeat (first %))))
            (apply merge))
          (let [s (first remaining-partitions)]
            (recur
              (disj remaining-partitions s)
              partitions
              (map
                (fn [input]
                  (set
                    (filter
                      #(contains? s (-> fsm (transitions %) (get input)))
                      (states fsm))))
                (alphabet fsm)))))

        ;; repartition
        (let [a (first remaining-states)
              remaining-partitions (atom remaining-partitions)
              partitions (->> partitions
                           (mapcat
                             (fn [b]
                               (let [i (set/intersection a b)]
                                 (if (empty? i)
                                   [b]
                                   (let [d (set/difference b a)]
                                     (if (contains? @remaining-partitions b)
                                       (swap! remaining-partitions
                                         #(-> %
                                            (disj b)
                                            (conj i d)))
                                       (swap! remaining-partitions conj (smallest i d)))
                                     [i d])))))
                           set)]
          (recur
            @remaining-partitions
            partitions
            (rest remaining-states)))))))

(defn- prune [fsm]
  (let [fsm (->dfa fsm)
        reachable? (set/difference
                     (reachable-states fsm)
                     (dead-states fsm))]
    (dfa
      (start fsm)
      (filter reachable? (accept fsm))
      (zipmap*
        (filter reachable? (states fsm))
        (fn [state]
          (->> (transitions fsm state)
            (filter #(reachable? (val %)))
            (into {})))))))

(defn minimize
  "Returns a minimized DFA."
  [fsm]
  (let [fsm (prune (->dfa fsm))
        state->new-state (reduce-states fsm)
        states' (filter #(= % (state->new-state %)) (states fsm))]
    (dfa
      (state->new-state
        (start fsm))
      (->> (accept fsm)
        (map state->new-state)
        set)
      (zipmap
        states'
        (map
          (fn [input->state]
            (zipmap
              (keys input->state)
              (map state->new-state (vals input->state))))
          (map #(transitions fsm %) states'))))))

;;;

(defn automaton
  "A basic automaton that will accept any of the given inputs."
  [& inputs]
  (dfa 0 #{1} {0 (zipmap inputs (repeat 1))}))

(defn concat
  "Concatenate one or more automatons together."
  ([a]
     a)
  ([a b]
     ;; remove epsilons before adding more
     (let [a (-> a ->dfa gensym-states ->nfa)
           b (-> b ->dfa gensym-states ->nfa)
           state->input->states (merge
                                  (zipmap* (states a) #(transitions a %))
                                  (zipmap* (states b) #(transitions b %)))]
       (nfa
         (start a)
         (accept b)
         (reduce
           #(assoc-in %1 [%2 epsilon] #{(start b)})
           state->input->states
           (accept a)))))
  ([a b & rest]
     (apply concat (concat a b) rest)))

(defn kleene
  "Accepts zero or more of the given automaton."
  [fsm]
  ;; remove epsilons before adding more
  (let [fsm (-> fsm ->dfa ->nfa)]
    (nfa
      (start fsm)
      (conj (accept fsm) (start fsm))
      (reduce
        #(assoc-in %1 [%2 epsilon] #{(start fsm)})
        (zipmap* (states fsm) #(transitions fsm %))
        (accept fsm)))))

(defn maybe
  "Accepts one or zero of the given automaton."
  [fsm]
  ((if (deterministic? fsm) dfa nfa)
   (start fsm)
   (conj (accept fsm) (start fsm))
   (zipmap* (states fsm) #(transitions fsm %))))

(defn- merge-fsms [a b accept-states]
  (let [a (gensym-states (minimize a))
        b (gensym-states (minimize b))
        cartesian-states (for [s-a (states a), s-b (states b)]
                           (join-states s-a s-b))
        inputs (set/union
                 (alphabet a)
                 (alphabet b))]
    (dfa
      (join-states (start a) (start b))
      (accept-states a b)
      (merge
        (zipmap* (states a) #(transitions a %))
        (zipmap* (states b) #(transitions b %))
        (zipmap*
          cartesian-states
          (fn [{:keys [sub-states]}]
            (let [[s-a s-b] sub-states]
              (merge
                (transitions a s-a)
                (transitions b s-b)
                (zipmap*
                  (filter
                    #(and
                       (contains? (transitions a s-a) %)
                       (contains? (transitions b s-b) %))
                    inputs)
                  (fn [input]
                    (join-states
                      (get (transitions a s-a) input)
                      (get (transitions b s-b) input))))))))))))

(defn intersection
  "Returns the intersection of multiple automata."
  ([a]
     a)
  ([a b]
     (merge-fsms a b
       (fn [a b]
         (for [s-a (accept a), s-b (accept b)]
           (join-states s-a s-b)))))
  ([a b & rest]
     (apply intersection (intersection a b) rest)))

(defn union
  "Returns the union of multiple automata."
  ([a]
     a)
  ([a b]
     (merge-fsms a b
       (fn [a b]
         (set/union
           (accept a)
           (accept b)
           (set
             (for [s-a (accept a), s-b (states b)]
               (join-states s-a s-b)))
           (set
             (for [s-a (states a), s-b (accept b)]
               (join-states s-a s-b)))))))
  ([a b & rest]
     (apply union (union a b) rest)))

(defn difference
  "Returns the difference of multiple automata."
  ([a]
     a)
  ([a b]
     (merge-fsms a b
       (fn [a b]
         (set/union
           (accept a)
           (set
             (for [s-a (accept a), s-b (set/difference (states b) (accept b))]
               (join-states s-a s-b)))))))
  ([a b & rest]
     (apply difference (difference a b) rest)))

