package load.datapool.service;

public interface Locker {

    void add();
    void add(int count);
    void lock(int id);
    void unlock(int id);
    void unlockAll();
    int firstUnlockId();
    int firstBiggerUnlockedId(int id);
    boolean isMarkedAsEmpty();
    void markAsEmpty();
    void markAsNotEmpty();

}
