;
; Copyright (c) 2019.  Heidelberg University
;
; This program is free software: you can redistribute it and/or modify
; it under the terms of the GNU General Public License as published by
; the Free Software Foundation, either version 3 of the License, or
; (at your option) any later version.
;
; This program is distributed in the hope that it will be useful,
; but WITHOUT ANY WARRANTY; without even the implied warranty of
; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
; GNU General Public License for more details.
;
; You should have received a copy of the GNU General Public License
; along with this program.  If not, see <https://www.gnu.org/licenses/>.
;

(ns phabricator-atlassian-migrator.phabricator
  (:require
    [cheshire.core :as json]
    [clj-http.client :as http]
    [clj-time.coerce :as coerce-time]
    [clojure.set :as set]
    [me.raynes.conch :refer [let-programs with-programs]]
    [phabricator-atlassian-migrator.util.coll :as coll-util]
    [phabricator-atlassian-migrator.util.db :as db-util]))

(def keys-to-rewrite
  {:app/phid :object/phid
   :space/phid :object/phid
   :user/phid :object/phid
   :project/phid :object/phid
   :project/spacePHID :project/space
   :project/memberPHIDs :project/members
   :project/watcherPHIDs :project/watchers
   :task/phid :object/phid
   :task/authorPHID :task/author
   :task/ownerPHID :task/owner
   :task/closerPHID :task/closer
   :task/spacePHID :task/space
   :task/subscriberPHIDs :task/subscribers
   :task/projectPHIDs :task/projects
   :transaction/phid :object/phid
   :transaction/authorPHID :transaction/author
   :transaction/objectPHID :transaction/object
   :comment/phid :object/phid
   :comment/authorPHID :comment/author
   :wiki-page/phid :object/phid
   :wiki-page/spacePHID :wiki-page/space
   :wiki-page/authorPHID :wiki-page/author})

(declare prepare-for-ingest)

