package org.codework.tools.gb2260;

import org.codework.tools.gb2260.snatch.guava.RunThreadPoolExecutor;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class RunThreadPoolExecutorTest {

    @Test
    public void testInFlightTasksAndCleanExit() throws InterruptedException {
        RunThreadPoolExecutor executor = new RunThreadPoolExecutor(
                10, 20, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>()
        );

        AtomicInteger executedCount = new AtomicInteger(0);

        // 模拟级联多层异步任务
        CompletableFuture.runAsync(() -> {
            executedCount.incrementAndGet();
            for (int i = 0; i < 5; i++) {
                final int childId = i;
                CompletableFuture.runAsync(() -> {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ignored) {}
                    executedCount.incrementAndGet();

                    // 下一层任务
                    CompletableFuture.runAsync(() -> {
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException ignored) {}
                        executedCount.incrementAndGet();
                    }, executor);

                }, executor);
            }
        }, executor);

        // 启动等待所有任务完成
        CompletableFuture<Void> waitFuture = CompletableFuture.runAsync(() -> executor.isEndTask());

        // 最多等待 5 秒
        try {
            waitFuture.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            Assert.fail("线程池未能正常完成或死锁: " + e.getMessage());
        }

        Assert.assertTrue("线程池应当已平滑关闭", executor.isShutdown());
        Assert.assertEquals("所有分层任务应当全部执行完成", 11, executedCount.get()); // 1 + 5 + 5 = 11
        Assert.assertEquals("在途任务数最终归零", 0, executor.getInFlightTasks());
        Assert.assertTrue("正常收敛收尾后 isNormalCompleted 应为 true", executor.isNormalCompleted());
        Assert.assertFalse("未被强制终止时 isUserTerminated 应为 false", executor.isUserTerminated());
    }

    @Test
    public void testShutdownNowMarksUserTerminated() {
        RunThreadPoolExecutor executor = new RunThreadPoolExecutor(
                5, 10, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>()
        );
        executor.execute(() -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {}
        });

        Assert.assertFalse("刚启动未中止时 userTerminated 应为 false", executor.isUserTerminated());
        executor.shutdownNow();
        Assert.assertTrue("调用 shutdownNow 后 isUserTerminated 应为 true", executor.isUserTerminated());
        Assert.assertFalse("强制中止后 isNormalCompleted 应为 false", executor.isNormalCompleted());
    }
}
