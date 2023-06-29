package load.datapool.todo.rest;

import load.datapool.db.H2Template;
import load.datapool.prometheus.Exporter;
import load.datapool.service.LockerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("api/v1")
public final class TodoRestController {

    private final Logger logger = LoggerFactory.getLogger(TodoRestController.class);

    private final H2Template jdbcOperations;
    private final Exporter exp;
    private final LockerService lockerService;
    private String extErrText = ""; //Extended error cause
    private int retryGetNextCount = 0;// retry if skip seq
    private int maxTableNameLength = 16;
    private int maxSequenceLength = 25;
    private int maxIndexLength = 25;
    private int maxKeyColumnLength = 1500;
    private int maxLengthNotValidRows = 25000;//Max length wrong lines for response
    private int retryGetNextMaxCount = 999999;
    private int seqCycleCache = 5;

    private final ReentrantLock restartPoolLock = new ReentrantLock();

    @Autowired
    public TodoRestController(H2Template jdbcOperations, Exporter exp, LockerService lockerService) {
        this.jdbcOperations = jdbcOperations;
        this.exp = exp;
        this.lockerService = lockerService;
    }

    @GetMapping(path = "/get-next-value")
    public ResponseEntity<String> getNextData(@RequestParam(value = "env", defaultValue = "load") String env,
                                              @RequestParam(value = "pool", defaultValue = "testpool") String pool,
                                              @RequestParam(value = "locked", defaultValue = "false") boolean locked) {
        Instant start = Instant.now();
        exp.increaseRequests(env, pool, "get-next-value");
        Long sq = -1L;
        extErrText = "";
        String fullPoolName = fullPoolName(env, pool);

        if (!lockerService.poolExist(env, pool))
            return tableNotFindResponse(fullPoolName);

        if (lockerService.isMarkedAsEmpty(env, pool))
            return tableIsEmptyResponse(fullPoolName);

        try {
            sq = jdbcOperations.queryForObject("select nextval (?)", new String[]{getSeqPrefix(env, pool) + "_rid"}, Long.class);
            ResponseEntity<String> res = ResponseEntity.ok(jdbcOperations.queryForObject("select rid, text, locked from " + fullPoolName + " where rid = ? and locked = false limit 1",
                    (resultSet, i) ->
                            new String("{\"rid\":" + resultSet.getLong("rid") +
                                    ",\"values\":" + resultSet.getString("text") +
                                    ",\"locked\":" + locked + "}"), sq));
            if (locked) {
                lockerService.lock(env, pool, sq.intValue());
            }
            exp.increaseLatency(env, pool, "get-next-value", start);
            return res;
        } catch (EmptyResultDataAccessException e) {
            if (restartPoolLock.tryLock()) {
                if (fixSequenceState(env, pool, sq)) {
                    System.out.println("Sequence reseted for " + fullPoolName + " was value = " + sq);
                } else {
                    restartPoolLock.unlock();
                    exp.increaseLatency(env, pool, "get-next-value", start);
//                    return ResponseEntity.badRequest().body(new String(e.getMessage() + "\\\n" + extErrText + "\\\n ERROR " + env + "." + pool + " sequence = " + sq));
                    return tableIsEmptyResponse(fullPoolName);
                }
                restartPoolLock.unlock();
            } else {
                try {
                    while (restartPoolLock.isLocked()) {
                        Thread.sleep(50);
                    }
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                }
            }
            exp.increaseLatency(env, pool, "get-next-value", start);
            return getNextData(env, pool, locked);
        } catch (DataAccessException e) {
            exp.increaseLatency(env, pool, "get-next-value", start);
            return ResponseEntity.badRequest().body(e.getMessage() + "\\\n" + extErrText + "\\\n ERROR " + env + "." + pool);
        }
    }

