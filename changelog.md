# Changelog
Service for creating and utilizing test data pools via REST API.

## [1.7.8]

### Added

- Optimization of method for download datapool content/api/v2/download/csv
- New URL connection options

## [1.7.7-alfa]

### Added

- Create api method for download datapool content/api/v2/download/csv for debug version

## [1.7.6]

### Added

- Create viewer database user for web console http://<host:port>/h2-console/login.do
- Make parameter db.maxTextColumnLength into application.properties

## [1.7.5]

### Changed

- Checking unlocked rows when frequent puts & gets

## [1.7.4]

### Changed

- Mute deadlock when happens DB and application cache collision

## [1.7.3]

### Changed

- Fix incorrect load big locked datapool

## [1.7.2]

### Changed

- Added details for prometheus logging
- Disable auto load datapool in BaseLockerService.poolExist
- Add mutex in /put-value when creating new datapool

## [1.7.1]

### Changed

- Fixed override=false in /upload-csv-as-json
- Mark empty pool in locker
- Fixed next biggest id
- Check poolExist only in app cache

## [1.6.5]

### Changed

- Fixed next sequence value

## [1.6.4]

### Added

- Resetting datapool sequence has done. /resetseq

### Changed

- Threads properties was optimized

## [1.6.3]

### Changed

- Add maxIdle logging 


## [1.6.2]

### Changed

- Fixed  reading from application.properties 


## [1.6.1]

### Changed

- Add table name maximum length
- Done drop datapool