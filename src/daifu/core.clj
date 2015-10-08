(ns daifu.core
  (:require [clojure.tools.cli :as cli]
            [clojure.java.io :as io]
            [gita.core :as git]
            [cheshire.core :as json]
            [cheshire.generate :as generate]
            [clojure.pprint :as pprint]
            [daifu.diagnosis :as diagnosis]
            [daifu.diagnosis.indicator :as indicator]
            [daifu.diagnosis.jurisdiction :as jurisdiction]))

(defn accumulate
  ([m k v]
   (accumulate m k v []))
  ([m k v initial]
   (update-in m [k] (fnil #(conj % v) initial))))

(def cli-options
  [["-c" "--checkup PATH" "Path to the checkups file that will be run"]
   ["-f" "--filter PATH"  "Path of files that filter"
    :assoc-fn #(accumulate %1 %2 %3 #{})]
   ["-i" "--indicator-path DIR" "Directory to load indicators"
    :assoc-fn accumulate]
   ["-j" "--jurisdiction-path DIR" "Directory to load indicators"
    :assoc-fn accumulate]
   ["-o" "--output FILE" "Path for output file"]
   
   [nil "--format FORMAT" "Format of output {edn|json}"
    :default :edn
    :parse-fn keyword
    :validate [#(#{:edn :json} %) "Must be a either edn or json"]]
   [nil "--no-defaults" "Do not load default indicators"]
   [nil "--root PATH" "Path to the repository "
    :default (System/getProperty "user.dir")]
   [nil "--use-git" "Load files from git"]])

(def ^:dynamic *default-indicators*
  ["file/line_count.indi"
   "form/record_count.indi"
   "function/no_docstring.indi"
   "function/token_count.indi"
   "idiom/arithmatic.indi"
   "idiom/collection.indi"
   "idiom/control.indi"
   "idiom/equality.indi"
   "idiom/sequence.indi"
   "idiom/string.indi"
   "project/project_meta.indi"])

(defn load-default-indicators []
  (->> *default-indicators*
       (map (fn [path]
              (-> (io/resource (str "daifu/defaults/indicators/" path))
                  slurp
                  read-string
                  indicator/indicator)))
       (map (juxt :id identity))
       (into {})))

(def default-jurisdictions
  {:default (jurisdiction/jurisdiction {:id :default
                                        :type :project
                                        ;;:comparison true
                                        ;;:current {:commit "HEAD"}
                                        ;;:previous {:commit "HEAD^1"}
                                        })})

(defrecord Visitation [])

(defmethod print-method Visitation
  [v w]
  (.write w (str "#visitation "
                 (into {} (-> v
                              (update-in [:indicators] (comp vec sort keys))
                              (update-in [:jurisdictions] (comp vec sort keys))
                              (dissoc :indicator-path :jurisdiction-path))))))

(defn load-maps [dir suffix constructor]
  (->> (file-seq (io/file dir))
       (filter (fn [f] (.endsWith (str f) suffix)))
       (map (fn [f] (-> (slurp f)
                        (read-string)
                        constructor)))
       (map (juxt :id identity))
       (into {})))

(defn load-indicators [dir]
  (load-maps dir ".indi" indicator/indicator))

(defn load-jurisdictions [dir]
  (load-maps dir ".juri" jurisdiction/jurisdiction))

(defn load-checkups [path]
  (if path
    (-> (slurp path) read-string)))

(defn git-repo? [path]
  (.exists (io/file path ".git")))

(defn visitation [opts]
  (let [opts (if-not (:no-defaults opts)
               (update-in opts [:indicators] merge (load-default-indicators))
               opts)
        opts (update-in opts [:jurisdictions] merge default-jurisdictions)
        opts (update-in opts [:indicators]
                        merge (apply merge (map (fn [path]
                                                  (load-indicators (str (:root opts) "/" path)))
                                                (:indicator-path opts))))
        opts (update-in opts [:jurisdictions]
                        merge (apply merge (map (fn [path]
                                                  (load-jurisdictions (str (:root opts) "/" path)))
                                                (:jurisdiction-path opts))))
        opts (update-in opts [:checkups]
                        #(->> (load-checkups (:checkup-path opts))
                              (concat %)
                              vec))
        opts (if (empty? (:checkups opts))
               (assoc opts :checkups (vec (map vector (sort (keys (:indicators opts))))))
               opts)
        opts (assoc-in opts [:repository]
                       (if (and (git-repo? (:root opts)) (:use-git opts))
                         (git/repository (:root opts))
                         (io/file (:root opts))))]
    (map->Visitation opts)))

(defn diagnosis-single [visitation [ik jk]]
  (let [jk   (or jk :default)
        indi (-> visitation
                 :indicators
                 (get ik))
        juri (-> visitation
                 :jurisdictions
                 (get jk)
                 (merge (select-keys visitation [:filter])))]
    (if (and indi juri)
      (try
        (println "Diagnostic for" indi)
        (diagnosis/diagnose (:repository visitation) indi juri)
        (catch Throwable t
          (println "Failure for indicator" indi ", jurisdiction" juri))))))

(defn filter-zero-stats [{:keys [results stat] :as data}]
    (cond (and (number? stat) (zero? stat)) nil

          (and (number? stat)
               (vector? results))
          (update-in data [:results]
                     #(vec (keep filter-zero-stats %)))

          :else data))

(defn diagnosis [visitation checkups]
  (let [path (str (:root visitation) "/" (:output visitation))
        results (->> checkups
                     (keep (partial diagnosis-single visitation))
                     (mapv filter-zero-stats))
        writer  (if (:output visitation)
                  (io/writer path)
                  *out*)]
    
    (case (:format visitation)
      :json (let [output (json/generate-string results {:pretty true})]
              (if (= writer *out*)
                (.write writer output)
                (spit path (str output))))
      :edn  (pprint/pprint results writer))))

(defn -main [& args]
  (let [summary (cli/parse-opts args cli-options)
        summary (if (:errors summary)
                  (do (println "Errors on input:")
                      (doseq [error (:errors summary)]
                        (println error))
                      (assoc-in summary [:options :help] true))
                  summary)]
    (cond (-> summary :options :help)
          (println (:summary summary))

          :else
          (let [opts    (:options summary)
                visit (visitation (dissoc opts :diagnosis))]
            (diagnosis visit (:checkups visit))))))


(comment
  
  (-> (cli/parse-opts ["--root" "/Users/chris/Development/helpshift/moby" "--no-defaults"
                       "-i" "qa/indicators" "-o" "output.edn" ]
                      cli-options)
      :options)
  
  (-main "--help")

  (-main "--root" "/Users/chris/Development/helpshift/moby" "--no-defaults"
         "-i" "qa/indicators")
  

  (-main "--root" "/Users/chris/Development/helpshift/moby" "--no-defaults"
         "-i" "qa/indicators"
         "-f" "src/qa/automation.clj"
         "-f" "src/moby/core/models/view_folder.clj"
         ;;"--format" "json"
         "-o" "output.edn")
  
  (def output (read-string (slurp "output.edn")))

  

  (filter-zero-stats (first output))
  
  
  (filter-zero-stats {:stat 0})
  => nil
  
  (filter-zero-stats {:stat 1
                      :results [{:path "src/api/lib/handlers/faq.clj", :stat 0, :results []}
                                {:path "src/api/lib/handlers/issue.clj",
                                 :stat 1
                                 :results
                                 [{:expr '(if hs-tags hs-tags [])
                                   :alt  '(or hs-tags [])
                                   :row  155
                                   :col  7}]}]})
  {:stat 1, :results [{:path "src/api/lib/handlers/issue.clj",
                       :stat 1,
                       :results [{:expr '(if hs-tags hs-tags []),
                                  :alt  '(or hs-tags []),
                                  :row  155,
                                  :col  7}]}]}
  
  => {:stat 1
      :results [{:path "src/api/lib/handlers/issue.clj",
                 :stat 1
                 :results
                 [{:expr '(if hs-tags hs-tags [])
                   :alt  '(or hs-tags [])
                   :row  155
                   :col  7}]}]}
  
  
  (if (zero? (:stat (first output))))
  
  
  
  )
