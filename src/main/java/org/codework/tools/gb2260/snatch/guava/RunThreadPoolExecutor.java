package org.codework.tools.gb2260.snatch.guava;

import lombok.extern.java.Log;

import java.util.concurrent.*;

@Log
public class RunThreadPoolExecutor extends ThreadPoolExecutor {
    private boolean hasFinish = false;


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

    @Override
    protected void afterExecute(Runnable r, Throwable t) {
        super.afterExecute(r, t);

        synchronized (this){
            //System.out.println("自动调用 afterEx. 此时getActiveCount()值 : " + this.getActiveCount());
            if(this.getActiveCount()==1){
                //已执行完任务之后的最后一个线程
                this.hasFinish=true;
                this.notify();
            }
        }
    }

    public void isEndTask(){
        synchronized (this){
            long startTime = System.currentTimeMillis();
            long endTime;

            while (this.hasFinish==false){
                log.info("等待线程池所有任务结束: wait...");
                try{
                    this.wait();
                }catch (InterruptedException e){
                    e.printStackTrace();
                }
            }
            if (this.hasFinish) {
                endTime = System.currentTimeMillis();
                log.info("完成所有线程池内的线程任务! Completed Total:"+this.getCompletedTaskCount()+". 当前耗时:"+(endTime-startTime));
                this.shutdown();
            }

        }
    }
}
