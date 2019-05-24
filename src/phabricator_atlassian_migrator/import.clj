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

(ns phabricator-atlassian-migrator.import
  (:require
    [clj-http.client :as http]
    [clojure.string :as string]
    [datascript.core :as d]
    [phabricator-atlassian-migrator.jira :as jira]
    [phabricator-atlassian-migrator.phabricator :as phabricator]
    [phabricator-atlassian-migrator.queries :as queries]
    [phabricator-atlassian-migrator.util.coll :as coll-util]
    [phabricator-atlassian-migrator.util.db :as db-util]
    [phabricator-atlassian-migrator.util.milestone :as milestone-util]
    [taoensso.timbre :as log]))

(defn import-spaces!
  "Fetch spaces from Phabricator and store them in `db`"
  [db config]
  (log/debug "Loading spaces ...")
  (http/with-connection-pool
    {:timeout 5 :threads 4}
    (let [spaces (phabricator/get-spaces (:phabricator config))]
      (d/transact! db spaces)
      spaces)))

(defn import-users!
  "Fetch users from Phabricator and store them in `db`"
  [db config]
  (log/debug "Loading users ...")
  (http/with-connection-pool
    {:timeout 5 :threads 4}
    (let [users (phabricator/get-users (:phabricator config))]
      (d/transact! db users)
      users)))

