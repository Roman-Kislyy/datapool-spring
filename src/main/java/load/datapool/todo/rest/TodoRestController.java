package load.datapool.todo.rest;

import load.datapool.db.H2Template;
import load.datapool.prometheus.Exporter;
import load.datapool.service.LockerService;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("api/v1")
@NoArgsConstructor
public final class TodoRestController {

    @Autowired
    private H2Template jdbcOperations;
    @Autowired
    private Exporter exp;
    @Autowired
    private LockerService lockerService;
    private String extErrText = ""; //Extended error cause
    private int retryGetNextCount = 0;// retry if skip seq
    private int maxSequenceLength = 25;
    private int maxIndexLength = 25;
    private int maxKeyColumnLength = 1500;
    private int maxLengthNotValidRows = 25000;//Max length wrong lines for response
    private int retryGetNextMaxCount = 999999;
    private int seqCycleCache = 5;


    @GetMapping("/initLocks")
    public ResponseEntity<String> initLocks() {
        lockerService.initLocks();
        return ResponseEntity.ok("loaded");
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

        try {
            sq = jdbcOperations.queryForObject("select nextval (?)", new String[]{getSeqPrefix(env, pool) + "_rid"}, Long.class);
            ResponseEntity<String> res = ResponseEntity.ok(jdbcOperations.queryForObject("select rid, text, locked from " + fullPoolName + " where rid = ? and locked = false limit 1",
                    (resultSet, i) ->
                            new String("{\"rid\":" + resultSet.getLong("rid") +
                                    ",\"values\":" + resultSet.getString("text") +
                                    ",\"locked\":" + locked + "}"), sq));
            if (locked) {
                lockerService.lock(fullPoolName, sq.intValue());
            }
            exp.increaseLatency(env, pool, "get-next-value", start);
            return res;

        } catch (EmptyResultDataAccessException e) {
            if (fixSequenceState(env, pool, sq)) {
                System.out.println("Sequence reseted " + fullPoolName + " last value = " + sq + ". Retry count = " + retryGetNextCount);
                if (retryGetNextCount >= retryGetNextMaxCount) {
                    retryGetNextCount = 0;
                    exp.increaseLatency(env, pool, "get-next-value", start);
                    return ResponseEntity.badRequest().body(new String(e.getMessage() + "\\\n"
                            + extErrText
                            + "\\\n Datapool may be empty."
                            + "\\\n Very often reset sequense: " + retryGetNextCount
                            + " for " + env + "." + pool + " last value = " + sq));
                }
                retryGetNextCount = retryGetNextCount + 1;
                exp.increaseLatency(env, pool, "get-next-value", start);
                return getNextData(env, pool, locked);
            }
            exp.increaseLatency(env, pool, "get-next-value", start);
            return ResponseEntity.badRequest().body(new String(e.getMessage() + "\\\n" + extErrText + "\\\n ERROR " + env + "." + pool + " sequence = " + sq));
        } catch (DataAccessException e) {
            exp.increaseLatency(env, pool, "get-next-value", start);
            return ResponseEntity.badRequest().body(new String(e.getMessage() + "\\\n" + extErrText + "\\\n ERROR " + env + "." + pool));
        }
    }

