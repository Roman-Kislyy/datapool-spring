# Changelog
Service for creating and utilizing test data pools via REST API.

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