(ns pallet.crate.nginx
  "Crate for nginx management functions"
  (:require
;    [pallet.crate.rubygems :as rubygems]
    [pallet.crate :refer [admin-user defplan get-settings
                         assoc-settings]]
    [pallet.crate.automated-admin-user :as automated-admin-user]
    [pallet.actions :refer [service-script service file remote-file user
                            exec-checked-script directory package
                            remote-directory symbolic-link]]
    [pallet.stevedore :as stevedore]
    [pallet.template :as template]
    [pallet.api :as api]
    [pallet.strint :as strint]
    [pallet.utils :as utils]
    [pallet.core.session :as session]
    [clojure.string :as string])
  (:use
    pallet.thread-expr
    [pallet.script.lib :only [tmp-dir]]))

(def src-packages
  ["libpcre3" "libpcre3-dev" "libssl1.0.0" "libssl-dev" "make" "build-essential"])

(def nginx-md5s
  {"1.2.6" "1350d26eb9b66364d9143fb3c4366ab6"})

(defn ftp-path [version]
  (format "http://nginx.org/download/nginx-%s.tar.gz" version))

(def nginx-conf-dir "/etc/nginx")
(def nginx-install-dir "/opt/nginx")
(def nginx-log-dir "/var/log/nginx")
(def nginx-pid-dir "/var/run/nginx")
(def nginx-user "www-data")
(def nginx-group "www-data")
(def nginx-binary "/usr/local/sbin/nginx")

(def nginx-init-script "crate/nginx/nginx")
(def nginx-conf "crate/nginx/nginx.conf")
(def nginx-site "crate/nginx/site")
(def nginx-location "crate/nginx/location")
(def nginx-passenger-conf "crate/nginx/passenger.conf")
(def nginx-mime-conf "crate/nginx/mime.types")

(def nginx-defaults
  {:version "1.2.6"
   :modules [:http_ssl_module]})

(def nginx-default-conf
  {:gzip "on"
   :gzip_http_version "1.0"
   :gzip_comp_level "2"
   :gzip_proxied "any"
   :gzip_types ["text/plain"
                "text/css"
                "application/x-javascript"
                "text/xml"
                "application/xml"
                "application/xml+rss"
                "text/javascript"]
   :client_max_body_size "10M"
   :sendfile "on"
   :tcp_nopush "off"
   :tcp_nodelay "off"
   :keepalive "on"
   :keepalive_timeout 65
   :worker_processes "total"
   :worker_connections 2048
   :server_names_hash_bucket_size 64})


(def default-site
  {:sites [:action :enable
           :name "default"
           :upstreams []
           :servers [{:server-name "localhost"
                      :listen "80"
                      :access-log  [(str nginx-log-dir "/access.log")] 
                      :locations [{:path "/"
                                   :index ["index.html" "index.htm"]}]}]]})

(defn convert-key-to-nginx [key]
  (clojure.string/replace (name key) "-" "_"))

(def join-with-space 
  (partial clojure.string/join " "))

(defmulti str-location-line (fn [key val] key))
;; vals for proxy-set-header should be an array of dictionaries
(defmethod str-location-line :proxy-set-header [ky vls]
 (let [
       output (map (fn [item] 
              (let [
                    k (first (keys item))
                    v (first (vals item))
                    ] 
                 (format "\t\t%s %s %s;\n" (convert-key-to-nginx ky) 
                        (convert-key-to-nginx k) v)   
                )) vls)]
   (apply str output)))
;; vals for index will be an array of strings
(defmethod str-location-line :index [ky vls]
  (format "\t\t%s %s;\n" (convert-key-to-nginx ky)
          (join-with-space vls)))
(defmethod str-location-line :default [key val]
  (format "\t\t%s %s;\n" (convert-key-to-nginx key) val))

;; DEFINING SERVERLINE MULTI-METHODS
(defmulti str-server-line (fn [key val] key))
;; vals for this should e an array of strings to print out
(defmethod str-server-line :access-log [key vals]
  (format "\t%s %s;\n" (convert-key-to-nginx key) (join-with-space vals))) 
(defmethod str-server-line :default [key vals]
  (format "\t%s %s;\n" (convert-key-to-nginx key) vals))
