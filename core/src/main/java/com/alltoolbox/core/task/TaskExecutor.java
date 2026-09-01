package com.alltoolbox.core.task;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 后台任务线程池管理。
 *
 * 区分不同用途的线程池，避免互相抢占：普通文件 IO、扫描、压缩解压等
 * 分别使用可调数量的独立线程池；APK 逆向等重操作使用键控单线程池，
 * 保证工具调用串行且不会因 IO 任务阻塞。
 */
public final class TaskExecutor {

    private final int cpuCount = Math.max(2, Runtime.getRuntime().availableProcessors());

    // 通用文件 IO 线程池
    private final ExecutorService ioPool;
    // 压缩/解压线程池
    private final ExecutorService archivePool;
    // 扫描（大文件/重复文件）线程池
    private final ExecutorService scanPool;
    // APK 逆向等重且须串行的任务池
    private final ExecutorService heavyPool;
    // 定时/延迟任务
    private final ScheduledExecutorService scheduledPool;

    private static volatile TaskExecutor sInstance;

    public static TaskExecutor get() {
        if (sInstance == null) {
            synchronized (TaskExecutor.class) {
                if (sInstance == null) {
                    sInstance = new TaskExecutor();
                }
            }
        }
        return sInstance;
    }

    private TaskExecutor() {
        ioPool = Executors.newFixedThreadPool(cpuCount, namedThread("file-io"));
        archivePool = Executors.newFixedThreadPool(cpuCount, namedThread("archive"));
        scanPool = Executors.newFixedThreadPool(2, namedThread("file-scan"));
        heavyPool = Executors.newSingleThreadExecutor(namedThread("apk-heavy"));
        scheduledPool = Executors.newScheduledThreadPool(1, namedThread("scheduler"));
    }

    private ThreadFactory namedThread(final String prefix) {
        final AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread t = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            t.setDaemon(false);
            return t;
        };
    }

    public ExecutorService io() {
        return ioPool;
    }

    public ExecutorService archive() {
        return archivePool;
    }

    public ExecutorService scan() {
        return scanPool;
    }

    public ExecutorService heavy() {
        return heavyPool;
    }

    public ScheduledExecutorService scheduler() {
        return scheduledPool;
    }

    /** 等待所有后台任务结束（用于测试或退出前清理）。 */
    public void shutdownQuietly() {
        for (ExecutorService pool : new ExecutorService[]{ioPool, archivePool, scanPool, heavyPool, scheduledPool}) {
            pool.shutdown();
            try {
                pool.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}