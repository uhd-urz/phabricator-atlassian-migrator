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

(ns phabricator-atlassian-migrator.jira
  (:require
    [clj-http.client :as http]))

(defn call-jira
  [config api-path object-type parameters]
  (let [endpoint (get-in config [:jira :endpoint])
        username (get-in config [:jira :username])
        password (get-in config [:jira :password])]
    (:body (http/post (str endpoint api-path object-type)
                      {:as :json
                       :basic-auth [username password]
                       :content-type :json
                       :form-params parameters}))))

(defn call-jira-software
  [config object-type parameters]
  (call-jira config "/rest/api/2/" object-type parameters))

(defn call-jira-agile
  [config object-type parameters]
  (call-jira config "/rest/agile/1.0/" object-type parameters))

(defn get-jira
  [config api-path object-type parameters]
  (let [endpoint (get-in config [:jira :endpoint])
        username (get-in config [:jira :username])
        password (get-in config [:jira :password])]
    (:body (http/get (str endpoint api-path object-type)
                     {:as :json
                      :basic-auth [username password]
                      :query-params parameters}))))

(defn get-jira-software
  ([config object-type]
   (get-jira-software config object-type nil))
  ([config object-type parameters]
   (get-jira config "/rest/api/2/" object-type parameters)))

(defn get-jira-agile
  ([config object-type]
   (get-jira-agile config object-type nil))
  ([config object-type parameters]
   (get-jira config "/rest/agile/1.0/" object-type parameters)))

(defn delete-jira
  [config api-path object]
  (let [endpoint (get-in config [:jira :endpoint])
        username (get-in config [:jira :username])
        password (get-in config [:jira :password])]
    (:body (http/delete (str endpoint api-path object)
                        {:as :json
                         :basic-auth [username password]}))))

(defn delete-jira-software
  [config object]
  (delete-jira config "/rest/api/2/" object))

(defn delete-jira-agile
  [config object]
  (delete-jira config "/rest/agile/1.0/" object))

(defn jira-search
  ([fetch-fn config object-type parameters]
   (jira-search 0 fetch-fn config object-type parameters))
  ([start-at fetch-fn config object-type parameters]
   (let [parameters-with-start-at (merge parameters {:startAt start-at})
         result (fetch-fn config object-type parameters-with-start-at)
         data (-> result :values)
         next (+ start-at (count data))]
     (if (not-empty data)
       (lazy-seq (apply conj data (jira-search next fetch-fn config object-type parameters)))
       data))))

(defn jira-search-software
  [config object-type parameters]
  (jira-search get-jira-software config object-type parameters))

(defn jira-search-agile
  [config object-type parameters]
  (jira-search get-jira-agile config object-type parameters))
