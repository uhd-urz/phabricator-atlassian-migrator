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

(ns phabricator-atlassian-migrator.util.markdown
  (:import
    (com.vladsch.flexmark.parser Parser)
    (com.vladsch.flexmark.html HtmlRenderer)
    (com.vladsch.flexmark.ext.gfm.tables TablesExtension)
    (com.vladsch.flexmark.util.options MutableDataSet)))

(def md-extensions
  [(TablesExtension/create)])

(def md-parser
  (.. (Parser/builder
        (MutableDataSet.))
      (extensions md-extensions)
      (build)))

(defn- md-renderer
  "Create a Markdown renderer."
  [{:keys [escape-html?]}]
  (.. (HtmlRenderer/builder
        (MutableDataSet.))
      (escapeHtml (boolean escape-html?))
      (extensions md-extensions)
      (build)))

(defn markdown-to-html
  "Parse the given string as Markdown and return HTML.

  A second argument can be passed to customize the rendering
  supported options:

  - `:escape-html?` if true HTML in input-str will be escaped"
  ([input-str]
   (markdown-to-html input-str {}))
  ([input-str opts]
   (->> input-str
        (.parse md-parser)
        (.render (md-renderer opts)))))
