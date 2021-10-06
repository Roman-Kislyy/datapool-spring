package load.datapool.service;

import lombok.Getter;

import java.util.Arrays;

@Getter
public class BaseLocker implements Locker {

    public static final int startIndex = 1;

    private final String poolName;
    private final boolean[] lockList;

    public BaseLocker(String poolName, int size) {
        this.poolName = poolName;
        this.lockList = new boolean[size+1];
    }

    @Override
    public void lock(int id) {
        lockList[id] = true;
    }

    @Override
    public void unlock(int id) {
        lockList[id] = false;
    }

    @Override
    public void unlockAll() {
        Arrays.fill(lockList, false);
    }

    @Override
    public int firstUnlockId() {
        for (int i = startIndex; i < lockList.length; i++) {
            if (!lockList[i]) {     // not locked
                return i;
            }
        }
        return 0;   // null index
    }

    @Override
    public int firstBiggerUnlockedId(int id) {
        if (id > lockList.length-1) return 0;   // not exist
        for (int i = id+1; i < lockList.length; i++) {
            if (!lockList[i]) return i;
        }
        return 0;
    }

}
