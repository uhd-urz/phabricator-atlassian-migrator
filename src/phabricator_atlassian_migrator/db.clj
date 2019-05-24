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

(ns phabricator-atlassian-migrator.db
  (:require
    [clj-time.coerce :as coerce-time]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.pprint :as pp]
    [datascript.core :as d]))

(def datascript-schema
  {:object/phid          {:db/unique      :db.unique/value}
   :project/color        {:db/valueType   :db.type/ref}
   :project/parent       {:db/valueType   :db.type/ref}
   :project/space        {:db/valueType   :db.type/ref}
   :project/members      {:db/valueType   :db.type/ref
                          :db/cardinality :db.cardinality/many}
   :project/watchers     {:db/valueType   :db.type/ref
                          :db/cardinality :db.cardinality/many}
   :color/key            {:db/unique      :db.unique/value}
   :task-priority/name   {:db/unique      :db.unique/value}
   :task-status/name     {:db/unique      :db.unique/value}
   :task/space           {:db/valueType   :db.type/ref}
   :task/author          {:db/valueType   :db.type/ref}
   :task/owner           {:db/valueType   :db.type/ref}
   :task/closer          {:db/valueType   :db.type/ref}
   :task/subscribers     {:db/valueType   :db.type/ref
                          :db/cardinality :db.cardinality/many}
   :task/projects        {:db/valueType   :db.type/ref
                          :db/cardinality :db.cardinality/many}
   :task/priority        {:db/valueType   :db.type/ref}
   :task/status          {:db/valueType   :db.type/ref}
   :transaction/author   {:db/valueType   :db.type/ref}
   :transaction/object   {:db/valueType   :db.type/ref}
   :transaction/comments {:db/valueType   :db.type/ref
                          :db/cardinality :db.cardinality/many}
   :comment/author       {:db/valueType   :db.type/ref}
   :wiki-page/space      {:db/valueType   :db.type/ref}
   :wiki-page/status     {:db/valueType   :db.type/ref}
   :wiki-page/subscribers {:db/valueType  :db.type/ref
                           :db/cardinality :db.cardinality/many}
   :wiki-page/parent     {:db/valueType   :db.type/ref}
   :wiki-page/jira-project {:db/valueType :db.type/ref}
   :wiki-page/projects   {:db/valueType   :db.type/ref
                          :db/cardinality :db.cardinality/many}
   :wiki-status/name     {:db/unique      :db.unique/value}})

(defn init-db
  []
  (d/create-conn datascript-schema))

(defn load-db!
  ([db]
   (load-db! db "phabricator.edn"))
  ([db filename]
   (reset! db (edn/read-string
                {:readers (merge d/data-readers coerce-time/data-readers)}
                (slurp (io/file filename))))
   nil)) ; Prevent large output to REPL

(defn store-db
  ([db]
   (store-db db "phabricator.edn"))
  ([db filename]
   (spit (io/file filename) (pr-str @db))))

(defn store-db-pretty
  ([db]
   (store-db-pretty db "phabricator.pretty.edn"))
  ([db filename]
   (pp/pprint @db (io/writer filename))))
