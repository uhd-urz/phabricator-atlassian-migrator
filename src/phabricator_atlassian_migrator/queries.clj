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

(ns phabricator-atlassian-migrator.queries)

(def q-object-by-phid
  '[:find ?object .
    :in $ ?phid
    :where
    [?object :object/phid ?phid]])

(def q-users
  '[:find [?user ...]
    :where
    [?user :user/id]])

(def q-user-by-username
  '[:find ?user .
    :in $ ?user-name
    :where
    [?user :user/username ?user-name]])

(def q-top-level-projects
  "Find all top-level projects, which do not have parents"
  '[:find [?project ...]
    :where
    [?project :project/id]
    [(missing? $ ?project :project/parent)]])

(def q-top-level-projects-with-members
  "Find all top-level projects, which do not have parents, but do have members"
  '[:find [?project ...]
    :where
    [?project :project/members]
    [(missing? $ ?project :project/parent)]])

(def q-active-top-level-projects-with-members
  "Find all top-level projects, which do not have parents, but do have members and are active"
  (concat q-top-level-projects-with-members
          '[(not [?project :project/color [:color/key "disabled"]])]))

(def q-top-level-projects-for-migration
  "Find all top-level projects, which do not have parents, but are marked for migration"
  '[:find [?project ...]
    :where
    [?project :project/jira-key]
    [(missing? $ ?project :project/parent)]])

(def q-project-by-name
  '[:find ?project .
    :in $ ?project-name
    :where
    [?project :project/name ?project-name]])

(def q-projects-by-names
  '[:find [?project ...]
    :in $ [?project-name ...]
    :where
    [?project :project/name ?project-name]])

(def q-all-super-projects-by-project
  '[:find [?super-project ...]
    :in $ % [?project-phid ...]
    :where
    [?project :object/phid ?project-phid]
    (subproject ?project ?super-project)])

(def q-subprojects-by-projects
  "Find all subprojects of a list of top-level projects given by PHIDs"
  '[:find [?subproject ...]
    :in $ % [?top-level-project ...]
    :where
    (subproject ?subproject ?top-level-project)
    [(missing? $ ?subproject :project/milestone)]])

(def q-milestones-by-projects
  '[:find [?subproject ...]
    :in $ % [?top-level-project ...]
    :where
    [?subproject :project/milestone]
    (subproject ?subproject ?top-level-project)])

(def q-direct-milestones-by-projects
  "Find all milestones belonging to projects given by PHID directly (i.e. not in subprojects)"
  '[:find [?subproject ...]
    :in $ % [?project-phid ...]
    :where
    [?project :object/phid ?project-phid]
    [?subproject :project/milestone]
    [?subproject :project/parent ?project]])

(def q-task-phids
  "List PHIDs of all tasks"
  '[:find [?phid ...]
    :where
    [?task :task/id]
    [?task :object/phid ?phid]])

(def q-task-by-phid
  "Find one specific by its PHID"
  '[:find ?task .
    :in $ ?phid
    :where
    [?task :object/phid ?phid]])

(def q-tasks-by-projects
  "Find all tasks belonging to a list of top-level projects given by PHIDs"
  '[:find [?task ...]
    :in $ % [?top-level-project ...]
    :where
    (subproject-or-self ?project ?top-level-project)
    [?task :task/projects ?project]])

(def q-user-stories-by-projects
  "Find all 'user story' tasks belonging to a list of top-level projects given by PHIDs"
  (concat q-tasks-by-projects
          '[[?user-story-project :project/name "User Story"]
            [?task :task/projects ?user-story-project]]))

(def q-epics-by-projects
  "Find all 'epic' tasks belonging to a list of top-level projects given by PHIDs"
  (concat q-tasks-by-projects
          '[[?epic-project :project/name "Epic"]
            [?task :task/projects ?epic-project]]))

(def q-transactions-by-tasks
  "Find all transactions belonging to tasks given by PHIDs"
  '[:find [?transaction ...]
    :in $ [?task-phid ...]
    :where
    [?task :object/phid ?task-phid]
    [?transaction :transaction/object ?task]])

(def q-comments-by-tasks
  "Find all comments belonging to tasks given by PHIDs"
  '[:find [?comment ...]
    :in $ [?task-phid ...]
    :where
    [?task :object/phid ?task-phid]
    [?transaction :transaction/object ?task]
    [?transaction :transaction/comments ?comment]])

(def q-wiki-page-by-path
  '[:find ?page .
    :in $ ?path
    :where
    [?page :wiki-page/path ?path]])

(def q-wiki-pages
  '[:find [?page ...]
    :where
    [?page :wiki-page/path]])

(def q-top-level-wiki-pages
  '[:find [?page ...]
    :where
    [?top-level :wiki-page/path "/"]
    [?page :wiki-page/parent ?top-level]])

(def q-top-level-wiki-pages-for-migration
  (concat q-top-level-wiki-pages
          '[[?project :project/jira-key]
            [?page :wiki-page/jira-project ?project]]))

(def q-new-top-level-wiki-pages-for-migration
  '[:find [?page ...]
    :where
    [?page :wiki-page/jira-project]])

(def q-active-subpages-by-wiki-page
  "Find all pages underneath a top-level wiki page, which are not active (not moved or deleted)"
  '[:find [?subpage ...]
    :in $ % [?top-level-wiki-page ...]
    :where
    (subpage ?subpage ?top-level-wiki-page)
    [?status :wiki-status/name "Active"]
    [?subpage :wiki-page/status ?status]])

(def r-subproject
  '[[(subproject ?e1 ?e2)
     [?e1 :project/parent ?e2]]
    [(subproject ?e1 ?e2)
     [?e1 :project/parent ?t]
     (subproject ?t ?e2)]
    [(subproject-or-self ?e1 ?e2)
     [?e2]
     (subproject ?e1 ?e2)]])

(def r-subpage
  '[[(subpage ?e1 ?e2)
     [?e1 :wiki-page/parent ?e2]]
    [(subpage ?e1 ?e2)
     [?e1 :wiki-page/parent ?t]
     (subpage ?t ?e2)]])
