package com.securelogx.util;

public class CachedClock {
    private static volatile long cachedNow = System.currentTimeMillis();

    static {
        Thread updater = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                cachedNow = System.currentTimeMillis();
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "SecureLogX-CachedClock");
        updater.setDaemon(true);
        updater.start();
    }

    public static long now() {
        return cachedNow;
    }
}
