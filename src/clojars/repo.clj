(ns clojars.repo
  (:require [clojars.auth :refer [with-account require-authorization]]
            [clojars.db :refer [find-jar add-jar update-jar]]
            [clojars.config :refer [config]]
            [clojars.maven :as maven]
            [clojars.promote :as promote]
            [compojure.core :refer [defroutes PUT ANY]]
            [compojure.route :refer [not-found]]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [ring.util.codec :as codec]
            [ring.util.response :as response]
            [clj-stacktrace.repl :refer [pst]])
  (:import java.io.StringReader))

(defn save-to-file [sent-file input]
  (-> sent-file
      .getParentFile
      .mkdirs)
  (io/copy input sent-file))

(defroutes routes
  (PUT ["/:group/:artifact/:file"
        :group #".+" :artifact #"[^/]+" :file #"maven-metadata\.xml[^/]*"]
       {body :body {:keys [group artifact file]} :params}
       (with-account
         (require-authorization
          (string/replace group "/" ".")
          (save-to-file (io/file (config :repo) group artifact file)
                        body)
          {:status 201 :headers {} :body nil})))
  (PUT ["/:group/:artifact/:version/:filename"
        :group #"[^\.]+" :artifact #"[^/]+" :version #"[^/]+"
        :filename #"[^/]+(\.pom|\.jar|\.sha1|\.md5|\.asc)$"]
       {body :body {:keys [group artifact version filename]} :params}
       (let [groupname (string/replace group "/" ".")]
         (with-account
           (require-authorization
            groupname
            (try
              (let [info {:group groupname
                          :name  artifact
                          :version version}
                    file (io/file (config :repo) group
                                  artifact version filename)]
                (try
                  (if (.endsWith filename ".pom")
                    (let [contents (slurp body)
                          pom-info (merge (maven/pom-to-map
                                           (StringReader. contents)) info)]
                      (save-to-file file contents)
                      (if (find-jar groupname artifact version)
                        (update-jar account pom-info)
                        (add-jar account pom-info)))
                    (do
                      (save-to-file file body)
                      (when-not (find-jar groupname artifact version)
                        (add-jar account info))))
                  (catch java.io.IOException e
                    (.delete file)
                    (throw e)))
                (.offer promote/queue info))
              {:status 201 :headers {} :body nil}
              (catch Exception e
                (pst e)
                {:status 403 :headers {} :body (.getMessage e)}))))))
  (PUT "*" _ {:status 400 :headers {}})
  (not-found "Page not found"))

(defn wrap-file [app dir]
  (fn [req]
    (if-not (= :get (:request-method req))
      (app req)
      (let [path (codec/url-decode (:path-info req))]
        (or (response/file-response path {:root dir})
            (app req))))))
