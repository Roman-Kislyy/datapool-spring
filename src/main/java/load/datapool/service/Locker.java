package load.datapool.service;

public interface Locker {

    void lock(int id);
    void unlock(int id);
    int firstUnlockId();
    int firstBiggerUnlockedId(int id);

}
