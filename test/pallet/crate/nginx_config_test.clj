(ns pallet.crate.nginx-config-test
  (:require [clojure.test :refer [deftest testing is]]
            [pallet.crate.nginx-config :as config]))

(deftest str-location-line-test
  (let [default-line (config/str-location-line :autoindex "on")
        index-line (config/str-location-line :index ["index.html" "index.htm"])        
        proxy-line (config/str-location-line :proxy-set-header [{:Host "localhost"}
                                                         {:More "more"}])]
    (is (= default-line "\t\tautoindex on;\n"))
    (is (= index-line "\t\tindex index.html index.htm;\n"))
    (is (= proxy-line 
           "\t\tproxy_set_header Host localhost;\n\t\tproxy_set_header More more;\n"))))

(deftest str-server-line-test
  (let [
        access-line (config/str-server-line :access-log ["data1" "data2"])
        default-line (config/str-server-line :server-name "mine")
        locations-block (config/str-server-line 
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
  (let [default-line (config/str-upstream-line :server "127.0.0.1")]
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
    (is (= (config/str-upstream-blocks data)
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
    (is (= (config/str-server-blocks data)
           (str "server {\n"
                "\taccess_log data1 data2;\n"
                "\tserver_name mine;\n"
                "}\n")))))

(deftest str-site-file-test
  (let [
        data {:upstreams [{:name "http_backend" :lines [{:server "127.0.0.1:80"}]}]
              :servers [{:access-log ["data1"]}]
              }]
    (is (= (config/str-site-file data)
           (str "upstream http_backend {\n"
                "\tserver 127.0.0.1:80;\n"
                "}\n"
                "server {\n"
                "\taccess_log data1;\n"
                "}\n"
                )))))

