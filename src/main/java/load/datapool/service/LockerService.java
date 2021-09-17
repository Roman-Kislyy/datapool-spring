package load.datapool.service;

public interface LockerService {

    void putPool(String pool, int size);
    void deletePool(String pool);
    void lock(String pool, int rid);
    void unlock(String pool, int rid);
    int firstUnlockRid(String pool);
    int firstBiggerUnlockedId(String pool, int id);
    void initLocks();

}
