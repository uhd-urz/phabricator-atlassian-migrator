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

(ns phabricator-atlassian-migrator.util.debug
  (:require
    [puget.printer :as puget]
    [taoensso.timbre :as log]))

(defn- fline
  [and-form]
  (:line (meta and-form)))

(defmacro -debug [?line config level name expr]
  `(log/-log-and-rethrow-errors ~?line
                                (let [result# ~expr]
                                  ;; Subject to elision:
                                  ;; (log* ~config ~level ~name "=>" result#) ; CLJ-865
                                  (log/log!
                                    ~level
                                    :p
                                    [~name "=>\n" (puget/cprint-str result#)]
                                    ~{:?line ?line :config config})

                                  ;; NOT subject to elision:
                                  result#)))

(defmacro debug
  "Evaluates named expression and logs its result. Always returns the result.
  Defaults to :debug logging level and unevaluated expression as name."
  [expr]
  `(-debug ~(fline &form) log/*config* :debug '~expr ~expr))

(defmacro log-exceptions [exception-store expr]
  `(try ~expr
        (catch Exception e#
          (swap! ~exception-store conj e#))))
