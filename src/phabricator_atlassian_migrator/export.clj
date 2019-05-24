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

(ns phabricator-atlassian-migrator.export
  (:require
    [clj-http.client :as http]
    [clj-time.coerce :as coerce-time]
    [clojure.string :as string]
    [datascript.core :as d]
    [phabricator-atlassian-migrator.confluence :as confluence]
    [phabricator-atlassian-migrator.jira :as jira]
    [phabricator-atlassian-migrator.queries :as queries]
    [phabricator-atlassian-migrator.util.coll :as coll-util]
    [phabricator-atlassian-migrator.util.markdown :as markdown-util]
    [phabricator-atlassian-migrator.util.milestone :as milestone-util]
    [phabricator-atlassian-migrator.util.string :as string-util]
    [taoensso.timbre :as log]))

(defn create-component
  "Create a single component of `jira-project-key` from `subproject`"
  [config jira-project-key subproject]
  (let [subproject-name (:project/name subproject)
        subproject-description (:project/description subproject)
        jira-component (jira/call-jira-software
                         config ; Creating components requires admin permissions
                         "component"
                         (into {}
                               (filter coll-util/some-value-kv?
                                       {:name subproject-name
                                        :description subproject-description
                                        :project jira-project-key})))
        jira-component-id (:id jira-component)]
    (log/debug "Created component" subproject-name "in" jira-project-key)
    jira-component-id))

