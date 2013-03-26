;;; Pallet project configuration file

(require
 '[pallet.crate.nginx-test
   :refer [nginx-test-spec]]
 '[pallet.crates.test-nodes :refer [node-specs]])

(defproject nginx-crate
  :provider node-specs                  ; supported pallet nodes
  :groups [(group-spec "nginx-test"
             :extends [with-automated-admin-user
                       nginx-test-spec]
             :roles #{:live-test :default :nginx})])