    private boolean fixSequenceState(String env, String pool, Long currentValue) {
        if (currentValue < 0) return false; //Some undefined sequence error
        try {
            int sqMax = lockerService.firstBiggerUnlockedId(env, pool, currentValue.intValue());
            Long newSqValue = 0L;
            if (sqMax == 0) //Need reset sequence to start, no values forward
            // You can change for sequence CYCLE type if slow puts are planing
            {
                 newSqValue = (long) lockerService.firstUnlockRid(env, pool);
                if (newSqValue == 0) {
                    lockerService.markAsEmpty(env, pool);
                    newSqValue = 1L;
                    jdbcOperations.update("ALTER SEQUENCE " + getSeqPrefix(env, pool) + "_rid" + " RESTART WITH ?", newSqValue);
                    return false;
                }
                jdbcOperations.update("ALTER SEQUENCE " + getSeqPrefix(env, pool) + "_rid" + " RESTART WITH ?", newSqValue);
                return true;
            } else {//need reset sequence to next available value
                newSqValue = Long.valueOf(sqMax);
                this.jdbcOperations.update("ALTER SEQUENCE " + getSeqPrefix(env, pool) + "_rid" + " RESTART WITH ?", newSqValue);
                return true;
            }

        } catch (DataAccessException e) {
            System.err.println(e.getMessage() + "\\\n" + extErrText);
            return false;
        }
    }

