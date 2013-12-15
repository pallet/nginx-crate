(ns pallet.crate.nginx
  "Crate for nginx management functions"
  (:require
    ;    [pallet.crate.rubygems :as rubygems]
    [pallet.crate :refer [admin-user defplan get-settings
                          assoc-settings defmethod-plan]]
    [pallet.crate-install :as crate-install]
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
    [pallet.version-dispatch :refer [defmethod-version-plan
                                     defmulti-version-plan]]
    [clojure.string :as string]
    [pallet.crate.nginx-config :as config])
  (:use
    pallet.thread-expr
    [pallet.script.lib :only [tmp-dir]]))

(defmulti src-packages (fn [os] os))
(defmethod src-packages :centos [os]
  ["gcc" "gcc-c++" "kernel-devel" "pcre" "pcre-devel" "zlib"
   "zlib-devel" "openssl-devel" "openssl" "redhat-lsb"])
(defmethod src-packages :default [os]
  ["libpcre3" "libpcre3-dev" "libssl1.0.0" "libssl-dev" "make" "build-essential"])



(defn a-default-site 
  "Returns a log-dir for nginx"
  [{:keys [instance-id nginx-log-dir] :as options}]
  (let [ 
        site  { :sites [{ :action :enable 
                         ;; We want all sites to have the suffix .site
                         ;; otherwise the md5 stuff will cause the md5 stuff
                         ;; to load and nginx will not be happy
                         :name "default.site"
                         :upstreams [] 
                         :servers [{:server-name "localhost"
                                    :listen "80"
                                    :access-log  ["%s/access.log"] 
                                    :locations [{:path "/"
                                                 :index ["index.html" "index.htm"]}]}]}]}]
    (update-in site [:sites 0 :servers 0 :access-log] 
               (fn [s]
                 (let [ fmt (format (first s) nginx-log-dir)]
                   [fmt])))))

(defmulti get-init-script (fn [os-family] os-family))
(defmethod get-init-script :centos [os-family] "crate/nginx/nginx.centos")
(defmethod get-init-script :default [os-family] "crate/nginx/nginx")
(defn default-settings [options]
  (let [
        os (pallet.crate/os-family)
        settings 
        {:version "1.2.6"
         :user "www-data"
         :group "www-data"
         :install-strategy ::download
         :modules [:http_ssl_module]
         :nginx-conf-dir "/etc/nginx"
         :nginx-conf-wildcard "*.conf"
         :nginx-sites-wildcard "*.site"
         :nginx-pid-dir "/var/run"
         :dist-url "http://nginx.org/download/nginx-%s.tar.gz" 
         :nginx-log-dir "/var/log/nginx"
         :nginx-install-dir "/opt/nginx"
         :nginx-binary "/usr/local/sbin/nginx"
         :nginx-init-script (get-init-script os)
         :nginx-conf "crate/nginx/nginx.conf"
         :nginx-passenger-conf "crate/nginx/passenger.conf"
         :nginx-mime-conf "crate/nginx/mime.types"
         :nginx-default-conf  {:gzip "on"
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
                               :server_names_hash_bucket_size 64}}
        site (a-default-site (merge settings options))]
    (merge settings site)))

(defn url
  [{:keys [dist-url version] :as settings}]
  {:pre [dist-url version]}
  (format dist-url version))

(defmulti-version-plan settings-map [version settings])

(defmethod-version-plan settings-map {:os :linux}
  [os os-version version settings]
  (cond
    (= :packages (:install-strategy settings))
    (assoc settings :packages (or (:packages settings) ["nginx"]))

    (= :package-source (:install-strategy settings)) 
    (-> settings
        (assoc :packages (or (:packages settings)
                             ["nginx-full"]))
        (assoc :package-source (or (:package-source settings)
                                   {:name "debian-backports"
                                    :aptitude {:url "http://backports.debian.org/debian-backports"
                                               :release "squeeze-backports"
                                               :scopes ["main"]}})))

    :else (assoc settings
                 :install-strategy ::download
                 :remote-file {:url (url settings)
                               :tar-options "xz"})))

(defplan remove-default-site
  "This will remove the file default in the sites-enabled directory.
  This is done because we are naming our site files <x>.site but
  in the nginx conf file including *.site.  This will match the default
  file as well as the default.site md5 file items and we will get a bunch of warnings
  for duplicate definitions when starting nginx.  To get around this fact
  after installation of nginx we delete the default site file"
  [& {:keys [instance-id] :as options}]
  (let [settings (get-settings :nginx {:instance-id instance-id})
        {:keys [nginx-conf-dir]} settings
        ]
    (file (format "%s/sites-enabled/default" nginx-conf-dir) :action :delete :force :true)))


