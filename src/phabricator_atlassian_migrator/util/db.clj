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

(ns phabricator-atlassian-migrator.util.db
  (:require
    [clojure.string :as string]))

(defn map->ref
  [select-keyword target-keyword value]
  [target-keyword (value select-keyword)])

(defn string->phid-ref
  [value]
  [:object/phid value])

(defn unique-refs
  [attribute datoms]
  (set (filter some? (map #(-> % attribute second) datoms))))

(defn extract-phid-refs
  [[attachment-name attachment]]
  [attachment-name
   (->>
     attachment
     (mapcat
       (fn [[attachment-field-name attachment-field]]
         (cond
           (= attachment-field-name attachment-name)
           (map :phid attachment-field) ; could use map->phid-ref
           (string/ends-with? attachment-field-name "PHIDs")
           attachment-field))) ; could use string->phid-ref
     (map (fn [phid] [:object/phid phid])))])

(defn drop-phid
  [k]
  (keyword (namespace k)
           (string/replace (name k) "PHID" "")))