    @PostMapping(path = "/put-value")
    public ResponseEntity<Object> putData(@RequestParam(value = "env", defaultValue = "load") String env,
                                          @RequestParam(value = "pool", defaultValue = "testpool") String pool,
                                          @RequestParam(value = "search-key", defaultValue = "") String searchKey,
                                          @RequestBody String text) {
        Instant start = Instant.now();
        exp.increaseRequests(env, pool, "put-value");
        if (!lockerService.poolExist(env, pool)) {
            if (!createTable(env, pool)){
                if (pool.length() > maxTableNameLength)
                {
                    exp.increaseLatency(env, pool, "put-value", start);
                    return ResponseEntity.badRequest().body("Datapool name can't be longer then " +
                                                            maxTableNameLength + " simbols. Your value is " + pool);
                }
            }
        }

        try {
            if (searchKey.equals("")) {//No
                this.jdbcOperations.update("insert into " + env + "." + pool + "(rid, text, locked) values (nextval (?),?,?);", getSeqPrefix(env, pool) + "_max", text, false);
            } else {
                this.jdbcOperations.update("insert into " + env + "." + pool + "(rid, text, searchkey, locked) values (nextval (?),?,?,?);", getSeqPrefix(env, pool) + "_max", text, searchKey, false);
            }
        } catch (Exception e) {
            logger.error(e.getMessage());
            exp.increaseLatency(env, pool, "put-value", start);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
        lockerService.add(env, pool);
        exp.increaseLatency(env, pool, "put-value", start);
        return ResponseEntity.ok(new String((long) 1 + " Inserted:" + "locked = false; searchKey = " + searchKey));
    }

    @PostMapping(path = "/unlock")
    public ResponseEntity<String> unlockData(@RequestParam(value = "env", defaultValue = "load") String env,
                                             @RequestParam(value = "pool", defaultValue = "testpool") String pool,
                                             @RequestParam(value = "rid", defaultValue = "-1") String sRid,
                                             @RequestParam(value = "search-key", defaultValue = "") String searchKey,
                                             @RequestParam(value = "unlock-all", defaultValue = "false") boolean unlockAll) {
        Instant start = Instant.now();
        exp.increaseRequests(env, pool, "unlock");
        if (!lockerService.poolExist(env, pool))
            return tableNotFindResponse(fullPoolName(env, pool));

        Long rid;
        //Check rid valid value
        try {
            rid = Long.parseLong(sRid.trim());  //<-- String to long here
        } catch (NumberFormatException nfe) {
            exp.increaseLatency(env, pool, "unlock", start);
            return ResponseEntity.badRequest().body(new String(nfe.getMessage() + "\\\n" + extErrText + "\\\n ERROR " + env + "." + pool));
        }

        try {
            if (unlockAll) {
                lockerService.unlockAll(env, pool);
            } else {
                if (!sRid.equals("-1")) {
                    lockerService.unlock(env, pool, rid.intValue());
                } else {
                    if (!searchKey.equals("")) {
                        lockerService.unlock(env, pool, searchKey);
                    } else {
                        exp.increaseLatency(env, pool, "unlock", start);
                        return ResponseEntity.badRequest().body(new String("You must define one of the parameters variant:\n"
                                + "\t&unlock-all=true \n"
                                + "\t&rid=<row number> \n"
                                + "\t&search-key=<key value>"));
                    }
                }
            }
        } catch (DataAccessException e) {
            exp.increaseLatency(env, pool, "unlock", start);
            return ResponseEntity.badRequest().body(new String(e.getMessage() + "\\\n" + extErrText + "\\\n ERROR " + env + "." + pool));
        }
        exp.increaseLatency(env, pool, "unlock", start);
        return ResponseEntity.ok(new String("Unlocked successfully!"));
    }

    @GetMapping(path = "/search-by-key")
    public ResponseEntity<String> getBySearchKey(@RequestParam(value = "env", defaultValue = "load") String env,
                                                 @RequestParam(value = "pool", defaultValue = "testpool") String pool,
                                                 @RequestParam(value = "search-key", defaultValue = "") String searchKey,
                                                 @RequestParam(value = "locked", defaultValue = "false") boolean locked) {
        if (!lockerService.poolExist(env, pool))
            return tableNotFindResponse(fullPoolName(env, pool));

        Instant start = Instant.now();
        exp.increaseRequests(env, pool, "search-by-key");
        Long sq = (long) -1;
        extErrText = "";
        if (searchKey.equals("")) {
            exp.increaseLatency(env, pool, "search-by-key", start);
            return ResponseEntity.badRequest().body(new String("Undefined request parameter \"search-key\"."));
        }
        try {
            final Long[] rid = {null};
            Instant dd = Instant.now();
            // todo - долгий запрос!!!
            ResponseEntity<String> res = ResponseEntity.ok(this.jdbcOperations.queryForObject(
                    "select rid, text,searchkey, locked from " + env + "." + pool + " where searchkey = ? and locked = false limit 1",
                    (resultSet, i) -> {
                        rid[0] = resultSet.getLong("rid");
                        exp.increaseLatency(env, pool, "search-by-key", start);
                        return new String("{\"rid\":" + resultSet.getLong("rid") +
                                ",\"searchkey\":\"" + resultSet.getString("searchkey") + "\"" +
                                ",\"values\":" + resultSet.getString("text") +
                                ",\"locked\":" + locked + "}");
                    }
                    , searchKey));

            if (locked) {
                System.out.println("Try update locked value.");
                lockerService.lock(env, pool, rid[0].intValue());
            }
            exp.increaseLatency(env, pool, "search-by-key", start);
            return res;

        } catch (DataAccessException e) {
            exp.increaseLatency(env, pool, "search-by-key", start);
            return ResponseEntity.badRequest().body(new String(e.getMessage() + "\\\n" + extErrText + "\\\n ERROR " + env + "." + pool + " searchkey = " + searchKey));
        }
    }

    @PostMapping(path = "/upload-csv-as-json")
    public ResponseEntity<String> uploadCSV(@RequestParam(value = "env", defaultValue = "load") String env,
                                            @RequestParam(value = "pool", defaultValue = "testpool") String pool,
                                            @RequestParam(value = "delimiter", defaultValue = ",") String delim,
                                            @RequestParam(value = "override", defaultValue = "false") boolean override,
                                            @RequestParam(value = "with-headers", defaultValue = "true") boolean withHeaders,
                                            @RequestParam("file") MultipartFile file) {
        Instant start = Instant.now();
        exp.increaseRequests(env, pool, "upload-csv-as-json");
        final String fullPoolName = fullPoolName(env, pool);

        if (!withHeaders) {
            exp.increaseLatency(env, pool, "upload-csv-as-json", start);
            return ResponseEntity.badRequest().body(new String("With out headers csv files not supported!"));
        }
        if (file.isEmpty()) {
            exp.increaseLatency(env, pool, "upload-csv-as-json", start);
            return ResponseEntity.badRequest().body(new String("File is empty!"));
        }
        if (override) {
            if (lockerService.poolExist(env, pool)){
                exp.removeMetrics(env, pool);
                dropTable(env, pool);
            }
        }

        if (!createTable(env, pool)){
            if (pool.length() > maxTableNameLength)
            {
                exp.increaseLatency(env, pool, "upload-csv-as-json", start);
                return ResponseEntity.badRequest().body("Datapool name can't be longer then " +
                        maxTableNameLength + " simbols. Your value is " + pool);
            }
        }

        // parse CSV file
        BufferedReader reader = null;
        String notValidRows = "";

        try {
            reader = new BufferedReader(new InputStreamReader(file.getInputStream()));
            String header = reader.readLine();
            if (header == null) {
                exp.increaseLatency(env, pool, "upload-csv-as-json", start);
                return ResponseEntity.badRequest().body(new String("Not found rows into file!"));
            }
            int cntColumns = header.split(delim).length;
            String headers[] = header.split(delim);

            String line = reader.readLine();

            String json = "";
            int successCnt = 0;
            while (line != null) {
                String values[] = line.split(delim);
                if (values.length != cntColumns && notValidRows.length() < maxLengthNotValidRows) {
                    notValidRows += line + "\n";
                } else {
                    json = "{";
                    for (int i = 0; i < cntColumns; i++) {
                        json += "\"" + headers[i] + "\":\"" + values[i] + "\",";
                    }
                    if (json.endsWith(",")) {
                        json = json.substring(0, json.length() - 1);
                    }
                    json += "}";
                    this.jdbcOperations.update("insert into " + env + "." + pool + "(rid, text, locked) values (nextval (?),?,?);", getSeqPrefix(env, pool) + "_max", json, false);
                    successCnt++;
                }
                // read next line
                line = reader.readLine();
            }
            reader.close();

            if (override) {
                lockerService.putPool(env, pool, successCnt);
            }else{
                lockerService.add(env, pool, successCnt);
            }

            if (!notValidRows.equals("")) {
                exp.increaseLatency(env, pool, "upload-csv-as-json", start);
                return ResponseEntity.badRequest().body("Success uploaded rows: " + successCnt + ". Wrong parse some lines:\n" + notValidRows.substring(0, Math.min(notValidRows.length(), maxLengthNotValidRows)));
            }
            exp.increaseLatency(env, pool, "upload-csv-as-json", start);
            return ResponseEntity.ok().body("Ok! Uploaded rows: " + successCnt);
        } catch (Exception ex) {
            exp.increaseLatency(env, pool, "upload-csv-as-json", start);
            return ResponseEntity.badRequest().body("File parse exeption: " + ex.getMessage() + "\n" + notValidRows);
        }
    }

    private ResponseEntity<String> tableNotFindResponse(String poolName) {
        return ResponseEntity.badRequest().body("Table " + poolName + " not found in lockerService");
    }

    private ResponseEntity<String> tableIsEmptyResponse(String poolName) {
        return ResponseEntity.badRequest().body("Incorrect result size: expected 1, actual 0\\\n" +
                "May be table is empty. \\\n" +
                " ERROR " + poolName);
    }

    private String getSeqPrefix(String env, String pool) {
        String seqPrefix = new String(env + "." + "seq_" + pool);
        seqPrefix = seqPrefix.substring(0, seqPrefix.length() <= maxSequenceLength ? seqPrefix.length() : maxSequenceLength);
        return seqPrefix;
    }

    private boolean createTable(String env, String pool) {
        if (pool.length() > maxTableNameLength)
        {
            logger.error("Datapool name can't be longer then {} simbols. Your value is {}", maxTableNameLength, pool);
            return false;
        }

        if (lockerService.poolExist(env, pool)) {
            logger.info("Datapool {} already exists.", pool);
            return true;
        }
        lockerService.putPool(env, pool);
        try {
        	
        	this.jdbcOperations.execute("create schema if not exists "+env+";");
            this.jdbcOperations.execute("" +
                    "create table " + env + "." + pool +
                    "(" +
                    "        rid           bigint primary key," +
                    "        text        varchar(32000)," +
                    "        searchkey        varchar(" + maxKeyColumnLength + ")," +
                    "        locked         boolean   not null default false" +
                    ");");
            String indexPrefix = new String("idx_" + env + "" + pool);
            indexPrefix = indexPrefix.substring(0, indexPrefix.length() <= maxIndexLength ? indexPrefix.length() : maxIndexLength);
            this.jdbcOperations.execute("CREATE INDEX " + indexPrefix + "_rid ON  " + env + "." + pool + " (rid);");
            this.jdbcOperations.execute("CREATE INDEX " + indexPrefix + "_lc ON  " + env + "." + pool + " (locked);");
            this.jdbcOperations.execute("CREATE INDEX " + indexPrefix + "_sk ON  " + env + "." + pool + " (searchkey);");


            this.jdbcOperations.execute("DROP SEQUENCE if exists " + getSeqPrefix(env, pool) + "_max;");
            this.jdbcOperations.execute("DROP SEQUENCE if exists " + getSeqPrefix(env, pool) + "_rid;");
            this.jdbcOperations.execute("CREATE SEQUENCE " + getSeqPrefix(env, pool) + "_max MINVALUE 0 NO CYCLE CACHE " + seqCycleCache + " INCREMENT 1 START 1;"); //for append insert
            this.jdbcOperations.execute("CREATE SEQUENCE " + getSeqPrefix(env, pool) + "_rid MINVALUE 0 NO CYCLE CACHE " + seqCycleCache + " INCREMENT 1 START 1;"); //for get value

            return true;
        } catch (DataAccessException e) {
            System.err.println(e.getMessage());
            return false;
        }
    }

    private boolean dropTable(String env, String pool) {
        lockerService.deletePool(env, pool);
        try {
            this.jdbcOperations.execute("drop table if exists " + env + "." + pool + ";");
            String indexPrefix = new String("idx_" + env + "" + pool);
            indexPrefix = indexPrefix.substring(0, indexPrefix.length() <= maxIndexLength ? indexPrefix.length() : maxIndexLength);
            this.jdbcOperations.execute("drop INDEX if exists " + indexPrefix + "_rid");
            this.jdbcOperations.execute("drop INDEX if exists " + indexPrefix + "_lc");
            this.jdbcOperations.execute("DROP SEQUENCE if exists " + getSeqPrefix(env, pool) + "_max;");
            this.jdbcOperations.execute("DROP SEQUENCE if exists " + getSeqPrefix(env, pool) + "_rid;");
            return true;
        } catch (DataAccessException e) {
            System.err.println(e.getMessage());
            return false;
        }
    }

    private String fullPoolName(String env, String pool) {
        return env + "." + pool;
    }

    @DeleteMapping(path = "/drop-pool")
    public ResponseEntity<String> deletePool(@RequestParam(value = "env", defaultValue = "load") String env,
                                             @RequestParam(value = "pool", defaultValue = "testpool") String pool) {
        if (lockerService.poolExist(env, pool)) {
            try {
                dropTable(env, pool);
                exp.removeMetrics(env, pool);
                return ResponseEntity.ok().body("Pool deleted successfully!");
            } catch (Exception ex) {
                System.err.println(ex.getMessage());
                return ResponseEntity.badRequest().body("Failed to delete pool");
            }
        } else {
            return ResponseEntity.badRequest().body("Pool not found");
        }
    }
    
    // reset sequence to 1 (zero point of pool)
    @GetMapping(path = "/resetseq")
    public ResponseEntity<Object> resetseq(@RequestParam(value = "env", defaultValue = "load") String env,
                                          @RequestParam(value = "pool", defaultValue = "testpool") String pool) {
        Instant start = Instant.now();
        exp.increaseRequests(env, pool, "resetseq");
        Long newSqValue = 1L;

        try {
        	jdbcOperations.update("ALTER SEQUENCE " + getSeqPrefix(env, pool) + "_rid" + " RESTART WITH ?", newSqValue);
        } catch (Exception e) {
            logger.error(e.getMessage());
            exp.increaseLatency(env, pool, "resetseq", start);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
        exp.increaseLatency(env, pool, "resetseq", start);
        return ResponseEntity.ok(new String("Row id sequence has been reseted for pool "+pool));
    }

    
    
}