(defn update-nginx-conf-file
  "Will update the nginx conf file with the settings specified"
  [settings]
  (let [ {:keys [nginx-conf-dir nginx-default-conf user group
           nginx-pid-dir nginx-conf-dir nginx-log-dir
                 nginx-conf nginx-conf-wildcard
                 nginx-sites-wildcard]} settings]
    (pallet.actions/remote-file
      (format "%s/nginx.conf" nginx-conf-dir)
      :template nginx-conf
      :values (reduce
                merge {}
                [nginx-default-conf
                 (settings :configuration)
                 (strint/capture-values user group nginx-pid-dir 
                                        nginx-conf-dir nginx-log-dir
                                        nginx-conf-wildcard
                                        nginx-sites-wildcard)])
      :owner user :group group :mode "0644")))

(defplan install-nginx-via-download
  "Install nginx from source. Options:
  :version version-string   -- specify the version (default \"0.7.65\")
  :configuration map        -- map of values for nginx.conf"
  [& {:keys [instance-id] :as options}]
  (let [settings (get-settings :nginx {:instance-id instance-id})
        {:keys [user group nginx-conf-dir nginx-pid-dir 
                nginx-install-dir nginx-binary remote-file
                nginx-log-dir nginx-conf nginx-default-conf
                nginx-passenger-conf 
                ]} settings
        version (settings :version)
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
                   settings)
        os (pallet.crate/os-family)]
    (doseq [p (src-packages os)]
      (package p))
    (pallet.actions/user
        user
        :home nginx-install-dir :shell :false :create-home true :system true)
    (utils/apply-map remote-directory nginx-install-dir remote-file)
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
     
    (directory
      (format "%s/conf.d" nginx-conf-dir)
      :owner user :group group :mode "0755") 
    (when (:passenger settings)
      (pallet.actions/remote-file
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
        :owner "root" :group group :mode "0644")) 
    (directory
      nginx-pid-dir
      :owner user :group group :mode "0755") 
    ))

(defplan init
  "Creates a nginx init script."
  [& {:keys [instance-id] :as options}]
  (let [options (get-settings :nginx {:instance-id instance-id})
        {:keys [nginx-conf-dir nginx-pid-dir nginx-init-script
                nginx-binary]} options
        ]
    (service-script
      "nginx"
      :template nginx-init-script 
      :values {:nginx-conf-dir nginx-conf-dir
               :nginx-pid-dir nginx-pid-dir
               :nginx-binary nginx-binary
               }
      :literal true))
  (if-not (:no-enable options)
    (service "nginx" :action :enable)))

(defplan settings
  "Set-up settings for nginx"
  [{:keys [] :as settings} 
   & {:keys [instance-id] :as options}]
  (let [def-settings (default-settings settings)
        merged-settings (merge def-settings settings)
        merged-settings (settings-map (:version merged-settings) merged-settings)
        ]
    (assoc-settings :nginx merged-settings {:instance-id instance-id})))

(defplan mime
  "Install the mime file"
  [& {:keys [instance-id] :as info}]
  (let [
        settings (get-settings :nginx {:instance-id instance-id})
        {:keys [group nginx-conf-dir nginx-mime-conf user
                ]} settings
        ]
    (remote-file
      (format "%s/mime.types" nginx-conf-dir)
      :owner user
      :group group
      :mode "0644"
      :template nginx-mime-conf)
    (file
      (format "%s/mime.types.default" nginx-conf-dir)
      :action :delete)))


(defmethod-plan crate-install/install ::download
  [facility instance-id]
  (install-nginx-via-download :instance-id instance-id)
  (init))

(defplan install
  "Install nginx"
  [{:keys [instance-id]}]
  (let [{:keys [owner group] :as settings}
        (get-settings :nginx {:instance-id instance-id})
        ret (crate-install/install :nginx instance-id)
        ]
    (remove-default-site)
    (update-nginx-conf-file settings)
    ret))


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
        sites (:sites settings)
        nginx-conf-dir (:nginx-conf-dir settings)
        ] 
    (doseq [site sites]
      (let 
        [
         {:keys [action name upstreams servers] :or {action :enable} :as site-options}
         site 
         available (format "%s/sites-available/%s" nginx-conf-dir name)
         enabled (format "%s/sites-enabled/%s" nginx-conf-dir name)
         contents (config/str-site-file site-options)
         site-fn (fn [filename]
                   (remote-file
                     filename
                     :content contents
                     :literal true))]

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
  "Defines some default nginx phases"
  [settings & {:keys [instance-id] :as options}]
  (api/server-spec
    :phases {
             :settings (api/plan-fn (pallet.crate.nginx/settings settings))
             :install (api/plan-fn (install options)
                                   (mime))
             :configure (api/plan-fn (site))
             :run (api/plan-fn
                    (service "nginx" :action :start))
             :stop (api/plan-fn
                     (service "nginx" :action :stop))
             :restart (api/plan-fn (service "nginx" :action :restart))}))

