package org.codework.tools.gb2260.snatch.guava;

import lombok.extern.java.Log;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 自定义并发任务追踪执行器
 * <p>
 * 继承自 {@link ThreadPoolExecutor}，核心扩展能力：
 * 1. 在途任务计数遥测（In-Flight Tasks）：
 *    - 通过 {@link AtomicLong} 对提交的每一个任务进行原子增减追踪，精准掌握全局未处理完的任务总数。
 * 2. 任务收敛阻塞等待（isEndTask）：
 *    - 当在途任务降为 0 且触发完成状态时唤醒等待线程，实现多级异步树形抓取的可靠优雅收敛与线程池自动关闭。
 *
 * @author benjaminkc
 */
@Log
public class RunThreadPoolExecutor extends ThreadPoolExecutor {

    /**
     * 正在执行或排队中的任务总数计数器
     */
    private final AtomicLong inFlightTasks = new AtomicLong(0);

    /**
     * 任务收敛结束状态标识
     */
    private volatile boolean hasFinish = false;

    /**
     * 是否被外部强制中断或主动取消
     */
    private volatile boolean userTerminated = false;

    public RunThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit, BlockingQueue<Runnable> workQueue) {
        super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue);
    }

    public RunThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit, BlockingQueue<Runnable> workQueue, ThreadFactory threadFactory) {
        super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory);
    }

    public RunThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit, BlockingQueue<Runnable> workQueue, RejectedExecutionHandler handler) {
        super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, handler);
    }

    public RunThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit, BlockingQueue<Runnable> workQueue, ThreadFactory threadFactory, RejectedExecutionHandler handler) {
        super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory, handler);
    }

    /**
     * 提交执行新任务，包装任务以保证无论成功还是抛出异常，在途计数器均能精准扣减
     *
     * @param command 待执行任务
     */
    @Override
    public void execute(Runnable command) {
        synchronized (this) {
            this.hasFinish = false;
        }
        inFlightTasks.incrementAndGet();
        Runnable wrapped = () -> {
            try {
                command.run();
            } finally {
                decrementInFlight();
            }
        };
        try {
            super.execute(wrapped);
        } catch (Throwable ex) {
            decrementInFlight();
            throw ex;
        }
    }

    /**
     * 在途任务计数原子递减，并在所有任务完成时发出全局唤醒通知
     */
    private void decrementInFlight() {
        if (inFlightTasks.decrementAndGet() == 0) {
            synchronized (this) {
                this.hasFinish = true;
                this.notifyAll();
            }
        }
    }

    /**
     * 获取当前处于排队或执行状态的在途任务数量
     *
     * @return 在途任务总数
     */
    public long getInFlightTasks() {
        return inFlightTasks.get();
    }

    /**
     * 强制立即终止线程池，标记用户主动中止状态
     *
     * @return 尚未执行的任务列表
     */
    @Override
    public List<Runnable> shutdownNow() {
        this.userTerminated = true;
        return super.shutdownNow();
    }

    /**
     * 判断任务是否正常收敛完成（所有在途任务清零且未被强制终止）
     *
     * @return true 若全部在途任务正常执行完毕并收敛
     */
    public boolean isNormalCompleted() {
        return !userTerminated && this.hasFinish && inFlightTasks.get() == 0;
    }

    /**
     * 判断是否被外部主动终止或强制中断
     *
     * @return true 若被主动终止
     */
    public boolean isUserTerminated() {
        return userTerminated;
    }

    /**
     * 阻塞等待当前执行器内所有异步子任务全部执行完毕
     * 定期输出遥测日志，并在全部收敛后自动调用 shutdown 释放资源
     */
    public void isEndTask() {
        synchronized (this) {
            long startTime = System.currentTimeMillis();
            while ((!this.hasFinish || inFlightTasks.get() > 0) && !this.isShutdown()) {
                log.info("等待线程池所有任务结束: 剩余待处理任务数 = " + inFlightTasks.get());
                try {
                    this.wait(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    this.userTerminated = true;
                    break;
                }
            }
            if ((this.hasFinish && inFlightTasks.get() == 0) || this.isShutdown()) {
                long endTime = System.currentTimeMillis();
                log.info("完成所有线程池内的线程任务! Completed Total: " + this.getCompletedTaskCount() + ". 当前耗时: " + (endTime - startTime) + "ms");
                if (!this.isShutdown()) {
                    this.shutdown();
                }
            }
        }
    }
}
