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

(ns phabricator-atlassian-migrator.util.string
  (:require
    [clojure.string :as string]))

(defn friendly-subs
  [s start end]
  (subs s start (min end (count s))))

(defn basename
  [path]
  (->> (string/split path #"/")
       (take-last 1)))

(defn path-underneath-any
  [top-level-paths path]
  (some
    identity
    (for [top-level-path top-level-paths]
      (string/starts-with? path top-level-path))))
