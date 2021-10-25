package load.datapool.service;

import load.datapool.db.H2Template;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

@Service
public class BaseLockerService implements LockerService {

    private static final List<String> systemSchemas = Arrays.asList("INFORMATION_SCHEMA", "PUBLIC");
    private final HashMap<String, Locker> lockers = new HashMap<>();
    private final char delimiter = '.';
    @Autowired
    private H2Template jdbcOperations;

    public BaseLockerService() {
        jdbcOperations = new H2Template();
        initLocks();
        jdbcOperations = null;
    }

    @Override
    public void initLocks() {
        System.out.println("Init start");
        try {
            List<String> schemas = showSchemas();
            schemas.forEach(schema -> {
                selectTables(schema).forEach(tableName -> {
                    selectLocksFromTable(schema, tableName);
                });
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
        System.out.println("Init finish");
    }

    private List<String> showSchemas() {
        final List<String> schemas = new ArrayList<>();
        final String selectSchemas = "SHOW SCHEMAS";
        jdbcOperations.queryForList(selectSchemas)
                .forEach(map -> map.forEach((key, value) -> {
                    final String schema = value.toString();
                    if (systemSchemas.contains(schema))
                        return;
                    schemas.add(schema);
                }));
        return schemas;
    }

    private List<String> selectTables(String schema) {
        final String selectTables = "SELECT TABLE_NAME FROM information_schema.tables where table_schema = ?";
        final String[] args = new String[]{schema};
        return jdbcOperations.queryForList(selectTables, args, String.class);
    }

    private void selectLocksFromTable(String schema, String tableName) {
        final String fullTableName = TableService.fullName(schema, tableName);
        System.out.println("Locker scan table: " + fullTableName);

        try {
            if (!containsLockedColumn(schema, tableName))
                return;

            Integer maxRid = maxRid(fullTableName);
            if (maxRid == null)
                return;

            Locker locker = new BaseLocker(fullTableName, maxRid);
            lockers.put(fullTableName, locker);

            final Integer lockedRows = lockedRows(fullTableName);
            if (lockedRows.equals(0))
                return;

            final int batchRows = 10000;
            final String selectRids = "SELECT rid FROM " + fullTableName + " WHERE rid > ? AND locked = true limit ?";
            final Object[] args = new Object[]{0, batchRows};
            final int ridIndex = 1;

            for (int rows = lockedRows; rows > 0; rows -= batchRows) {
                List<Integer> rids = jdbcOperations.queryForList(selectRids, args, Integer.class);
                rids.forEach(locker::lock);
                args[ridIndex] = rids.get(rids.size() - 1);
            }
            System.out.println("\tLockedRows: " + lockedRows);
        } catch (Exception e) {
            System.err.println("\tError scan table:" + fullTableName);
            e.printStackTrace();
        }
    }

    private boolean containsLockedColumn(String schema, String tableName) {
        final String lockedColumn = "LOCKED";
        final String selectColumns = "SELECT count(column_name) FROM INFORMATION_SCHEMA.COLUMNS WHERE table_schema = ? AND table_name = ? AND column_name = ?";
        final String[] args = new String[]{schema, tableName, lockedColumn};
        Integer lockedColNum = jdbcOperations.queryForObject(selectColumns, args, Integer.class);
        return lockedColNum > 0;
    }

    private Integer lockedRows(String fullTableName) {
        final String selectCountLocked = "SELECT count(rid) FROM " + fullTableName + " WHERE locked = true";
        return jdbcOperations.queryForObject(selectCountLocked, Integer.class);
    }

    private Integer maxRid(String fullTableName) {
        final String selectMaxRid = "SELECT max(rid) FROM " + fullTableName;
        return jdbcOperations.queryForObject(selectMaxRid, Integer.class);
    }

    private String handlePoolName(String env, String pool) {
        return (env + delimiter + pool).toUpperCase();
    }

    @Override
    public boolean poolExist(String env, String pool) {
        final int trueNum = 1;
        AtomicInteger exist = new AtomicInteger(0);
        try {
            showSchemas().forEach(schema -> {
                if (!schema.equalsIgnoreCase(env))
                    return;
                selectTables(schema).forEach(tableName -> {
                    if (tableName.equalsIgnoreCase(pool))
                        exist.set(trueNum);
                });
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
        return exist.get() == trueNum;
    }

    @Override
    public void putPool(String env, String pool) {
        pool = handlePoolName(env, pool);
        lockers.put(pool, new BaseLocker(pool));
    }

    @Override
    public void putPool(String env, String pool, int size) {
        pool = handlePoolName(env, pool);
        lockers.put(pool, new BaseLocker(pool, size));
    }

    @Override
    public void deletePool(String env, String pool) {
        pool = handlePoolName(env, pool);
        lockers.remove(pool);
    }

    @Override
    public void add(String env, String pool) {
        pool = handlePoolName(env, pool);
        lockers.get(pool).add();
    }

    @Override
    public void lock(String env, String pool, int rid) {
        pool = handlePoolName(env, pool);
        jdbcOperations.update("update " + pool + " set locked = true where  rid = ? and locked = false;", rid);
        lockers.get(pool).lock(rid);
    }

    @Override
    public void unlock(String env, String pool, int rid) {
        pool = handlePoolName(env, pool);
        jdbcOperations.update("update " + pool + " set locked = false where rid = ?", rid);
        lockers.get(pool).unlock(rid);
    }

    @Override
    public void unlock(String env, String pool, String searchKey) {
        pool = handlePoolName(env, pool);
        jdbcOperations.update("update " + pool + " set locked = false where searchkey = ?", searchKey);
        Integer rid = jdbcOperations.queryForObject("SELECT rid FROM " + pool + " WHERE searchkey = ? limit 1", Integer.class, searchKey);
        if (rid != null) lockers.get(pool).unlock(rid);
    }

    @Override
    public void unlockAll(String env, String pool) {
        pool = handlePoolName(env, pool);
        jdbcOperations.update("update " + pool + " set locked = false where locked is null; update " + pool + " set locked = false where locked =true;"); //Fast variant
        lockers.get(pool).unlockAll();
    }

    @Override
    public int firstUnlockRid(String env, String pool) {
        pool = handlePoolName(env, pool);
        return lockers.get(pool).firstUnlockId();
    }

    @Override
    public int firstBiggerUnlockedId(String env, String pool, int id) {
        pool = handlePoolName(env, pool);
        return lockers.get(pool).firstBiggerUnlockedId(id);
    }
}
