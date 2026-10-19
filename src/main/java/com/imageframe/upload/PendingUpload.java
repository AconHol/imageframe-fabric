package com.imageframe.upload;

import java.nio.file.Path;
import java.util.UUID;

/**
 * One in-flight upload initiated by {@code /imageframe create <name> upload ...}.
 */
public final class PendingUpload {

    private final UUID token;
    private final UUID owner;
    private final String ownerName;
    private final String name;
    private final int width;
    private final int height;
    private final long createdAt = System.currentTimeMillis();
    private final Object lock = new Object();
    private volatile Path uploaded;
    private volatile boolean cancelled;

    PendingUpload(UUID owner, String ownerName, String name, int width, int height) {
        this.token = UUID.randomUUID();
        this.owner = owner;
        this.ownerName = ownerName;
        this.name = name;
        this.width = width;
        this.height = height;
    }

    public UUID token() {
        return token;
    }

    public UUID owner() {
        return owner;
    }

    public String ownerName() {
        return ownerName;
    }

    public String name() {
        return name;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public long createdAt() {
        return createdAt;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() - createdAt > ImageUploadService.EXPIRATION_MS;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    void cancel() {
        cancelled = true;
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    void complete(Path path) {
        uploaded = path;
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    public Path uploadedFile() {
        return uploaded;
    }

    /**
     * Blocks until the player uploaded a file, the upload expires or it is cancelled.
     *
     * @return the uploaded file, or {@code null} on expiry/cancellation
     */
    public Path awaitFile(long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        synchronized (lock) {
            while (uploaded == null && !cancelled && !isExpired() && System.currentTimeMillis() < deadline) {
                lock.wait(500L);
            }
            return uploaded;
        }
    }
}