(defn import-projects!
  "Fetch projects and store them in `db`"
  [db config]
  (log/debug "Loading projects ...")
  (http/with-connection-pool
    {:timeout 5 :threads 4}
    (let [projects (sort-by :project/depth (phabricator/get-projects (:phabricator config))) ; Sort by hierarchy so DataScript can resolve refs during transaction
          colors (-> projects
                     (->>
                       (map :project/color)
                       (map (fn [color] (update color :name #(or % "none")))))
                     set
                     seq
                     (->> (map #(coll-util/add-ns-to-keys :color %))))
          projects-with-color-refs (map
                                     #(update % :project/color (partial db-util/map->ref :key :color/key))
                                     projects)]
      (d/transact! db colors)
      (d/transact! db projects-with-color-refs)
      projects-with-color-refs)))

(defn import-tasks!
  "Fetch tasks and store them in `db`"
  [db config]
  (log/debug "Loading tasks ...")
  (http/with-connection-pool
    {:timeout 5 :threads 4}
    (let [tasks (phabricator/get-tasks (:phabricator config))
          stati (-> tasks
                    (->>
                      (map :task/status)
                      (map (fn [status] (update status :color #(or % "none")))))
                    set
                    seq
                    (->> (map #(coll-util/add-ns-to-keys :task-status %))))
          priorities (-> tasks
                         (->>
                           (map :task/priority)
                           (map (fn [priority] (update priority :color #(or % "none")))))
                         set
                         seq
                         (->> (map #(coll-util/add-ns-to-keys :task-priority %))))
          tasks-with-status-and-priority-refs (map
                                                #(-> %
                                                     (update :task/status (partial db-util/map->ref :name :task-status/name))
                                                     (update :task/priority (partial db-util/map->ref :name :task-priority/name)))
                                                tasks)]
      (d/transact! db stati)
      (d/transact! db priorities)
      (d/transact! db tasks-with-status-and-priority-refs)
      tasks-with-status-and-priority-refs)))

(defn import-transactions!
  "Fetch transactions on `tasks` and store them in `db`.
  Also fetches and stores all applications referenced by transactions."
  [db config]
  (log/debug "Loading transactions ...")
  (http/with-connection-pool
    {:timeout 5 :threads 4}
    (let [task-phids (d/q queries/q-task-phids @db)
          transactions (mapcat #(phabricator/get-transactions-by-object (:phabricator config) %)
                               task-phids)
          ; Ugly, but necessary, because applications cannot be listed through Phabricator's Conduit:
          app-phids (filter #(string/starts-with? % "PHID-APPS-")
                            (db-util/unique-refs :transaction/author transactions))
          apps (phabricator/phid-lookup (:phabricator config) :app app-phids)]
      (d/transact! db apps)
      (d/transact! db transactions)
      transactions)))

(defn get-parent-path
  "Get parent path of a page, similar to dirname, but also fix holes in the path due to corrupted data in Phabricator"
  [db path]
  (when (not= path "/") ; Root node has no parent
    (let [parts (string/split path #"/")
          parent-parts (take (- (count parts) 1) parts)
          parent-path (str (string/join "/" parent-parts) "/")]
      (if (nil? (d/q queries/q-wiki-page-by-path @db parent-path))
        (get-parent-path db parent-path) ; Fixup holes in the tree by reparenting to parent of parent
        parent-path))))

(defn link-parent-wiki-pages!
  "From the given wiki pages, find their parents and create relations between them"
  [db wiki-pages]
  (let [wiki-page-phids (map :object/phid wiki-pages)
        parent-paths (map (comp (partial get-parent-path db) :wiki-page/path) wiki-pages)
        parents (map #(some->> %
                               (d/q queries/q-wiki-page-by-path @db)
                               (d/pull @db '[:object/phid])
                               :object/phid)
                     parent-paths)
        wiki-page-updates (filter some?
                                  (map (fn [wiki-page-phid parent-phid]
                                         (when (and wiki-page-phid parent-phid)
                                           {:db/id [:object/phid wiki-page-phid]
                                            :wiki-page/parent [:object/phid parent-phid]}))
                                       wiki-page-phids
                                       parents))]
    (d/transact! db wiki-page-updates)))

(defn import-wiki-pages!
  "Fetch wiki pages and store them in `db`"
  [db config]
  (log/debug "Loading wiki pages ...")
  (http/with-connection-pool
    {:timeout 5 :threads 4}
    (let [wiki-pages (phabricator/get-wiki-pages (:phabricator config))
          stati (-> wiki-pages
                    (->>
                      (map :wiki-page/status)
                      (map (fn [status] (update status :name #(or % "none")))))
                    set
                    seq
                    (->> (map #(coll-util/add-ns-to-keys :wiki-status %))))
          wiki-pages-with-status-refs (map
                                        #(update % :wiki-page/status (partial db-util/map->ref :name :wiki-status/name))
                                        wiki-pages)]
      (d/transact! db stati)
      (d/transact! db wiki-pages-with-status-refs)
      (link-parent-wiki-pages! db wiki-pages-with-status-refs)
      wiki-pages-with-status-refs)))

(defn unimport-wiki-pages!
  "Purge `db` of all information related to wiki pages"
  [db]
  (d/transact! db (map (fn [db-id] [:db.fn/retractEntity db-id])
                       (d/q '[:find [?e ...] :where [?e :wiki-page/path]] @db)))
  (d/transact! db (map (fn [db-id] [:db.fn/retractEntity db-id])
                       (d/q '[:find [?e ...] :where [?e :wiki-status/name]] @db)))
  nil)

(defn import-all!
  "Fetch all (relevant) content from Phabricator and store it in `db`"
  [db config]
  (import-spaces! db config)
  (import-users! db config)
  (import-projects! db config)
  (import-tasks! db config)
  (import-transactions! db config)
  (import-wiki-pages! db config)
  nil)

(defn sync-jira-projects!
  "Identify already existing projects to migrate on JIRA and update our information (ids) about them"
  [db config]
  (http/with-connection-pool
    {:timeout 5 :threads 4}
    (let [projects (->> (d/q queries/q-top-level-projects-for-migration @db)
                        (d/pull-many @db '[:db/id :project/jira-key]))]
      (doseq [project projects]
        (let [project-id (:db/id project)
              subproject-blacklist-names (:subproject-component-blacklist config)
              subproject-blacklist-db-ids (d/q queries/q-projects-by-names @db subproject-blacklist-names)
              subprojects (->> [project-id]
                               (d/q queries/q-subprojects-by-projects @db queries/r-subproject)
                               (coll-util/seq-difference-swapped subproject-blacklist-db-ids)
                               (d/pull-many @db '[:db/id :project/name]))
              jira-board-id (:project/jira-board-id project)
              milestones (->> [project-id]
                              (d/q queries/q-milestones-by-projects @db queries/r-subproject)
                              (d/pull-many @db '[:db/id :project/name :project/description {:project/color [:color/key]}])
                              (group-by :project/name))
              ; We sometimes have Phabricator milestones with duplicate names (from subprojects) or similar names (accidential duplications) that we merge into one JIRA sprint
              milestone-unified-names (->> milestones
                                           keys
                                           set
                                           seq
                                           (group-by milestone-util/unify-milestone-name))
              jira-project-key (:project/jira-key project)
              jira-project (jira/get-jira-software config (str "project/" jira-project-key))
              jira-project-id (:id jira-project)
              jira-board-id (->> (jira/jira-search-agile config "board" {:projectKeyOrId jira-project-key})
                                 (map :id)
                                 sort
                                 first)
              jira-components (try
                                (jira/get-jira-software
                                  config
                                  (str "project/" jira-project-key "/components"))
                                (catch Exception e
                                  (when (nil? (#{404} (:status (ex-data e))))
                                    (throw e))))
              jira-sprints (try
                             (jira/get-jira-agile
                               config
                               (str "board/" jira-board-id "/sprint"))
                             (catch Exception e
                               (when (nil? (#{404} (:status (ex-data e))))
                                 (throw e))))]
          (d/transact! db [{:db/id                   project-id
                            :project/jira-project-id jira-project-id
                            :project/jira-board-id   jira-board-id}])
          (doseq [component jira-components]
            (let [component-name (:name component)
                  component-id (:id component)
                  matching-subprojects (filter #(= component-name (:project/name %)) subprojects)]
              (doseq [subproject matching-subprojects]
                (let [subproject-id (:db/id subproject)]
                  (d/transact! db [{:db/id                     subproject-id
                                    :project/jira-component-id component-id}])))))
          (doseq [sprint jira-sprints]
            (let [jira-sprint-id (:id sprint)
                  milestone-unified-name (:name sprint)
                  matching-milestone-names (get milestone-unified-names milestone-unified-name)
                  matching-milestones (mapcat #(get milestones %) matching-milestone-names)]
              (doseq [milestone matching-milestones]
                (let [milestone-id (:db/id milestone)]
                  (d/transact! db [{:db/id                  milestone-id
                                    :project/jira-sprint-id jira-sprint-id}]))))))))))
