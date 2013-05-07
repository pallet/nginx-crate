# Release notes

## nginx 0.8.1
- Changes nginx-restart to restart.  *Breaking Change*
- Fixed a bug such that the nginx server would not restart after being installed.
- Allows installation from packages
- Follows new convention around install strategies and naming conventions
- Moved all the config data into a settings map
- Fixed some bugs that were not uncovered yet but would cause things like the log directory to point at different directories than it should based upon the configuration.

## nginx 0.8.0 

- First version that works properly with pallet 0.8.0.  Passenger support is not added.

