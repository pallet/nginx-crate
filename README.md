# Pallet crate for nginx

This a crate to install and run nginx via [Pallet](http://pallet.github.com/).  This crate is heavily based off of
Hugo Duncans nginx-crate.  The main differences with this one being...
* The crate is updated to work with Pallet 0.8.
* The crate has been modified to use a much more recent version of nginx.
* The crate's configurations are much more data driven.
* The crate no longer supports passenger.

## Usage
Artifacts are released [released to Clojars](https://clojars.org/strad/datomic-crate).  If you are using Maven, add the following definition to your `pom.xml`:
```xml
<repository>
 <id>clojars.org</id>
 <url>http://clojars.org/repo</url>
</repository>
```

### The Most Recent Release
With Leiningen
```clojure
  [org.clojars.strad/nginx-crate "0.8.0"]
```

With Maven
```xml
   <dependency>
      <groupId>org.clojars.strad</groupId>
      <artifactId>nginx-crate</artifactId>
      <version>0.8.0</version>
   </dependency>
```

## Server Spec
The nginx crate defines the nginx function, that takes a settings map and returns a default server-spec for
installing nginx.  You can use this in a `group-spec` or `server-spec`.  The phases defined are
`settings`, `install`, `configure`, `nginx-restart`.   The configure phase is where you can modify
the upstream blocks and other configuration files.

```clj
(group-spec "my-node-with-nginx"
   :extends [(pallet.crate.nginx/nginx {})])
```

For instance the below could be passed in as settings.  It will enable the default.site.
It basically creates a reverse proxy for http and https connections to a server.  I use
this configuration for httpkit.

(def http-server-config
  {:sites [{:action :enable
    :name "default.site"
    :upstreams [{:lines [{:server "127.0.0.1:8080"}
                         {:keepalive 32}]
                 :name "http_backend"}]
    :servers [
              {:access-log ["/var/log/nginx/app.access.log"] 
               :locations [{:path "/"
                           :proxy-pass "http://http_backend"
                           :proxy-http-version "1.1"
                           :proxy-set-header [{:Connection "\"\""},
                                              {:X-Forwarded-For 
                                               "$proxy_add_x_forwarded_for"}, 
                                               {:Host "$http_host"}]}]}
              {:listen "443"
               :ssl "on"
               :ssl_certificate "/etc/ssl/certs/myssl.crt"
               :ssl_certificate_key "/etc/ssl/private/myssl.key"
               :keepalive_timeout "70" 
               :locations [{:path "/"
                           :proxy-pass "http://http_backend"
                           :proxy-http-version "1.1"
                           :proxy-set-header [{:Connection "\"\""},
                                              {:X-Forwarded-For 
                                               "$proxy_add_x_forwarded_for"}, 
                                               {:Host "$http_host"}]}]}]}]})




## Support

## License

Licensed under [EPL](http://www.eclipse.org/legal/epl-v10.html)

Copyright 2010, 2011, 2012, 2013 Hugo Duncan.