    synchronized private boolean fixSequenceState(String env, String pool, Long currentValue) {
        String fullPullName = fullPoolName(env, pool);
        if (currentValue < 0) return false; //Some undefined sequence error
        try {
//            long operation
//            Long sqMax = jdbcOperations.queryForObject("SELECT nvl(max (rid), 0) FROM " + env + "." + pool + "  where rid >= ? and locked = false", new Object[]{currentValue}, Long.class); //Is exist values forward
            int sqMax = lockerService.firstBiggerUnlockedId(fullPullName, currentValue.intValue());
            Long newSqValue = 0L;
            if (sqMax == 0) //Need reset sequence to start, no values forward
            // You can change for sequence CYCLE type if slow puts are planing
            {
//                long operation
//                newSqValue = jdbcOperations.queryForObject("SELECT nvl(min (rid), 0) FROM " + env + "." +pool + " where rid > 0 and locked = false",  Long.class);
                newSqValue = (long) lockerService.firstUnlockRid(fullPullName);
                if (newSqValue == 0) {
                    extErrText += "May be table is empty." + " ";
                    newSqValue = 1L;
                    jdbcOperations.update("ALTER SEQUENCE " + getSeqPrefix(env, pool) + "_rid" + " RESTART WITH ?", newSqValue);
                    return false;
                } //May be table is empty
                jdbcOperations.update("ALTER SEQUENCE " + getSeqPrefix(env, pool) + "_rid" + " RESTART WITH ?", newSqValue);
                return true;
            } else {//need reset sequence next
                newSqValue = this.jdbcOperations.queryForObject("SELECT nvl(min (rid),0) FROM " + env + "." + pool + " where rid > ? and rid <= ?  and locked != true", new Object[]{currentValue, sqMax}, Long.class);
                if (newSqValue == 0) {
                    extErrText += "Some errors when try to make sequence offset." + " ";
                    return false;
                } //May be table is empty
                this.jdbcOperations.update("ALTER SEQUENCE " + getSeqPrefix(env, pool) + "_rid" + " RESTART WITH ?", newSqValue);
                return true;
            }

        } catch (DataAccessException e) {
            System.err.println(e.getMessage() + "\\\n" + extErrText);
            return false;
        }
        //return false;
    }

    @PostMapping(path = "/put-value")
    public ResponseEntity<Object> putData(@RequestParam(value = "env", defaultValue = "load") String env,
                                          @RequestParam(value = "pool", defaultValue = "testpool") String pool,
                                          @RequestParam(value = "search-key", defaultValue = "") String searchKey,
                                          @RequestBody String text) {
        Instant start = Instant.now();
        exp.increaseRequests(env, pool, "put-value");
        try {
            if (searchKey.equals("")) {//No
                this.jdbcOperations.update("insert into " + env + "." + pool + "(rid, text, locked) values (nextval (?),?,?);", getSeqPrefix(env, pool) + "_max", text, false);
            } else {
                this.jdbcOperations.update("insert into " + env + "." + pool + "(rid, text, searchkey, locked) values (nextval (?),?,?,?);", getSeqPrefix(env, pool) + "_max", text, searchKey, false);
            }
            exp.increaseLatency(env, pool, "put-value", start);
            return ResponseEntity.ok(new String((long) 1 + " Inserted:" + "locked = false; searchKey = " + searchKey));
        } catch (DataAccessException e) {

            if (isTableNotFound(((DataAccessException) e).getCause())) {
                if (createTable(env, pool, searchKey)) {
                    System.out.println("Table " + env + "." + pool + " created!");
                    return this.putData(env, pool, searchKey, text);
                }
            } else {
                exp.increaseLatency(env, pool, "put-value", start);
                return ResponseEntity.badRequest().body(e.getMessage());
            }
        }
        return null;
    }

