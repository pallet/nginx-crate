(ns pallet.crate.nginx
  "Crate for nginx management functions"
  (:require
;    [pallet.crate.rubygems :as rubygems]
    [pallet.crate :refer [admin-user defplan get-settings
                         assoc-settings]]
    [pallet.crate.automated-admin-user :as automated-admin-user]
    [pallet.actions :refer [service-script service file remote-file user
                            exec-checked-script directory package]]
    [pallet.stevedore :as stevedore]
    [pallet.template :as template]
    [pallet.api :as api]
    [pallet.strint :as strint]
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
   :rails-env nil
   :passenger-enabled nil})

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
        tarpath (str (stevedore/script (~tmp-dir)) "/" tarfile)
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
    (remote-file
      tarpath :url (ftp-path version) :md5 (get nginx-md5s version "x"))
    (user
      nginx-user
      :home nginx-install-dir :shell :false :create-home true :system true)
    (directory nginx-install-dir :owner "root")
    (when-> (:passenger settings)
            (package "build-essential")
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
          ("tar" "xz --strip-components=1 -f " ~tarpath)
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
               (map #(format "--with-%s " (utils/as-string %)) (settings :modules)))
            ~(apply
               str
               (map #(format "--add-module=%s " (utils/as-string %)) (settings :add-modules))))
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
    (when-> (:passenger settings)
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
   ; (when-> (= :install (get settings :action :install))
   ;         (parameter/parameters
   ;           [:nginx :owner] nginx-user
   ;           [:nginx :group] nginx-group))
    
    ))

(defplan nginx-settings
  "Install nginx"
  [{:keys [instance-id] :as settings}]
  (let [merged-settings 
        (merge nginx-defaults (apply hash-map settings))]
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
  [& {:as options}]
  (service-script
    "nginx"
    :url (template/get-resource nginx-init-script) 
    :literal true)
  (if-not (:no-enable options)
            (service "nginx" :action :enable)))

(defplan site
  "Enable or disable a site.  Options:
  :listen        -- address to listen on
  :server_name   -- name
  :locations     -- locations (a seq of maps, with keys :location, :root
  :index, :proxy_pass :passenger-enabled :rails-env)"
  [name & {:keys [locations action] :or {action :enable} :as options}]
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
                 (remote-file
                   filename
                   :template nginx-site
                   :values (utils/map-with-keys-as-symbols
                             (reduce
                               merge {:locations locations}
                               [nginx-default-site
                                (dissoc options :locations)])))))]

    (directory (format "%s/sites-available" nginx-conf-dir))
    (directory (format "%s/sites-enabled" nginx-conf-dir))
    (when-> (= action :enable)
            (site enabled)
            (file available :action :delete :force true))
    (when-> (= action :disable)
            (site available)
            (file enabled :action :delete :force true))
    (when-> (= action :remove)
            (file available :action :delete :force true)
            (file enabled :action :delete :force true))))

(defn nginx
  [settings]
  (api/server-spec
    :phases {
             :bootstrap (api/plan-fn 
                          (automated-admin-user/automated-admin-user)
                          (mime)
                          (init))
             :settings (api/plan-fn (nginx-settings settings))
             :configure (api/plan-fn (install-nginx))
             :nginx-restart (api/plan-fn (service "nginx" :action :restart))}))

