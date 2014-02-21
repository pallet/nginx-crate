(ns pallet.crate.nginx-test
  (:require
    [pallet.crate.nginx :refer [default-settings]]
    [pallet.build-actions :as build-actions]
    [pallet.actions :refer [directory remote-file file]]
    [pallet.crate :refer [assoc-settings]]
    [clojure.test :refer [deftest is testing]]))


(def site-test-data
  {:sites [{ :action :enable 
            :name "default"
            :upstreams [] 
            :servers [{:server-name "testhost"
                       :listen "80"
                       :access-log  ["/var/log/nginx/access.log"] 
                       :locations [{:path "/"
                                    :index ["index.html" "index.htm"]}]}]}]})


(def config-data
  {:nginx-log-dir "/var/log/test"})

(deftest default-settings-test
  ;; Redefine the os-family function as we don't have a valid session
  ;; to grab this from
  (with-redefs [pallet.crate/os-family (fn [] :centos)] 
    (let [settings (default-settings config-data)]
     (is (= (first (get-in settings [:sites 0 :servers 0 :access-log])) 
            (str (:nginx-log-dir config-data) "/access.log"))))))


(comment (deftest site-test
   ( let [
          item1 (first
                 (build-actions/build-actions
                  {:phase-context "site"}
                  (directory "/etc/nginx/sites-available")
                  (directory "/etc/nginx/sites-enabled")
                  (remote-file
                   "/etc/nginx/sites-enabled/default"
                   :content "server {\n\tserver_name testhost;\n\tlisten 80;\n\taccess_log /var/log/nginx/access.log;\n\tlocation / {\n\t\tindex index.html index.htm;\n\t}\n}\n"
                   )
                  (file
                   "/etc/nginx/sites-available/default" :action :delete :force true)))  
          item2 (first
                 (build-actions/build-actions
                  {:server {:group-name :n :image {:os-family :ubuntu}}}
                  (pallet.crate.nginx/settings site-test-data)))] 
     (println "item1 ===========")
     (println item1)
     (println "item2 ===========")
     (println item2)

     (is (= item1 item2)))))

