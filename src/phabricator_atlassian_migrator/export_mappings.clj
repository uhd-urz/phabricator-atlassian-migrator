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

(ns phabricator-atlassian-migrator.export-mappings
  (:require
    [clojure.string :as string]
    [datascript.core :as d]
    [phabricator-atlassian-migrator.queries :as queries]
    [semantic-csv.core :as scsv]))

(defn load-user-mapping
  [db filename]
  (let [csv (scsv/slurp-csv filename
                            :cast-fns {:username string/trim
                                       :userid   string/trim})]
    (into {}
          (map
            (fn [entry]
              (let [account-id (:userid entry)
                    user-phid (->>
                                (:username entry)
                                (d/q queries/q-user-by-username @db)
                                (d/pull @db '[:object/phid])
                                :object/phid)]
                [user-phid account-id]))
            csv))))

(defn insert-user-account-id-mapping!
  [db config]
  (let [phab-phid->account-id (load-user-mapping db (get-in config [:mappings :users]))
        users (d/pull-many @db
                           '[:object/phid]
                           (d/q queries/q-users @db))
        user-phids (map :object/phid users)
        account-ids (map phab-phid->account-id user-phids)
        updated-users (map (fn [user-phid account-id]
                             {:db/id [:object/phid user-phid]
                              :user/account-id account-id})
                           user-phids
                           account-ids)]
    (d/transact! db updated-users))
  nil) ; Prevent printing large data to the REPL)

(defn load-project-mapping
  [db filename]
  (let [csv (scsv/slurp-csv filename
                            :cast-fns {:key  string/trim
                                       :name string/trim})]
    (into {}
          (map
            (fn [entry]
              (let [project-key (:key entry)
                    project-phid (->>
                                   (:name entry)
                                   (d/q queries/q-project-by-name @db)
                                   (d/pull @db '[:object/phid])
                                   :object/phid)]
                [project-phid project-key]))
            csv))))

(defn insert-top-level-project-mapping!
  [db config]
  (let [phab-phid->jira-key (load-project-mapping db (get-in config [:mappings :projects]))
        project-phids (->> (d/q queries/q-top-level-projects-with-members @db)
                           (d/pull-many @db '[:object/phid])
                           (map :object/phid))
        project-jira-keys (map phab-phid->jira-key project-phids)
        updated-projects (filter some?
                                 (map (fn [project-phid jira-key]
                                        (when (and project-phid jira-key)
                                          {:db/id [:object/phid project-phid]
                                           :project/jira-key jira-key}))
                                      project-phids
                                      project-jira-keys))]
    (d/transact! db updated-projects))
  nil); Prevent printing large data to the REPL))

(defn load-wiki-mapping
  [db filename]
  (let [csv (scsv/slurp-csv filename
                            :cast-fns {:path    string/trim
                                       :project string/trim})]
    (into {}
          (map
            (fn [entry]
              (let [page-phid (->>
                                (:path entry)
                                (d/q queries/q-wiki-page-by-path @db)
                                (d/pull @db '[:object/phid])
                                :object/phid)
                    page-project-phid (->>
                                        (:project entry)
                                        (d/q queries/q-project-by-name @db)
                                        (d/pull @db '[:object/phid])
                                        :object/phid)]
                [page-phid page-project-phid]))
            csv))))

(defn insert-top-level-wiki-mapping!
  [db config]
  (let [phab-page-phid->phab-project-phid (load-wiki-mapping db (get-in config [:mappings :wiki]))
        page-phids (->> (d/q queries/q-wiki-pages @db)
                        (d/pull-many @db '[:object/phid])
                        (map :object/phid))
        page-project-phids (map phab-page-phid->phab-project-phid page-phids)
        page-update (filter some?
                            (map (fn [page-phid page-project-phid]
                                   (when (and page-phid page-project-phid)
                                     {:db/id [:object/phid page-phid]
                                      :wiki-page/jira-project [:object/phid page-project-phid]}))
                                 page-phids
                                 page-project-phids))]
    (d/transact! db page-update))
  nil) ; Prevent printing large data to the REPL

(defn insert-all-mappings!
  [db config]
  (insert-user-account-id-mapping! db config)
  (insert-top-level-project-mapping! db config)
  (insert-top-level-wiki-mapping! db config)
  nil)
