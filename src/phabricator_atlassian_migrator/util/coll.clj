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

(ns phabricator-atlassian-migrator.util.coll
  (:require
    [clojure.set :as set]))

(defn force-namespace
  [ns k]
  (keyword (name ns) (name k)))

(defn add-ns-to-keys
  [ns m]
  (zipmap (map (partial force-namespace ns) (keys m))
          (vals m)))

(def some-value-kv?
  (comp some? second))

(defn coll-not-empty
  [x]
  (cond-> x (coll? x) not-empty))

(def some-value-not-empty-kv?
  (comp some? coll-not-empty second))

(defn deep-merge
  "Recursively merges maps."
  [& maps]
  (letfn [(m [& xs]
            (if (some #(and (map? %) (not (record? %))) xs)
              (apply merge-with m xs)
              (last xs)))]
    (reduce m maps)))

(defn select-keys-by
  "Filter keys k for which (`pred` k) returns true"
  [m pred]
  (select-keys m (filter pred (keys m))))

(defn seq-difference-swapped
  [seq-to-be-removed larger-seq]
  (seq (set/difference (set larger-seq) (set seq-to-be-removed))))
