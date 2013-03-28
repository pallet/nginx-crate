(ns pallet.crate.nginx-test
  (:use pallet.crate.nginx)
  (:require
   [pallet.build-actions :as build-actions]
   [pallet.actions :refer [directory remote-file file]]
    [pallet.crate :refer [assoc-settings]]
   [clojure.test :refer [deftest is testing]]) 
  (:use pallet.test-utils))


(deftest str-location-line-test
  (let [default-line (str-location-line :autoindex "on")
        index-line (str-location-line :index ["index.html" "index.htm"])        
        proxy-line (str-location-line :proxy-set-header [{:Host "localhost"}
                                                        {:More "more"}])]
    (is (= default-line "\t\tautoindex on;\n"))
    (is (= index-line "\t\tindex index.html index.htm;\n"))
    (is (= proxy-line "\t\tproxy_set_header Host localhost;\n\t\tproxy_set_header More more;\n"))))

(deftest str-server-line-test
  (let [
        access-line (str-server-line :access-log ["data1" "data2"])
        default-line (str-server-line :server-name "mine")
        locations-block (str-server-line 
                         :locations [{:autoindex "on"
                                      :path "/"
                                       :index ["index.html" 
                                               "index.htm"]}])]
    (is (= access-line "\taccess_log data1 data2;\n"))
    (is (= default-line "\tserver_name mine;\n"))
    (is (= locations-block (str "\tlocation / {\n"
                               "\t\tautoindex on;\n"
                               "\t\tindex index.html index.htm;\n"
                               "\t}\n")))))

(deftest str-upstream-line-test
  (let [default-line (str-upstream-line :server "127.0.0.1")]
    (is (= default-line "\tserver 127.0.0.1;\n"))))

(deftest str-upstream-blocks-test
  (let [
        data [{:lines [{:server "127.0.0.180"}
                       { :ip-hash ""}
                        { :least-conn ""}
                        { :keepalive "32"} 
                        ] :name "http_backend"}
              {:lines [{:server "127.0.0.1:90"}] :name "http_backend2"}
              ]]
    (is (= (str-upstream-blocks data)
           (str "upstream http_backend {\n"
                "\tserver 127.0.0.180;\n"
                "\tip_hash;\n"
                "\tleast_conn;\n"
                "\tkeepalive 32;\n"
                "}\n"
                "upstream http_backend2 {\n"
                "\tserver 127.0.0.1:90;\n"
                "}\n")))))

(deftest str-server-blocks-test
  (let [
        data [{:access-log ["data1" "data2"]
               :server-name "mine"
               }]]
  (is (= (str-server-blocks data)
         (str "server {\n"
              "\taccess_log data1 data2;\n"
              "\tserver_name mine;\n"
              "}\n")))))

(deftest str-site-file-test
  (let [
        data {:upstreams [{:name "http_backend" :lines [{:server "127.0.0.1:80"}]}]
              :servers [{:access-log ["data1"]}]
              }]
  (is (= (str-site-file data)
         (str "upstream http_backend {\n"
              "\tserver 127.0.0.1:80;\n"
              "}\n"
              "server {\n"
              "\taccess_log data1;\n"
              "}\n"
              )))))


(def site-test-data
  {:sites [{ :action :enable 
           :name "default"
           :upstreams [] 
           :servers [{:server-name "testhost"
                      :listen "80"
                      :access-log  [(str nginx-log-dir "/access.log")] 
                      :locations [{:path "/"
                                   :index ["index.html" "index.htm"]}]}]}]})
(deftest site-test
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
             (nginx-settings site-test-data)
             (site)))] 
    (println "item1 ===========")
    (println item1)
    (println "item2 ===========")
    (println item2)

    (is (= item1 item2))))