    @PostMapping(path = "/unlock")
    public ResponseEntity<String> unlockData(@RequestParam(value = "env", defaultValue = "load") String env,
                                             @RequestParam(value = "pool", defaultValue = "testpool") String pool,
                                             @RequestParam(value = "rid", defaultValue = "-1") String sRid,
                                             @RequestParam(value = "search-key", defaultValue = "") String searchKey,
                                             @RequestParam(value = "unlock-all", defaultValue = "false") boolean unlockAll) {
        Instant start = Instant.now();
        exp.increaseRequests(env, pool, "unlock");
        Long rid;
        String fullPullName = fullPoolName(env, pool);

        //Check rid valid value
        try {
            rid = Long.parseLong(sRid.trim());  //<-- String to long here
        } catch (NumberFormatException nfe) {
            exp.increaseLatency(env, pool, "unlock", start);
            return ResponseEntity.badRequest().body(new String(nfe.getMessage() + "\\\n" + extErrText + "\\\n ERROR " + env + "." + pool));
        }

        try {
            if (unlockAll) {
                lockerService.unlockAll(fullPullName);
            } else {
                if (!sRid.equals("-1")) {
                    lockerService.unlock(fullPullName, rid.intValue());
                } else {
                    if (!searchKey.equals("")) {
                        lockerService.unlock(fullPullName, searchKey);
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
        return ResponseEntity.ok(new String("Unlock successfully!"));
    }

    @GetMapping(path = "/search-by-key")
    public ResponseEntity<String> getBySearchKey(@RequestParam(value = "env", defaultValue = "load") String env,
                                                 @RequestParam(value = "pool", defaultValue = "testpool") String pool,
                                                 @RequestParam(value = "search-key", defaultValue = "") String searchKey,
                                                 @RequestParam(value = "locked", defaultValue = "false") boolean locked) {
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
                lockerService.lock(fullPoolName(env, pool), rid[0].intValue());
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
                                            @RequestParam(value = "override", defaultValue = "false") boolean override,
                                            @RequestParam(value = "with-headers", defaultValue = "true") boolean withHeaders,
                                            @RequestParam("file") MultipartFile file) {
        Instant start = Instant.now();
        exp.increaseRequests(env, pool, "upload-csv-as-json");
        String delim = ",";
        if (!withHeaders) {
            exp.increaseLatency(env, pool, "upload-csv-as-json", start);
            return ResponseEntity.badRequest().body(new String("With out headers csv files not supported!"));
        }
        if (file.isEmpty()) {
            exp.increaseLatency(env, pool, "upload-csv-as-json", start);
            return ResponseEntity.badRequest().body(new String("File is empty!"));
        }
        if (override) {
            if (!dropTable(env, pool) || !createTable(env, pool)) {
                exp.increaseLatency(env, pool, "upload-csv-as-json", start);
                return ResponseEntity.badRequest().body(new String("Some problem when trying to clean table " + env + "." + pool));
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
                    //System.out.println(json);
                }

                // read next line
                line = reader.readLine();
            }
            reader.close();
            if (!notValidRows.equals("")) {
                exp.increaseLatency(env, pool, "upload-csv-as-json", start);
                return ResponseEntity.badRequest().body(new String("Success uploaded rows: " + successCnt + ". Wrong parse some lines:\n" + notValidRows.substring(0, Math.min(notValidRows.length(), maxLengthNotValidRows))));
            }
            exp.increaseLatency(env, pool, "upload-csv-as-json", start);
            return ResponseEntity.ok().body(new String("Ok! Uploaded rows: " + successCnt));
        } catch (Exception ex) {
            exp.increaseLatency(env, pool, "upload-csv-as-json", start);
            return ResponseEntity.badRequest().body(new String("File parse exeption: " + ex.getMessage() + "\n" + notValidRows));
        }
        //return ResponseEntity.ok().body(new String ("Ok!"));
    }

    private String getSeqPrefix(String env, String pool) {
        String seqPrefix = new String(env + "." + "seq_" + pool);
        seqPrefix = seqPrefix.substring(0, seqPrefix.length() <= maxSequenceLength ? seqPrefix.length() : maxSequenceLength);
        return seqPrefix;
    }

    private boolean createTable(String env, String pool) {
        return createTable(env, pool, "");
    }

    private boolean createTable(String env, String pool, String searchKey) {
        boolean res = false;
        try {
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
        boolean res = false;
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

    private boolean isTableNotFound(Throwable cause) {
        Pattern pattern = Pattern.compile("Table .* not found");
        Matcher matcher = pattern.matcher(cause.getMessage());

        if (matcher.find()) {
            System.out.println("isTableNotFound function = true");
            return true;
        }
        return false;
    }

    private String fullPoolName(String env, String pool) {
        return env + "." + pool;
    }
}