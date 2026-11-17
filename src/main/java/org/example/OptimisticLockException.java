package org.example;

/**
 * Выбрасывается при попытке обновить прибор, который уже изменил другой пользователь.
 */
public class OptimisticLockException extends Exception {
    public final long deviceId;
    public final int expectedVersion;
    public final int actualVersion;

    public OptimisticLockException(long deviceId, int expectedVersion, int actualVersion) {
        super("Device " + deviceId + " was modified. Expected version: " + expectedVersion + ", actual: " + actualVersion);
        this.deviceId = deviceId;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }
}


