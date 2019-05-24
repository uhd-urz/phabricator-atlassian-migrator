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

(ns phabricator-atlassian-migrator.core
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [phabricator-atlassian-migrator.db :as db]
    [phabricator-atlassian-migrator.export :as export]
    [phabricator-atlassian-migrator.import :as import]
    [phabricator-atlassian-migrator.export-mappings :as mappings]))

(defn use-admin-permissions
  "Use JIRA admin credentials from config to authorise user"
  [config]
  (assoc config
    :jira
    (merge (get-in config [:jira])
           (get-in config [:jira :admin]))))

(defn -main
  [& args]
  (let [config (edn/read-string (slurp (io/file "config.edn")))
        conn (db/init-db)]
    (import/import-all! conn config)
    (db/store-db conn "phabricator.clean.edn")
    ;(db/load-db! conn "phabricator.clean.edn")

    (mappings/insert-all-mappings! conn config)
    (db/store-db conn "phabricator.with-jira-mappings.edn")
    ;(db/load-db! conn "phabricator.with-jira-mappings.edn")

    ;(export/create-projects conn config) ; Requires admin rights in JIRA!
    (import/sync-jira-projects! conn config)

    (export/create-components conn config) ; Requires edit rights on the repository or admin rights!
    (export/create-sprints conn config)
    (export/create-tasks conn config)
    (export/close-sprints conn config) ; Has to happen after adding tasks, because nothing can be added to closed sprints

    (export/create-spaces conn config)
    (export/create-pages conn config)))
