package load.datapool.service;

public interface LockerService {

    void putPool(String pool, int size);
    void deletePool(String pool);
    void add(String pool);
    void lock(String pool, int rid);
    void unlock(String pool, int rid);
    void unlock(String pool, String searchKey);
    void unlockAll(String pool);
    int firstUnlockRid(String pool);
    int firstBiggerUnlockedId(String pool, int id);
    void initLocks();
    boolean poolExist(String pool);

}
