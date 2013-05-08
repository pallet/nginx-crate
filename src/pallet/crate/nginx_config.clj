(ns pallet.crate.nginx-config)


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
(defmethod str-location-line :access-log [key vals]
  (format "\t%s %s;\n" (convert-key-to-nginx key) (join-with-space vals))) 
(defmethod str-location-line :default [key val]
  (format "\t\t%s %s;\n" (convert-key-to-nginx key) val))

;; DEFINING SERVERLINE MULTI-METHODS
(defmulti str-server-line (fn [key val] key))
;; vals for this should e an array of strings to print out
(defmethod str-server-line :access-log [key vals]
  (format "\t%s %s;\n" (convert-key-to-nginx key) (join-with-space vals))) 
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
(defmethod str-server-line :default [key vals]
  (format "\t%s %s;\n" (convert-key-to-nginx key) vals))

(defmulti str-upstream-line (fn [key val] key))
;; Ignore the val for ip_hash
(defmethod str-upstream-line :ip-hash [key val]
  (format "\t%s;\n" (convert-key-to-nginx key)))
;; Ignore the val for least-conn 
(defmethod str-upstream-line :least-conn [key val]
  (format "\t%s;\n" (convert-key-to-nginx key)))
(defmethod str-upstream-line :keepalive [key val]
  (format "\t%s %s;\n" (convert-key-to-nginx key) val))
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

