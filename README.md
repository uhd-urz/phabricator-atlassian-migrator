# Phabricator to Atlassian JIRA / Confluence migrator

A tool to migrate data from Phabricator into Atlassian JIRA and Confluence.

Imports spaces, users, projects, tasks and wiki pages from Phabricator into a local [DataScript](https://github.com/tonsky/datascript) database (uses [DataLog](http://www.learndatalogtoday.org/) as query language; for documentation also see the excellent [Datomic docs](https://docs.datomic.com/)) in `phabricator-atlassian-migrator.{import,phabricator}`.  To make the data more easily digestable, it is massaged into shape, relations are created, etc.  Then some mappings are applied (`phabricator-atlassian-migrator.export-mappings`) to allow you to select projects or wiki pages to be migrated or users to be mapped to new names.  Then everything is exported into JIRA and Confluence (`phabricator-atlassian-migrator.{export,jira,confluence}`).

Phabricator subprojects of the top-level projects to be migrated are turned into JIRA project components.

Phabricator milestones are turned into JIRA sprints.  If they follow the scheme "Week yyyy/ww", "Weeks yyyy/ww-ww" or "Month yyyy/mm", it will automatically calculate start and end dates and use that in JIRA.  JIRA cannot next projects, so we map milestones of all subprojects of our top-level Phabricator projects into the same JIRA project, and merge milestones with the same name or representing the same sprint time span. 

## Prerequisites

* Java
* [Leiningen](https://leiningen.org/)

## Run

Use `lein repl` to open an interactive REPL in the `phabricator-atlassian-migrator.core` namespace.  From there either call `-main` and run everything this converter has to offer, or use the contents and comments in `-main` to migrate specific parts individually.

### Hints

Translate
```clojure
(let [a (,,,)
      b (,,,)]
  ,,,)
```
into
```clojure
(def a (,,,))
(def b (,,,))
,,,
```
for convenience at the REPL.

## Files

### `config.edn`

Use `config.example.edn` as an example.  It consists of information about the source (Phabricator) and target (JIRA / Confluence) systems and a few settings and preferences.

### `phabricator-user-mapping.csv`

Has 2 columns: Phabricator "username", JIRA "userid"
```csv
username,userid
testuser,testuser
olduser,newuser
```

Users are primarily used in conjunction with tasks. Use this list to map usernames as used by Phabricator to users in JIRA.

### `phabricator-project-mapping.csv`

Has 2 columns: Phabricator project "name", JIRA project "key"
```
name,key
Secret Project,SP
Other Project,OP
```

JIRA projects are created for each line in this file.  The Phabricator project with a given name will receive the given JIRA project key.

### `phabricator-wiki-mapping.csv`

Has 2 columns: Phabricator wiki "path", Phabricator project "name"
```
path,name
foo/,Foo Project
foo/bar/,Bar Project
foo/bar/baz/,Baz Project
```

Confluence Spaces are created for each "name" mentioned in this file.  All Phabricator wiki content below "path/" will be migrated to this Confluence Space.  They use the same JIRA project key and project name as the project referenced here.  If a project is referenced multiple times in this list, all the paths will be migrated to the same Confluence Space.

## License

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