(def coercer-table
  {:user/dateCreated         coerce-time/from-epoch
   :user/dateModified        coerce-time/from-epoch
   :project/spacePHID        db-util/string->phid-ref
   :project/parent           (partial db-util/map->ref :phid :object/phid)
   :project/dateCreated      coerce-time/from-epoch
   :project/dateModified     coerce-time/from-epoch
   :task/authorPHID          db-util/string->phid-ref
   :task/ownerPHID           db-util/string->phid-ref
   :task/closerPHID          db-util/string->phid-ref
   :task/spacePHID           db-util/string->phid-ref
   :task/dateCreated         coerce-time/from-epoch
   :task/dateModified        coerce-time/from-epoch
   :task/dateClosed          coerce-time/from-epoch
   :task/description         :raw
   :transaction/authorPHID   db-util/string->phid-ref
   :transaction/objectPHID   db-util/string->phid-ref
   :transaction/dateCreated  coerce-time/from-epoch
   :transaction/dateModified coerce-time/from-epoch
   :transaction/comments     (partial #'prepare-for-ingest :comment)
   :comment/content          :raw
   :comment/authorPHID       db-util/string->phid-ref
   :comment/dateCreated      coerce-time/from-epoch
   :comment/dateModified     coerce-time/from-epoch
   :wiki-page/authorPHID     db-util/string->phid-ref
   :wiki-page/spacePHID      db-util/string->phid-ref
   :wiki-page/content        :raw})

(def rewrite-keys
  #(set/rename-keys % keys-to-rewrite))

(comment
  (defn coerce-value
    [[k v]]
    (let [coercer (or (coercer-table k)
                      identity)]
      [k (coercer v)]))

  (defn coerce-values
    [m]
    (into {} (map coerce-value m))))

(defn coerce-values [m]
  (merge-with
    (fn [f v] (f v))
    (select-keys coercer-table (keys m))
    m))

(defn prepare-for-ingest
  "Add namespace `n` to all keys in the maps in the `raw-data` coll, also mangling refs"
  [ns raw-data]
  (when raw-data
    (let [add-ns (partial coll-util/add-ns-to-keys ns)]
      (map (fn [raw-datum]
             (-> raw-datum
                 add-ns
                 coerce-values
                 rewrite-keys))
           raw-data))))

(defmulti conduit-call
          (fn [phabricator _ _]
            (:connection-type phabricator))
          :default :local)

(defmethod conduit-call :local
  [_ method parameters]
  (let-programs [conduit "./bin/conduit"]
    (json/parse-string
      (conduit "--method" method "--input" "-"
               {:in (json/generate-string parameters)})
      keyword)))

(defmethod conduit-call :ssh
  [phabricator method parameters]
  (with-programs [ssh]
    (json/parse-string
      (ssh "-l"
           (get-in phabricator [:ssh :user])
           (get-in phabricator [:ssh :host])
           (str (get-in phabricator [:ssh :phabricator-path]) "/bin/conduit")
           "call"
           "--method"
           method
           "--input"
           "-"
           {:in (json/generate-string parameters)})
      keyword)))

(defmethod conduit-call :http
  [phabricator method parameters]
  (:body (http/post (str (get-in phabricator [:http :endpoint]) "/api/" method)
                    {:as :json
                     :multi-param-style :indexed
                     :flatten-nested-form-params true
                     :form-params (merge {:api.token (get-in phabricator [:http :api-token])}
                                         parameters)})))

(defn lookup
  [phabricator method parameters]
  (let [response (conduit-call phabricator method parameters)
        error-code (-> response :error_code)
        error-message (-> response :error_info)]
    (if error-code
      (throw (ex-info (str error-code ": " error-message)
                      {:code error-code
                       :message error-message}))
      (-> response :result))))

(comment
  (map (fn [object]
         (let [phid-attachments (apply merge
                                       (map
                                         (fn [attachment]
                                           (reduce-kv
                                             (fn [acc k v]
                                               (when (and (string/ends-with? k "PHIDs")
                                                          (not-empty v))
                                                 (assoc acc k (map
                                                                (fn [phid] [:object/phid phid])
                                                                v))))
                                             {}
                                             attachment))
                                         (vals (:attachments object))))]
           (->
             (merge object (:fields object) phid-attachments)
             (dissoc :fields :attachments)
             (->> (filter some-value-kv?)))))
       data))

(comment
  (map (fn [object]
         (let [phid-attachments (apply merge
                                       (map
                                         (fn [attachment]
                                           (->> (select-keys-by
                                                  attachment
                                                  #(string/ends-with? % "PHIDs"))
                                                (map (fn [[k v]]
                                                       [k (map (fn [phid]
                                                                 [:object/phid phid])
                                                               v)]))))
                                         (vals (:attachments object))))]
           (->
             (merge object (:fields object) phid-attachments)
             (dissoc :fields :attachments)
             (->> (filter (comp not-empty second))))))
       data))

(defn search
  "Returns a lazy-seq that fetches everything 'after' the given token using Conduit"
  ([phabricator method parameters]
   (search nil phabricator method parameters))
  ([after phabricator method parameters]
   (let [parameters-with-after (merge parameters (when after {:after after}))
         result (lookup phabricator method parameters-with-after)
         data (-> result :data)
         flat-data (map
                     (fn [object]
                       (let [phid-attachments
                             (into {}
                                   (map
                                     db-util/extract-phid-refs
                                     (:attachments object)))]
                         (->
                           object
                           (merge (:fields object)
                                  phid-attachments
                                  (when (= method "phriction.document.search")
                                    (get-in object [:attachments :content])))
                           (dissoc :fields :attachments)
                           (->> (filter coll-util/some-value-not-empty-kv?)))))
                     data)
         next (-> result :cursor :after)]
     (if next
       (lazy-seq (apply conj flat-data (search next phabricator method parameters)))
       flat-data))))

(defn call-search-method
  "Fetches all the data of a search"
  [phabricator method & args]
  (let [parameters (apply hash-map args)]
    (search phabricator method parameters)))

(defn call-lookup-method
  "Looks up something in Phabricator via Conduit"
  [phabricator method & args]
  (let [parameters (apply hash-map args)]
    (lookup phabricator method parameters)))

(defn raw-phid-lookup
  [phabricator names]
  (call-lookup-method phabricator "phid.lookup" :names names))

(defn phid-lookup
  [phabricator ns names]
  (when (not-empty names)
    (prepare-for-ingest ns (vals (raw-phid-lookup phabricator names)))))

(defn get-milestones
  "Fetches milestone projects from Phabricator"
  [phabricator]
  (call-search-method phabricator "project.search" :constraints {:isMilestone true}))

(defn get-raw-projects
  "Fetches projects from Phabricator"
  [phabricator]
  (call-search-method phabricator "project.search" :attachments {:members true :watchers true}))

(defn get-projects
  [phabricator]
  (prepare-for-ingest :project (get-raw-projects phabricator)))

(defn get-raw-users
  "Fetches users from Phabricator"
  [phabricator]
  (call-search-method phabricator "user.search"))

(defn get-users
  [phabricator]
  (prepare-for-ingest :user (get-raw-users phabricator)))

(defn get-raw-tasks
  [phabricator]
  (call-search-method phabricator "maniphest.search" :attachments {:subscribers true :projects true}))

(defn get-tasks
  [phabricator]
  (prepare-for-ingest :task (get-raw-tasks phabricator)))

(defn get-spaces
  ([phabricator]
   (get-spaces phabricator 1))
  ([phabricator n]
   (let [stride 100
         space-ids (for [m (range stride)]
                     (str "S" (+ n m)))
         spaces (phid-lookup phabricator :space space-ids)]
     (if (not-empty spaces)
       (lazy-seq (apply conj spaces (get-spaces phabricator (+ n stride))))
       spaces))))

(defn get-raw-transactions-by-object
  [phabricator object-phid]
  (call-search-method phabricator "transaction.search" :objectIdentifier object-phid))

(defn get-transactions-by-object
  [phabricator object-phid]
  (filter
    :transaction/type
    (prepare-for-ingest :transaction (get-raw-transactions-by-object phabricator object-phid))))

(defn get-raw-wiki-pages
  "Fetches wiki pages from Phabricator"
  [phabricator]
  (call-search-method phabricator "phriction.document.search" :attachments {:subscribers true :content true :projects true}))

(defn get-wiki-pages
  [phabricator]
  (prepare-for-ingest :wiki-page (get-raw-wiki-pages phabricator)))
