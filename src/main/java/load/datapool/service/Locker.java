package load.datapool.service;

public interface Locker {

    void add();
    void lock(int id);
    void unlock(int id);
    void unlockAll();
    int firstUnlockId();
    int firstBiggerUnlockedId(int id);

}