;; This one the vals should be [{path location-data}
(defmethod str-server-line :locations [ky vls]
  (let [
        data (map (fn [dict]
                    (let [
                          path (:path dict)
                          dict-minus (dissoc dict :path)
                          location-str 
                            (apply str (map (fn [[k v]]
                                            (str-location-line k v)) dict-minus))]
                      (format "\tlocation %s {\n%s\t}\n" path
                              location-str))) vls)] 
    (apply str data))) 

(defmulti str-upstream-line (fn [key val] key))
(defmethod str-upstream-line :default [key val]
  (format "\t%s %s;\n" (convert-key-to-nginx key) val))

(defn str-server-block
  "The server data"
  [dict]
  (apply str (map (fn [[k v]]
         (str-server-line k v)) dict)))

(defn str-server-blocks
  "A sequence of maps"
  [server-array]
  (let [str-blocks (map (fn [server]
         (let [server-line (str-server-block server)]
           (format "server {\n%s}\n" server-line))) server-array)]
    (apply str str-blocks)))

(defn str-upstream-blocks
  "A list of upstream blocks where the data is
  [{:name \"http_backend\"
    :lines [{:server \"127.0.0.1\"}]}]"
  [upstream-blocks]
  (apply str (map (fn [block] 
                    (let [block-name (:name block)
                          lines (:lines block)
                          data (map (fn [item] 
                                      (let [k (first (keys item))
                                            v (first (vals item))
                                            ]
                                      (str-upstream-line k v))) lines)
                          flat (apply str data)
                          ]
                      (format "upstream %s {\n%s}\n" block-name flat)))
                  upstream-blocks)))

(defn str-site-file
  "A dictionary of {:upstreams [{:name <name> :lines <lines>}] 
   :servers [{:locations [{:path <path>}]}]"
  [site-map]
  (format "%s%s" (str-upstream-blocks (:upstreams site-map))
          (str-server-blocks (:servers site-map))))

(defplan install-nginx
  "Install nginx from source. Options:
  :version version-string   -- specify the version (default \"0.7.65\")
  :configuration map        -- map of values for nginx.conf"
  [& {:keys [instance-id]}]
  (let [settings (get-settings :nginx {:instance-id instance-id})
        version (settings :version)
        modules (settings :version)
        basename (str "nginx-" version)
        tarfile (str basename ".tar.gz")
        ;; Should use tmp-dir but tmp-dir does not work on Ubuntu
        ;; tmp-dir from pallet should be made more general
        tarpath (str "tmp" "/" tarfile)
        settings (if (:passenger settings)
                   (update-in
                     settings [:add-modules]
                     (fn [m]
                       (conj (or m [])
                             (stevedore/script
                               (str @("/usr/local/bin/passenger-config --root")
                                    "/ext/nginx")))))
                   settings)]

    (doseq [p src-packages]
      (package p))
    (user
      nginx-user
      :home nginx-install-dir :shell :false :create-home true :system true)
    (directory nginx-install-dir :owner "root")
    (remote-directory
      nginx-install-dir :url (ftp-path version) :md5 (get nginx-md5s version "x"))
    (when (:passenger settings)
      (package "g++")
      (package "libxslt1.1")
      (package "libssl-dev")
      (package "zlib1g-dev")
      ;  (rubygems/require-rubygems)
      ;  (rubygems/gem "passenger" :no-ri true :no-rdoc true
      )
    (exec-checked-script
      "Build nginx"
      (if-not (and @(pipe (~nginx-binary -v) ("grep" ~version))
                   @(pipe (~nginx-binary -V)
                          ("grep" ~(if (:passenger settings) "" "-v") "passenger")))
        (do
          ("cd" ~nginx-install-dir)
          ("./configure"
            ~(format "--conf-path=%s/nginx.conf" nginx-conf-dir)
            ~(format "--prefix=%s" nginx-install-dir)
            ~(format "--pid-path=%s/nginx.pid" nginx-pid-dir)
            ~(format "--error-log-path=%s/error.log" nginx-log-dir)
            ~(format "--http-log-path=%s/access.log" nginx-log-dir)
            ~(format "--sbin-path=%s" nginx-binary)
            ~(apply
               str
               (map #(format "--with-%s " (utils/as-string %)) (settings :modules)))
            ~(apply
               str
               (map #(format "--add-module=%s " (utils/as-string %)) 
                    (settings :add-modules))))
          ("make")
          ("make install")))) 
    (remote-file
      (format "%s/nginx.conf" nginx-conf-dir)
      :template nginx-conf
      :values (reduce
                merge {}
                [nginx-default-conf
                 (settings :configuration)
                 (strint/capture-values nginx-user nginx-group)])
      :owner "root" :group nginx-group :mode "0644") 
    (directory
      (format "%s/conf.d" nginx-conf-dir)
      :owner nginx-user :group nginx-group :mode "0755") 
    (when (:passenger settings)
      (remote-file
        (format "%s/conf.d/passenger.conf" nginx-conf-dir)
        :template nginx-passenger-conf
        :values (merge
                  {:passenger-root
                   (stevedore/script
                     @("/usr/local/bin/passenger-config --root"))
                   :passenger-ruby
                   (stevedore/script
                     @("which ruby"))}
                  (:configuration settings))
        :owner "root" :group nginx-group :mode "0644")) 
    (directory
      nginx-pid-dir
      :owner nginx-user :group nginx-group :mode "0755") 
    ; (when (= :install (get settings :action :install))
    ;         (parameter/parameters
    ;           [:nginx :owner] nginx-user
    ;           [:nginx :group] nginx-group))

    ))

(defplan nginx-settings
  "Install nginx"
  [{:keys [instance-id] :as settings}]
  (let [merged-settings (merge nginx-defaults default-site settings)]
    (assoc-settings :nginx merged-settings {:instance-id instance-id})))

(defplan mime
  []
  (remote-file
    (format "%s/mime.types" nginx-conf-dir)
    :owner "root"
    :group nginx-group
    :mode "0644"
    :template nginx-mime-conf)
  (file
    (format "%s/mime.types.default" nginx-conf-dir)
    :action :delete))


(defplan init
  "Creates a nginx init script."
  [& {:keys [instance-id] :as options}]
  (let [options (get-settings :nginx {:instance-id instance-id})]
    (service-script
      "nginx"
      :template nginx-init-script 
      :values {}
      :literal true))
  (if-not (:no-enable options)
    (service "nginx" :action :enable)))

(defplan site
  "Enable or disable a site.  Options:
   It takes in a map of site options.  For now the site data that 
   is passed in is made up of the following data
   {:sites [{:name <name-of-site>
             :action :enable | :disable defaults to :enable
             :upstreams [{}] ; Upstream servers configuration 
             :servers [{}] ; Server blocks information including locations}]
   }"
  [& {:keys [instance-id]}] 
  (let [settings (get-settings :nginx {:instance-id instance-id})
        sites (:sites settings)] 
    (doseq [site sites]
      (let 
        [
         {:keys [action name upstreams servers] :or {action :enable} :as site-options}
              site 
         available (format "%s/sites-available/%s" nginx-conf-dir name)
         enabled (format "%s/sites-enabled/%s" nginx-conf-dir name)
         contents (str-site-file site-options)
         site-fn (fn [filename]
                  (remote-file
                    filename
                    :content contents))]

        (directory (format "%s/sites-available" nginx-conf-dir))
        (directory (format "%s/sites-enabled" nginx-conf-dir))
        (when (= action :enable)
          (site-fn enabled)
          (file available :action :delete :force true))
        (when (= action :disable)
          (site-fn available)
          (file enabled :action :delete :force true))
        (when (= action :remove)
          (file available :action :delete :force true)
          (file enabled :action :delete :force true))))))

(defn nginx
  [settings]
  (api/server-spec
    :phases {
             :bootstrap (api/plan-fn 
                          (automated-admin-user/automated-admin-user))
             :settings (api/plan-fn (nginx-settings settings))
             :configure (api/plan-fn ;(install-nginx)
                                     (init)
                                     (mime)
                                     (site))
             :nginx-restart (api/plan-fn (service "nginx" :action :restart))}))