(defn create-components
  "Create components of projects in JIRA from subprojects of our top-level projects in Phabricator"
  [db config]
  (http/with-connection-pool
    {:timeout 5 :threads 4}
    (let [projects (->> (d/q queries/q-top-level-projects-for-migration @db)
                        (d/pull-many @db '[:db/id :project/jira-key]))]
      (doseq [project projects]
        (let [project-id (:db/id project)
              jira-project-key (:project/jira-key project)
              subproject-blacklist-names (:subproject-component-blacklist config)
              subproject-blacklist-db-ids (d/q queries/q-projects-by-names @db subproject-blacklist-names)
              subprojects (->> [project-id]
                               (d/q queries/q-subprojects-by-projects @db queries/r-subproject)
                               (coll-util/seq-difference-swapped subproject-blacklist-db-ids)
                               (d/pull-many @db '[:db/id :project/name :project/description]))]
          (doseq [subproject subprojects]
            (let [subproject-id (:db/id subproject)
                  jira-component-id (create-component config jira-project-key subproject)]
              (d/transact! db [{:db/id                     subproject-id
                                :project/jira-component-id jira-component-id}])))))))
  nil) ; Prevent large output in REPL

(defn create-sprint
  "Create a single sprint in JIRA"
  [config jira-board-id milestone-name start-date end-date]
  (let [milestone-start (some-> start-date coerce-time/to-string)
        milestone-end (some-> end-date coerce-time/to-string)
        jira-sprint-name (string-util/friendly-subs milestone-name 0 30)
        jira-sprint (jira/call-jira-agile
                      config
                      "sprint"
                      (into {}
                            (filter coll-util/some-value-kv?
                                    {:name jira-sprint-name
                                     :startDate milestone-start
                                     :endDate milestone-end
                                     :originBoardId jira-board-id})))
        jira-sprint-id (:id jira-sprint)]
    (log/debug "Created sprint" jira-sprint-name "in" jira-board-id)
    jira-sprint-id))

(defn create-sprints
  "Create in JIRA all sprints from the Phabricator milestones in our `db`.
  Because we retrieve all milestones from all subprojects from Phabricator, there might be duplications which we cannot migrate without loss into JIRA.
  So we map milestones with the same name (or representing the same sprint time span into a single JIRA sprint)."
  [db config]
  (http/with-connection-pool
    {:timeout 5 :threads 4}
    (let [projects (->> (d/q queries/q-top-level-projects-for-migration @db)
                        (d/pull-many @db '[:db/id
                                           :object/phid
                                           :project/name
                                           :project/jira-key
                                           :project/jira-board-id]))]
      (doseq [project projects]
        (let [project-id (:db/id project)
              jira-board-id (:project/jira-board-id project)
              milestones (->> [project-id]
                              (d/q queries/q-milestones-by-projects @db queries/r-subproject)
                              (d/pull-many @db '[:db/id
                                                 :project/name
                                                 :project/description
                                                 {:project/color [:color/key]}])
                              (group-by :project/name))
              ; We sometimes have Phabricator milestones with duplicate names (from subprojects) or similar names (accidential duplications) that we merge into one JIRA sprint
              milestone-unified-names (->> milestones
                                           keys
                                           set
                                           seq
                                           (group-by milestone-util/unify-milestone-name))]
          (doseq [milestone-unified-name (sort-by #(or (some->>
                                                         (milestone-util/milestone-data % (milestone-util/milestone-type %))
                                                         (take 2) ; default comparator sorts vectors by length
                                                         vec)
                                                       %)
                                                  (keys milestone-unified-names))]
            (let [[start-date end-date] (milestone-util/milestone-start-end milestone-unified-name)
                  jira-sprint-id (create-sprint config jira-board-id milestone-unified-name start-date end-date)
                  matching-milestone-names (get milestone-unified-names milestone-unified-name)
                  matching-milestones (mapcat #(get milestones %) matching-milestone-names)]
              (doseq [milestone matching-milestones]
                (let [milestone-id (:db/id milestone)]
                  (d/transact! db [{:db/id                  milestone-id
                                    :project/jira-sprint-id jira-sprint-id}])))))))))
  nil) ; Prevent large output in REPL

(defn close-sprints
  "Close all sprints we know about in our `db`.
  Afterwards no tasks can be added anymore, so they should be created before closing any sprints."
  [db config]
  (http/with-connection-pool
    {:timeout 5 :threads 4}
    (let [jira-sprints (->> (d/q queries/q-top-level-projects-for-migration @db)
                            (d/q queries/q-milestones-by-projects @db queries/r-subproject)
                            (d/pull-many @db '[:project/name
                                               :project/jira-sprint-id
                                               {:project/color [:color/key]}])
                            (group-by :project/jira-sprint-id))]
      (doseq [[jira-sprint-id milestones] jira-sprints]
        (let [all-milestones-closed? (->> milestones
                                          (map (comp :color/key :project/color)) ; pick color for filter below
                                          (filter (comp nil? #{"disabled"})) ; pick milestones which are not closed
                                          empty?) ; only closed milestones?
              any-milestone-has-dates? (-> milestones
                                           first
                                           :project/name
                                           milestone-util/milestone-type ; milestone is a sprint we extracted a start/end for
                                           some?)]
          (when (and all-milestones-closed? any-milestone-has-dates?)
            (jira/call-jira-agile
              config
              (str "sprint/" jira-sprint-id)
              {:state "active"})
            (jira/call-jira-agile
              config
              (str "sprint/" jira-sprint-id)
              {:state "closed"})))))))

(defn create-project
  "Create in JIRA a single given project"
  [config project]
  (let [project-name (:project/name project)
        project-description (:project/description project)
        jira-project-key (:project/jira-key project)
        project-lead (get-in config [:jira :username])
        project-type (get-in config [:jira :project-type])
        project-template (get-in config [:jira :project-template])
        jira-project (jira/call-jira-software
                       config ; Creating projects requires admin permissions
                       "project"
                       (into {}
                             (filter coll-util/some-value-kv?
                                     {:key jira-project-key
                                      :name project-name
                                      :description project-description
                                      :lead project-lead
                                      :projectTypeKey project-type
                                      :projectTemplateKey project-template})))
        jira-project-id (:id jira-project)]
    (log/debug "Created project" project-name)
    jira-project-id))

(defn create-projects
  "Create in JIRA all projects that we know about in our `db`.
  Does not create subprojects and milestones, for which there are separate functions."
  [db config]
  (http/with-connection-pool
    {:timeout 5 :threads 4}
    (let [projects (->> (d/q queries/q-top-level-projects-for-migration @db)
                        (d/pull-many @db '[:db/id
                                           :project/name
                                           :project/description
                                           :project/jira-key]))]
      (doseq [project projects]
        (let [project-id (:db/id project)
              jira-project-id (create-project config project)]
          (d/transact! db [{:db/id                   project-id
                            :project/jira-project-id jira-project-id}])))))
  nil) ; Prevent large output in REPL

(defn delete-projects
  "Delete from JIRA all projects that we know about in our `db`"
  [db config]
  (http/with-connection-pool
    {:timeout 5 :threads 4}
    (let [projects (->> (d/q queries/q-top-level-projects-for-migration @db)
                        (d/pull-many @db '[:project/jira-key]))]
      (doseq [project projects]
        (let [jira-project-key (:project/jira-key project)
              board-ids (doall (->> (try
                                      (jira/jira-search-agile
                                        config
                                        "board"
                                        {:projectKeyOrId jira-project-key})
                                      (catch Exception e
                                        (when (nil? (#{400} (:status (ex-data e))))
                                          (throw e))))
                                    (map :id)))]
          (doseq [id board-ids]
            (try
              (jira/delete-jira-agile
                config
                (str "board/" id))
              (catch Exception e
                (when (nil? (#{404} (:status (ex-data e))))
                  (throw e)))))
          (try
            (jira/delete-jira-software
              config
              (str "project/" jira-project-key))
            (catch Exception e
              (when (nil? (#{404} (:status (ex-data e))))
                (throw e)))))))))

(defn create-comment
  "To a given `jira-task` add a single comment.
  Meta data that cannot be migrated without loss is added to the top of the task."
  [config jira-task comment]
  (let [jira-task-key (:key jira-task)
        body (:comment/content comment)
        date (:comment/dateCreated comment)
        commenter (-> comment :comment/author :user/account-id)
        username (-> comment :comment/author :user/username)
        realname (-> comment :comment/author :user/realName)]
    (jira/call-jira-software
      config
      (str "issue/" jira-task-key "/comment")
      {:body (str "Commenter: " commenter " / " username " (" realname ")\n"
                  "Date: " date "\n"
                  "----\n"
                  body)})))

(def phab-priority->jira-priority
  {"Normal" "3"
   "Needs Triage" "3"
   "Wishlist" "5"
   "Low" "4"
   "Unbreak Now!" "1"
   "High" "2"})

(def phab-status->jira-transition
  {"Resolved" {:transition {:id "31"}}
   ;:fields {:resolution {:id "10000"}}}
   "Wontfix" {:transition {:id "31"}}
   ;:fields {:resolution {:id "10001"}}}
   "Invalid"  {:transition {:id "31"}}
   ;:fields {:resolution {:id "10003"}}}
   "Duplicate"  {:transition {:id "31"}}
   ;:fields {:resolution {:id "10002"}}}
   "Open" nil})

(defn create-task
  "Create in JIRA a single task under `project-jira-key`, move it to the sprint it is in, if any, and transition it to a matching state.
  Meta data that cannot be migrated without loss is added to the top of the task."
  [db config project-jira-key task]
  (let [task-phid (:object/phid task)
        task-id (:task/id task)
        task-name (:task/name task)
        project-phids (map :object/phid (:task/projects task))
        parents (d/pull-many
                  @db
                  '[:object/phid :project/slug :project/name :project/milestone]
                  (d/q queries/q-all-super-projects-by-project @db queries/r-subproject project-phids))
        projects (seq (set (apply conj parents (:task/projects task))))
        jira-component-ids (->> projects
                                (filter :project/jira-component-id)
                                (map :project/jira-component-id))
        jira-sprint-id (->> projects
                            (filter :project/jira-sprint-id)
                            (map :project/jira-sprint-id)
                            first)
        labels (->> projects
                    (filter #(not (:project/milestone %)))
                    (map :project/slug))
        dateCreated (:task/dateCreated task)
        dateModified (:task/dateModified task)
        dateClosed (:task/dateClosed task)
        author (-> task :task/author :user/account-id)
        author-username (-> task :task/author :user/username)
        author-realname (-> task :task/author :user/realName)
        owner (-> task :task/owner :user/account-id)
        owner-username (-> task :task/owner :user/username)
        owner-realname (-> task :task/owner :user/realName)
        closer (-> task :task/closer :user/account-id)
        closer-username (-> task :task/closer :user/username)
        closer-realname (-> task :task/closer :user/realName)
        subscriber-accounts-and-usernames (map
                                            #(str "@" (:user/account-id %) " / " (:user/username %) " (" (:user/realName %) ")")
                                            (:task/subscribers task))
        jira-transition (-> task :task/status :task-status/name phab-status->jira-transition)
        jira-priority (-> task :task/priority :task-priority/name phab-priority->jira-priority)
        jira-task (jira/call-jira-software
                    config
                    "issue"
                    {:fields
                     (into {}
                           (filter coll-util/some-value-kv?
                             {:project {:key project-jira-key}
                              :components (map (fn [component-id] {:id component-id})
                                               jira-component-ids)
                              :priority {:id jira-priority}
                              :assignee (when (get-in config [:jira :export-task-assignees]) {:name owner})
                              :reporter (when (get-in config [:jira :export-task-reporters]) {:name author})
                              :summary (str task-name " (T" task-id ")")
                              :description (str "Author: @" author " / " author-username " (" author-realname ")\n"
                                                (if owner
                                                  (str "Owner: @" owner " / " owner-username " (" owner-realname ")\n")
                                                  "")
                                                (if closer
                                                  (str "Closer: @" closer " / " closer-username " (" closer-realname ")\n")
                                                  "")
                                                (if (not-empty subscriber-accounts-and-usernames)
                                                  (str "Subscribers: " (string/join ", " subscriber-accounts-and-usernames) "\n")
                                                  "")
                                                "Created: " dateCreated "\n"
                                                (if dateModified
                                                  (str "Modified: " dateModified "\n")
                                                  "")
                                                (if dateClosed
                                                  (str "Closed: " dateClosed "\n")
                                                  "")
                                                "----\n"
                                                (:task/description task))
                              :labels labels
                              :issuetype {:name (get-in config [:jira :issue-type-name])}}))})
        jira-task-key (:key jira-task)
        comments (->> (d/q queries/q-comments-by-tasks @db [task-phid])
                      (d/pull-many @db
                                   '[:object/phid
                                     :comment/content
                                     :comment/dateCreated
                                     {:comment/author [:user/account-id
                                                       :user/username
                                                       :user/realName]}])
                      (filter #(not-empty (:comment/content %)))
                      (sort-by :comment/dateCreated))]
    (when jira-sprint-id
      (jira/call-jira-agile
        config
        (str "sprint/" jira-sprint-id "/issue")
        {:issues [jira-task-key]}))
    (doseq [comment comments]
      (create-comment config jira-task comment))
    ;(doseq [subscriber subscribers-and-author]
    ;  (call-jira (use-admin-permissions config)
    ;    (str "issue/" jira-task-key "/watchers") subscriber))) ; FIXME Needs admin rights to add!
    (when jira-transition
      (jira/call-jira-software
        config
        (str "issue/" jira-task-key "/transitions")
        jira-transition))
    (log/debug "Created task" task-name "(T" task-id ")")
    jira-task))

(defn create-tasks
  "Create in JIRA all tasks that we know about in our `db`"
  [db config]
  (http/with-connection-pool
    {:timeout 5 :threads 4}
    (let [projects (->> (d/q queries/q-top-level-projects-for-migration @db)
                        (d/pull-many @db '[:db/id :project/jira-key]))]
      (doseq [project projects]
        (let [project-jira-key (:project/jira-key project)
              project-tasks (->> [(:db/id project)]
                                 (d/q queries/q-tasks-by-projects @db queries/r-subproject)
                                 (d/pull-many
                                   @db
                                   '[:object/phid
                                     :task/id
                                     {:task/author [:user/account-id
                                                    :user/username
                                                    :user/realName]}
                                     :task/name
                                     :task/description
                                     :task/dateCreated
                                     :task/dateModified
                                     :task/dateClosed
                                     {:task/subscribers [:user/account-id
                                                         :user/username
                                                         :user/realName]}
                                     {:task/status [:task-status/name]}
                                     {:task/priority [:task-priority/name]}
                                     {:task/owner [:user/account-id
                                                   :user/username
                                                   :user/realName]}
                                     {:task/closer [:user/account-id
                                                    :user/username
                                                    :user/realName]}
                                     {:task/projects [:object/phid
                                                      :project/slug
                                                      :project/name
                                                      :project/milestone
                                                      :project/jira-version-id
                                                      :project/jira-sprint-id
                                                      :project/jira-component-id]}]))]
          (doseq [task project-tasks]
            (create-task db config project-jira-key task)))))))

(defn create-spaces
  "Create in Confluence all spaces that we know about in our `db`"
  [db config]
  (http/with-connection-pool
    {:timeout 5 :threads 4}
    (let [top-level-pages (->> (d/q queries/q-new-top-level-wiki-pages-for-migration @db)
                               (d/pull-many @db '[{:wiki-page/jira-project [:db/id
                                                                            :project/jira-key
                                                                            :project/name]}])
                               set ; We migrate several top-level wiki pages to the same Confluence Space
                               seq)]
      (doseq [page top-level-pages]
        (let [project (:wiki-page/jira-project page)
              project-id (:db/id project)
              project-key (:project/jira-key project)
              project-name (:project/name project)
              jira-space (confluence/call-confluence config "space" {:key project-key
                                                                     :name project-name})
              jira-space-homepage-id (get-in jira-space [:homepage :id])]
          (d/transact! db [{:db/id project-id
                            :project/jira-space-homepage-id jira-space-homepage-id}])
          (log/debug "Created space" project-key))))))

(defn delete-spaces
  "Delete from Confluence all spaces that we have in `db`"
  [db config]
  (http/with-connection-pool
    {:timeout 5 :threads 4}
    (let [top-level-pages (->> (d/q queries/q-new-top-level-wiki-pages-for-migration @db)
                               (d/pull-many @db '[{:wiki-page/jira-project [:project/jira-key
                                                                            :project/name]}])
                               set ; We migrate several top-level wiki pages to the same Confluence Space
                               seq)]
      (doseq [page top-level-pages]
        (try
          (confluence/delete-confluence config (str "space/" (-> page :wiki-page/jira-project :project/jira-key)))
          (catch Exception e
            (log/debug e)))))))

(defn create-page
  "Create a single wiki page in Confluence, under the space with key `jira-space-key`, linking it as a child of `jira-parent-page-id`"
  [db config jira-space-key jira-parent-page-id page]
  (let [path (:wiki-page/path page)
        title (:wiki-page/title page)
        content (markdown-util/markdown-to-html (:wiki-page/content page) {:escape-html? true})
        ancestors (when jira-parent-page-id {:ancestors [{:id jira-parent-page-id}]})]
    (confluence/call-confluence config "content" (merge
                                                   {:title (str title " (" path ")") ; Confluence needs unique page names per space
                                                    :type "page"
                                                    :space
                                                           {:key jira-space-key}
                                                    :body
                                                           {:storage
                                                            {:value content
                                                             :representation "storage"}}}
                                                   ancestors))))

(defn create-child-pages
  "Recursively walk down a tree, creating a wiki page and all its children"
  [db config jira-space-key jira-parent-page-id parent-page pages]
  (let [next-level-pages (filter #(= (get-in % [:wiki-page/parent :db/id]) (:db/id parent-page))
                                 pages)]
    (doseq [page next-level-pages]
      (let [jira-page (create-page
                        db
                        config
                        jira-space-key
                        jira-parent-page-id
                        page)
            jira-page-id (:id jira-page)]
        (create-child-pages db config jira-space-key jira-page-id page pages)))))

(defn create-pages
  "Assuming Spaces were created already, create in Confluence all wiki pages that are present in `db`"
  [db config]
  (http/with-connection-pool
    {:timeout 5 :threads 4}
    (let [top-level-pages (->> (d/q queries/q-new-top-level-wiki-pages-for-migration @db)
                               (d/pull-many @db '[:db/id
                                                  :wiki-page/path
                                                  :wiki-page/title
                                                  :wiki-page/content
                                                  {:wiki-page/jira-project [:project/jira-key
                                                                            :project/jira-space-homepage-id]}]))
          top-level-paths (set (map :wiki-page/path top-level-pages))]
      (doseq [top-level-page top-level-pages]
        (let [top-level-path (:wiki-page/path top-level-page)
              jira-space-key (get-in top-level-page [:wiki-page/jira-project :project/jira-key])
              jira-space-homepage-id (get-in top-level-page [:wiki-page/jira-project :project/jira-space-homepage-id])
              more-precise-top-level-paths (set (filter #(< (count top-level-path) (count %)) top-level-paths))
              other-top-level-paths (clojure.set/difference
                                      more-precise-top-level-paths
                                      #{top-level-path})
              pages (->> [(:db/id top-level-page)]
                         (d/q queries/q-active-subpages-by-wiki-page @db queries/r-subpage)
                         (apply conj [(:db/id top-level-page)])
                         (d/pull-many @db '[:db/id
                                            :wiki-page/path
                                            :wiki-page/title
                                            :wiki-page/content
                                            :wiki-page/parent])
                         (filter (comp ; Do not include pages that are also under another space with more precise path requirements
                                   not
                                   (partial string-util/path-underneath-any other-top-level-paths)
                                   :wiki-page/path)))
              top-level-jira-page (create-page
                                    db
                                    config
                                    jira-space-key
                                    {:id jira-space-homepage-id}
                                    top-level-page)
              top-level-jira-page-id (:id top-level-jira-page)]
          (create-child-pages db config jira-space-key top-level-jira-page-id top-level-page pages))))))
