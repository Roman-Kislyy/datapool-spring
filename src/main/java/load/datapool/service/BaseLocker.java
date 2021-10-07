package load.datapool.service;

import lombok.Getter;

import java.util.Arrays;

@Getter
public class BaseLocker implements Locker {

    private final String poolName;
    private boolean[] list;
    @Getter
    private int size = 0;
    private int bufferSize = 1000;
    public static final int startIndex = 1;

    public BaseLocker(String poolName) {
        this.poolName = poolName;
        list = new boolean[bufferSize];
    }

    public BaseLocker(String poolName, int size) {
        this.poolName = poolName;
        this.size = size;
        bufferSize = Math.max(bufferSize, size / 10);
        list = new boolean[size + bufferSize];
    }

    @Override
    public void add() {
        size++;
        if (size >= list.length) {
            list = Arrays.copyOf(list, size+bufferSize);
        }
    }

    @Override
    public void lock(int id) {
        id-= startIndex;
        list[id] = true;
    }

    @Override
    public void unlock(int id) {
        id-= startIndex;
        list[id] = false;
    }

    @Override
    public void unlockAll() {
        Arrays.fill(list, false);
    }

    @Override
    public int firstUnlockId() {
        for (int i = 0; i < size; i++) {
            if (!list[i]) {     // not locked
                return i + startIndex;
            }
        }
        return 0;  // null index
    }

    @Override
    public int firstBiggerUnlockedId(int id) {
        id-= startIndex;
        if (id >= size) return 0;   // not exist
        for (int i = id; i < list.length; i++) {
            if (!list[i])
                return i+startIndex;
        }
        return 0;
    }

}
