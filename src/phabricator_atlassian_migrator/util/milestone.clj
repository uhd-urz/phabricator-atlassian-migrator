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

(ns phabricator-atlassian-migrator.util.milestone
  (:require
    [clj-time.core :as time]
    [clojure.string :as string]
    [phabricator-atlassian-migrator.util.time :as time-util]))

(defn milestone-type
  [milestone-name]
  (cond
    (or
      (string/starts-with? milestone-name "Weeks")
      (string/starts-with? milestone-name "Wochen"))
    :weeks

    (or
      (string/starts-with? milestone-name "Week")
      (string/starts-with? milestone-name "Woche"))
    :week

    (or
      (string/starts-with? milestone-name "Month")
      (string/starts-with? milestone-name "Monat"))
    :month

    :default nil))

(defn milestone-data
  [milestone-name milestone-type]
  (case milestone-type
    :weeks
    (->> milestone-name
         (re-matches #"(Weeks|Wochen) ([0-9]+)/([0-9]+)-([0-9]+).*")
         (take-last 3)
         (map #(Integer/parseInt ^String %)))

    :week
    (->> milestone-name
         (re-matches #"(Week|Woche) ([0-9]+)/([0-9]+).*")
         (take-last 2)
         (map #(Integer/parseInt ^String %)))

    :month
    (->> milestone-name
         (re-matches #"(Month|Monat) ([0-9]+)/([0-9]+).*")
         (take-last 2)
         (map #(Integer/parseInt ^String %)))

    nil))

(defn unify-milestone-name
  [milestone-name]
  (let [milestone-type (milestone-type milestone-name)
        milestone-data (milestone-data milestone-name milestone-type)]
    (case milestone-type
      :weeks
      (str "Weeks " (nth milestone-data 0) "/" (nth milestone-data 1) "-" (nth milestone-data 2))

      :week
      (str "Week " (nth milestone-data 0) "/" (nth milestone-data 1))

      :month
      (str "Month " (nth milestone-data 0) "/" (nth milestone-data 1))

      milestone-name)))

(defn milestone-start-end
  [milestone-name]
  (let [milestone-type (milestone-type milestone-name)
        milestone-data (milestone-data milestone-name milestone-type)]
    (case milestone-type
      :weeks
      (let [[year start-week end-week] milestone-data
            start-date (time-util/first-day-of-week year start-week)
            end-date (time/plus start-date (time/weeks (+ 1 (- end-week start-week))))]
        [start-date end-date])

      :week
      (let [[year week] milestone-data
            start-date (time-util/first-day-of-week year week)
            end-date (time/plus start-date (time/weeks 1))]
        [start-date end-date])

      :month
      (let [[year month] milestone-data
            start-date (time/first-day-of-the-month year month)
            end-date (time/plus start-date (time/months 1))]
        [start-date end-date])

      nil)))
