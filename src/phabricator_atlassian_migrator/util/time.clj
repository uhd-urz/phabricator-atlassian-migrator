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

(ns phabricator-atlassian-migrator.util.time
  (:require
    [clj-time.core :as time]))

(defn first-day-of-week
  [year week]
  (let [first-day-of-year (time/first-day-of-the-month year 1)
        day-of-week (time/day-of-week first-day-of-year)
        first-day-of-first-week (time/minus first-day-of-year (time/days (- day-of-week 1)))]
    (time/plus first-day-of-first-week (time/weeks (- week 1)))))
