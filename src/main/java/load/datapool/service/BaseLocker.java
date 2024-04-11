package load.datapool.service;

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

@Getter
public class BaseLocker implements Locker {
    private final Logger logger = LoggerFactory.getLogger(BaseLocker.class);

    private final String poolName;
    private boolean[] list;
    @Getter
    private int size = 0;
    private int bufferSize = 1000;
    public static final int startIndex = 1;
    private boolean markedAsEmpty = false; // Mark true if the pool has become empty. There is no need to check table in the DB

    public BaseLocker(String poolName) {
        this.poolName = poolName;
        list = new boolean[bufferSize];
    }

    public BaseLocker(String poolName, int size) {
        this.poolName = poolName;
        this.size = size;
        bufferSize = Math.max(bufferSize, size / 10);
        list = new boolean[size + bufferSize];
        logger.debug("Create locker {poolName}: size = {}, bufferSize = {}, total length = {}", this.poolName, this.size, bufferSize, list.length);
    }

    @Override
    public synchronized void add() {
        add(1);
    }
    public synchronized  void add(int count) {
        size += count;
        if (size >= list.length) {
            list = Arrays.copyOf(list, size+bufferSize);
        }
    }
    @Override
    public synchronized  void lock(int id) {
        if (id >= list.length) return; // Some collision
        id-= startIndex;
        list[id] = true;
    }

    @Override
    public synchronized  void unlock(int id) {
        id-= startIndex;
        list[id] = false;
    }

    @Override
    public synchronized void unlockAll() {
        Arrays.fill(list, false);
    }

    @Override
    public synchronized int firstUnlockId() {
        logger.debug("Pool {}: size = {}", size);
        for (int i = 0; i < size; i++) {
            if (!list[i]) {     // not locked
                logger.debug("Now: {} = {}",i, String.valueOf(list[i]));
                logger.debug("Next: {} = {}",+ startIndex, String.valueOf(list[+ startIndex]));
                return i + startIndex;
            }
        }
        return 0;  // null index
    }

    @Override
    public synchronized int firstBiggerUnlockedId(int id) {
        logger.debug("Pool {}: id = {}, startIndex = {}, size = {}", getPoolName(), id, startIndex, size);
        id-= startIndex;
        if (id >= size) return 0;   // not exist
        for (int i = id; i < size; i++) {
            if (!list[i]) {
                logger.debug("Now: {} = {}",i, String.valueOf(list[i]));
                logger.debug("Next: {} = {}",+ startIndex, String.valueOf(list[+ startIndex]));
                return i + startIndex;
            }
        }
        return 0;
    }
    @Override
    public synchronized boolean isMarkedAsEmpty() {
        return this.markedAsEmpty;
    }
    @Override
    public synchronized void markAsEmpty() {
        if (this.markedAsEmpty == true) return;
        logger.warn("{} marked like empty pool.", poolName);
        this.markedAsEmpty = true;
    }
    @Override
    public synchronized void markAsNotEmpty() {
        if (this.markedAsEmpty == false) return;
        logger.warn("{} marked like NOT empty pool.", poolName);
        this.markedAsEmpty = false;
    }

}
