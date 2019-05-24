(defproject phabricator-atlassian-migrator "0.1.0-SNAPSHOT"
  :description "Migrate data from Phabricator to JIRA"
  :license {:name "GPL-3.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.gnu.org/licenses/gpl-3.0-standalone.html"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 ; Databases:
                 [datascript "0.18.2"]
                 [datascript-transit "0.3.0"]
                 ; HTTP requests:
                 [clj-http "3.9.1"]
                 ; JSON encoding:
                 [cheshire "5.8.1"]
                 ; Logging:
                 [com.taoensso/timbre "4.10.0"]
                 ; Colourful pretty printing
                 [mvxcvi/puget "1.1.2"]
                 ; Time:
                 [clj-time "0.15.0"]
                 ; Shell out:
                 [clj-commons/conch "0.9.2"]
                 ; CSV
                 [semantic-csv "0.2.1-alpha1"]
                 ; CommonMark <-> HTML for Clojure
                 [com.vladsch.flexmark/flexmark "0.40.16"]
                 [com.vladsch.flexmark/flexmark-ext-gfm-tables "0.40.16"]]
  :repl-options {:init-ns phabricator-atlassian-migrator.core})
