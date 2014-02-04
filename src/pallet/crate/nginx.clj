(ns pallet.crate.nginx
  "Crate for nginx management functions"
  (:require
   [pallet.action :as action]
   [pallet.action.directory :as directory]
   [pallet.action.exec-script :as exec-script]
   [pallet.action.file :as file]
   [pallet.action.package :as package]
   [pallet.action.remote-file :as remote-file]
   [pallet.action.service :as service]
   [pallet.action.user :as user]
   [pallet.argument :as argument]
   [pallet.crate.rubygems :as rubygems]
   [pallet.parameter :as parameter]
   [pallet.stevedore :as stevedore]
   [pallet.strint :as strint]
   [pallet.template :as template]
   [pallet.utils :as utils]
   [clojure.string :as string])
  (:use
   pallet.thread-expr
   [pallet.script.lib :only [tmp-dir]]))

(def src-packages
     ["libpcre3" "libpcre3-dev" "libssl" "libssl-dev"])

(def nginx-md5s
     {"0.7.65" "abc4f76af450eedeb063158bd963feaa"})

(defn ftp-path [version]
  (format "http://sysoev.ru/nginx/nginx-%s.tar.gz" version))

(def nginx-conf-dir "/etc/nginx")
(def nginx-install-dir "/opt/nginx")
(def nginx-log-dir "/var/log/nginx")
(def nginx-pid-dir "/var/run/nginx")
(def nginx-lock-dir "/var/lock")
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
     {:version "0.7.65"
      :modules [:http_ssl_module :http_gzip_static_module]})

