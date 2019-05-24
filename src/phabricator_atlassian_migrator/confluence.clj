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

(ns phabricator-atlassian-migrator.confluence
  (:require
    [clj-http.client :as http]))

(defn call-confluence
  [config object-type parameters]
  (let [endpoint (get-in config [:confluence :endpoint])
        username (get-in config [:confluence :username])
        password (get-in config [:confluence :password])]
    (:body (http/post (str endpoint "/rest/api/" object-type)
                      {:as :json
                       :basic-auth [username password]
                       :content-type :json
                       :form-params parameters}))))

(defn delete-confluence
  [config object]
  (let [endpoint (get-in config [:confluence :endpoint])
        username (get-in config [:confluence :username])
        password (get-in config [:confluence :password])]
    (:body (http/delete (str endpoint "/rest/api/" object)
                        {:as :json
                         :basic-auth [username password]}))))
