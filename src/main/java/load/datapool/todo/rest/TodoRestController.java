package load.datapool.todo.rest;

import com.opencsv.CSVReader;
import com.opencsv.bean.CsvToBean;
import com.opencsv.bean.CsvToBeanBuilder;
import com.opencsv.bean.MappingStrategy;
import com.opencsv.exceptions.*;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import load.datapool.todo.PoolRow;
import org.apache.commons.text.StringEscapeUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.List;
import java.util.ListIterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("api/v1")
@RequiredArgsConstructor
public final class TodoRestController {

    @NonNull
    private final JdbcOperations jdbcOperations;
    private String extErrText = ""; //Extended error cause
    private int retryGetNextCount = 0;// retry if skip seq
    private int maxSequenceLength = 25;
    private int maxIndexLength = 25;
    private int retryGetNextMaxCount = 999999;
    private int seqCycleCache = 5;

    @GetMapping(path = "/get-next-value")
    public ResponseEntity<String> getNextData(@RequestParam(value = "env", defaultValue = "load") String env,
                                               @RequestParam(value = "pool", defaultValue = "testpool") String pool,
                                               @RequestParam(value = "locked", defaultValue = "false") boolean locked) {
        Long sq = (long) -1;
        extErrText = "";

        try {
            sq = this.jdbcOperations.queryForObject("select nextval (?)", new Object[]{getSeqPrefix(env, pool) + "_rid"}, Long.class);
            ResponseEntity<String> res = ResponseEntity.ok(this.jdbcOperations.queryForObject("select rid, text, locked from " + env + "." + pool + " where rid = ? and locked = false limit 1",
                    (resultSet, i) ->
                            new String("{\"rid\":" + resultSet.getLong("rid") +
                                    ",\"values\":" + resultSet.getString("text") +
                                    ",\"locked\":" + locked +"}"), sq));
            //"\"locked\":" + resultSet.getBoolean("locked")), sq));
            if (locked) {
                this.jdbcOperations.update("update " + env + "." + pool + " set locked = true where  rid = ? and locked = false;", sq);
                //locked = true;
            }

            return res;

        } catch (EmptyResultDataAccessException  e) {

            if (fixSequenceState(env, pool, sq)){
                System.out.println("EmptyResultDataAccessException");
                System.out.println("Sequence reseted " + env +"." + pool + " last value = " + sq + ". Retry count = " + retryGetNextCount);
                if (retryGetNextCount >= retryGetNextMaxCount) {
                    retryGetNextCount = 0;
                    return ResponseEntity.badRequest().body(new String(e.getMessage()+ "\\\n"
                                                            + extErrText
                                                            + "\\\n Datapool may be empty."
                                                            + "\\\n Very often reset sequense: " + retryGetNextCount
                                                            + " for " + env +"." + pool + " last value = " + sq));
                }
                retryGetNextCount = retryGetNextCount +1;
                return this.getNextData(env, pool, locked);
            }
            return ResponseEntity.badRequest().body(new String(e.getMessage()+ "\\\n" + extErrText+ "\\\n ERROR " + env +"." + pool + " sequence = " + sq));
        } catch (DataAccessException e) {
            return ResponseEntity.badRequest().body(new String(e.getMessage()+ "\\\n" + extErrText+ "\\\n ERROR " + env +"." + pool));
        }
    }