(def nginx-default-conf
     {:gzip "on"
      :gzip_http_version "1.0"
      :gzip_comp_level "2"
      :gzip_proxied "any"
      :gzip_types ["text/plain"
                   "text/html"
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

(def nginx-default-site
     {:listen "80"
      :server_name "localhost"
      :access_log (str nginx-log-dir "/access.log")})

(def nginx-default-location
     {:location "/"
      :root nil
      :index ["index.html" "index.htm"]
      :proxy_pass nil
      :proxy_set_headers nil
      :rails-env nil
      :passenger-enabled nil})


(defn nginx
  "Install nginx from source. Options:
     :version version-string   -- specify the version (default \"0.7.65\")
     :configuration map        -- map of values for nginx.conf"
  [session & options]
  (let [options (merge nginx-defaults (apply hash-map options))
        version (options :version)
        modules (options :version)
        basename (str "nginx-" version)
        tarfile (str basename ".tar.gz")
        tarpath (str (stevedore/script (~tmp-dir)) "/" tarfile)
        options (if (:passenger options)
                  (update-in
                   options [:add-modules]
                   (fn [m]
                     (conj (or m [])
                           (stevedore/script
                            (str @("/usr/local/bin/passenger-config --root")
                                 "/ext/nginx")))))
                  options)]

    (-> session
        (for-> [p src-packages]
          (package/package p))
        (remote-file/remote-file
         tarpath :url (ftp-path version) :md5 (get nginx-md5s version "x"))
        (user/user
         nginx-user
         :home nginx-install-dir :shell :false :create-home true :system true)
        (directory/directory nginx-install-dir :owner "root")
        (when-> (:passenger options)
          (package/package "build-essential")
          (package/package "g++")
          (package/package "libxslt1.1")
          (package/package "libssl-dev")
          (package/package "zlib1g-dev")
          (rubygems/require-rubygems)
          (rubygems/gem "passenger" :no-ri true :no-rdoc true))
        (exec-script/exec-checked-script
         "Build nginx"
         (if-not (and @(pipe (~nginx-binary -v) (grep ~version))
                      @(pipe (~nginx-binary -V)
                             (grep ~(if (:passenger options) "" "-v") "passenger")))
           (do
             (cd ~nginx-install-dir)
             (tar xz --strip-components=1 -f ~tarpath)
             ("./configure"
              ~(format "--conf-path=%s/nginx.conf" nginx-conf-dir)
              ~(format "--prefix=%s" nginx-install-dir)
              ~(format "--pid-path=%s/nginx.pid" nginx-pid-dir)
              ~(format "--error-log-path=%s/error.log" nginx-log-dir)
              ~(format "--http-log-path=%s/access.log" nginx-log-dir)
              ~(format "--lock-path=%s/nginx" nginx-lock-dir)
              ~(format "--sbin-path=%s" nginx-binary)
              ~(apply
                str
                (map #(format "--with-%s " (utils/as-string %)) (options :modules)))
              ~(apply
                str
                (map #(format "--add-module=%s " (utils/as-string %)) (options :add-modules))))
             (make)
             (make install))))
        (remote-file/remote-file
         (format "%s/nginx.conf" nginx-conf-dir)
         :template nginx-conf
         :values (reduce
                  merge {}
                  [nginx-default-conf
                   (options :configuration)
                   (strint/capture-values nginx-user nginx-group)])
         :owner "root" :group nginx-group :mode "0644")
        (directory/directory
         (format "%s/conf.d" nginx-conf-dir)
         :owner nginx-user :group nginx-group :mode "0755")
        (when-> (:passenger options)
          (remote-file/remote-file
           (format "%s/conf.d/passenger.conf" nginx-conf-dir)
           :template nginx-passenger-conf
           :values (merge
                    {:passenger-root
                     (stevedore/script
                      @("/usr/local/bin/passenger-config --root"))
                     :passenger-ruby
                     (stevedore/script
                      @("which ruby"))}
                    (:configuration options))
           :owner "root" :group nginx-group :mode "0644"))
        (directory/directory
         nginx-pid-dir
         :owner nginx-user :group nginx-group :mode "0755")
        (when-> (= :install (get options :action :install))
          (parameter/parameters
           [:nginx :owner] nginx-user
           [:nginx :group] nginx-group)))))

(defn mime
  [session]
  (->
   session
   (remote-file/remote-file
    (format "%s/mime.types" nginx-conf-dir)
    :owner "root"
    :group nginx-group
    :mode "0644"
    :template nginx-mime-conf)
   (file/file
    (format "%s/mime.types.default" nginx-conf-dir)
    :action :delete)))


(defn init
  "Creates a nginx init script."
  [session & {:as options}]
  (->
   session
   (service/init-script
    "nginx"
    :content (utils/load-resource-url
              (template/find-template nginx-init-script session))
    :literal true)
   (if-not-> (:no-enable options)
     (service/service "nginx" :action :enable))))

(defn site
  "Enable or disable a site.  Options:
:listen        -- address to listen on
:server_name   -- name
:locations     -- locations (a seq of maps, with keys :location (the apparent path), :root (the actual path),
                  :index (name of the index file), :proxy_pass (url to pass to),
                  :proxy_set_headers (map of header name to header value, either symbols or strings),
                  :passenger-enabled :rails-env)"
  [session name & {:keys [locations action] :or {action :enable} :as options}]
  (let [available (format "%s/sites-available/%s" nginx-conf-dir name)
        enabled (format "%s/sites-enabled/%s" nginx-conf-dir name)
        site (fn [session filename]
               (let [locations (string/join
                                \newline
                                (map
                                 #(template/interpolate-template
                                   nginx-location
                                   (merge nginx-default-location %)
                                   session)
                                 locations))]
                 (remote-file/remote-file
                  session
                  filename
                  :template nginx-site
                  :values (utils/map-with-keys-as-symbols
                            (reduce
                             merge {:locations locations}
                             [nginx-default-site
                              (dissoc options :locations)])))))]
    (->
     session
     (directory/directory (format "%s/sites-available" nginx-conf-dir))
     (directory/directory (format "%s/sites-enabled" nginx-conf-dir))
     (when-> (= action :enable)
             (site enabled)
             (file/file available :action :delete :force true))
     (when-> (= action :disable)
             (site available)
             (file/file enabled :action :delete :force true))
     (when-> (= action :remove)
             (file/file available :action :delete :force true)
             (file/file enabled :action :delete :force true)))))