   synchronized private boolean fixSequenceState(String env, String pool, Long currentValue) {
        if (currentValue < 0) return false; //Some undefined sequence error
        try {
            Long sqMax = this.jdbcOperations.queryForObject("SELECT nvl(max (rid), 0) FROM " + env + "." +pool + "  where rid >= ? and locked != true", new Object[]{currentValue}, Long.class); //Is exist values forward
            Long newSqValue = (long)0;
            if (sqMax == 0 ) //Need reset sequence to start, no values forward
                // You can change for sequence CYCLE type if slow puts are planing
            {
                newSqValue = this.jdbcOperations.queryForObject("SELECT nvl(min (rid),0) FROM " + env + "." +pool + " where rid > 0 and locked != true",  Long.class);
                if (newSqValue == 0)
                {
                    extErrText += "May be table is empty." + " ";
                    newSqValue = (long) 1;
                    this.jdbcOperations.update  ("ALTER SEQUENCE "+  getSeqPrefix(env,pool)+"_rid" +" RESTART WITH ?", newSqValue);
                    return false;
                } //May be table is empty
                this.jdbcOperations.update  ("ALTER SEQUENCE "+  getSeqPrefix(env,pool)+"_rid" +" RESTART WITH ?", newSqValue);
                return true;
            } else{//need reset sequence next
                newSqValue = this.jdbcOperations.queryForObject("SELECT nvl(min (rid),0) FROM " + env + "." +pool + " where rid > ? and rid <= ?  and locked != true",new Object[]{currentValue,sqMax},  Long.class);
                if (newSqValue == 0) {extErrText += "Some errors when try to make sequence offset." + " "; return false;} //May be table is empty
                this.jdbcOperations.update  ("ALTER SEQUENCE "+  getSeqPrefix(env,pool)+"_rid" +" RESTART WITH ?", newSqValue);
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
                                          @RequestBody String text) {
        try {
            this.jdbcOperations.update("insert into " + env + "." + pool + "(rid, text, locked) values (nextval (?),?,?);", getSeqPrefix(env, pool) + "_max", text, false);
            return ResponseEntity.ok(new PoolRow((long) 0, "Inserted", false));
        } catch (DataAccessException e) {

            if (isTableNotFound(((DataAccessException) e).getCause())) {
                if (createTable(env, pool)) {
                    System.out.println("Table " + env + "." + pool + " created!");
                    return this.putData(env, pool, text);
                }
            } else {
                return ResponseEntity.badRequest().body(new PoolRow(e.getMessage()));
            }
        }
        return null;
    }

    @GetMapping(path = "/unlock")
    public ResponseEntity<String> unlockData(){

        return null;
    }

    @PostMapping(path = "/upload-csv-as-json")
    public ResponseEntity<String> uploadCSV(@RequestParam(value = "env", defaultValue = "load") String env,
                                         @RequestParam(value = "pool", defaultValue = "testpool") String pool,
                                         @RequestParam(value = "override", defaultValue = "false") boolean override,
                                         @RequestParam(value = "with-headers", defaultValue = "true") boolean withHeaders,
                                         @RequestParam("file") MultipartFile file){
        String delim = ",";
        if (!withHeaders) {
            return ResponseEntity.badRequest().body(new String ("With out headers csv files not supported!"));
        }
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(new String ("File is empty!"));
        }
        if (override){
            if (!dropTable(env, pool) || !createTable(env, pool)){
                return ResponseEntity.badRequest().body(new String ("Some problem when trying to clean table " +env + "." + pool));
            }
        }
        // parse CSV file
        BufferedReader reader = null;
        String notValidRows = "";
        try {
            reader = new BufferedReader(new InputStreamReader(file.getInputStream()));
          String header = reader.readLine();
          if (header == null){
              return ResponseEntity.badRequest().body(new String ("Not found rows into file!"));
          }
          int cntColumns = header.split (delim).length;
          String headers[] = header.split (delim);

          String line = reader.readLine();

          String json = "";
          int successCnt = 0;
            while (line != null) {
                String values [] = line.split (delim);
                if (values.length != cntColumns){
                    notValidRows += line + "\n";
                }else {
                    json = "{";
                    for (int i = 0; i < cntColumns; i++) {
                        json += "\"" + headers[i] + "\":\"" + values[i] + "\",";
                    }
                    if (json.endsWith(",")) { json = json.substring(0,json.length()-1);}
                    json += "}";
                    this.jdbcOperations.update("insert into " + env + "." + pool + "(rid, text, locked) values (nextval (?),?,?);", getSeqPrefix(env, pool) + "_max", json, false);
                    successCnt++;
                    //System.out.println(json);
                }

                // read next line
                line = reader.readLine();
            }
            reader.close();
            if (!notValidRows.equals("")){
                return ResponseEntity.badRequest().body(new String ("Success uploaded rows: " + successCnt + ". Wrong parse some lines:\n" + notValidRows));
            }
            return ResponseEntity.ok().body(new String ("Ok! Uploaded rows: " + successCnt));
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(new String ("File parse exeption: " + ex.getMessage() + "\n" + notValidRows));
        }
         //return ResponseEntity.ok().body(new String ("Ok!"));
    }

    private String getSeqPrefix(String env, String pool) {
        String seqPrefix = new String(env + "." + "seq_" + pool);
        seqPrefix = seqPrefix.substring(0, seqPrefix.length() <= maxSequenceLength ? seqPrefix.length() : maxSequenceLength);
        return seqPrefix;
    }

    private boolean createTable(String env, String pool) {
        boolean res = false;
        try {
            this.jdbcOperations.execute("" +
                    "create table " + env + "." + pool +
                    "(" +
                    "        rid           bigint primary key," +
                    "        text        varchar(32000)," +
                    "        locked         boolean   not null default false" +
                    ");");
            String indexPrefix = new String("idx_" + env + "" + pool);
            indexPrefix = indexPrefix.substring(0, indexPrefix.length() <= maxIndexLength ? indexPrefix.length() : maxIndexLength);
            this.jdbcOperations.execute("CREATE INDEX " + indexPrefix + "_rid ON  " + env + "." + pool + " (rid);");
            this.jdbcOperations.execute("CREATE INDEX " + indexPrefix + "_lc ON  " + env + "." + pool + " (locked);");


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
}