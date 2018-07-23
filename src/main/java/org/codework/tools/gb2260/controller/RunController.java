package org.codework.tools.gb2260.controller;

import lombok.extern.java.Log;
import org.codework.tools.gb2260.mapper.DDLMapper;
import org.codework.tools.gb2260.snatch.AreaLayer;
import org.codework.tools.gb2260.snatch.AreaSnatchHandler;
import org.codework.tools.gb2260.snatch.guava.RunThreadPoolExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.beans.Transient;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

@Log
@RestController
@RequestMapping("/api/run")
public class RunController {

    AreaSnatchHandler handler;
    ConcurrentHashMap<String, RunThreadPoolExecutor> executors;
    DDLMapper ddlMapper;
    JedisPool jedisPool;

    public RunController(AreaSnatchHandler handler,
                         ConcurrentHashMap<String, RunThreadPoolExecutor> executors,
                         DDLMapper ddlMapper,
                         JedisPool jedisPool) {
        this.handler = handler;
        this.executors = executors;
        this.ddlMapper = ddlMapper;
        this.jedisPool = jedisPool;
    }

    @GetMapping("start")
    public Response start() {
        //队列中任一运行均不可再发起抓取
        boolean isRunning = executors.entrySet().stream().anyMatch(item ->
                !item.getValue().isShutdown());
        if (isRunning) {
            return Response.ok("msg", "running");
        } else{
            CompletableFuture.runAsync(() -> this.truncateAll())
                    .thenRunAsync(()->this.flushDB())
                    .thenRunAsync(() -> handler.snatch());
            return Response.ok();
        }
    }

    @GetMapping("query")
    public Response query(){
        Optional<Map.Entry<String, RunThreadPoolExecutor>> op = executors.entrySet().stream().filter(item ->
                !item.getValue().isShutdown()).findFirst();
        if (op.isPresent()) {
            RunThreadPoolExecutor executor = op.get().getValue();
            boolean isRunning = !executor.isShutdown();
            long completeCount = executor.getCompletedTaskCount();
            long taskCount = executor.getTaskCount();
            long activeCount = executor.getActiveCount();
            return Response.ok("running", isRunning)
                    .add("completeCount", completeCount)
                    .add("taskCount", taskCount)
                    .add("activeCount", activeCount);
        }else{
            return Response.ok("running", false);
        }
    }

    @GetMapping("termination")
    public Response termination(){
        try {
            Optional<Map.Entry<String, RunThreadPoolExecutor>> op = executors.entrySet().stream()
                    .filter(item -> !item.getValue().isShutdown()).findFirst();

            if (op.isPresent()) {
                RunThreadPoolExecutor executor = executors.entrySet().stream()
                        .filter(item -> !item.getValue().isShutdown()).findFirst().get().getValue();
                executor.shutdownNow();
            }else return Response.error("none executor!");

        }catch (RejectedExecutionException ex){
            log.info(ex.getMessage());
        }
        return Response.ok();
    }


    @Transient
    public void truncateAll(){
        ddlMapper.truncateTable(AreaLayer.Province.name().toLowerCase());
        ddlMapper.truncateTable(AreaLayer.City.name().toLowerCase());
        ddlMapper.truncateTable(AreaLayer.County.name().toLowerCase());
        ddlMapper.truncateTable(AreaLayer.Town.name().toLowerCase());
        ddlMapper.truncateTable(AreaLayer.Village.name().toLowerCase());
    }

    public void flushDB(){
        try(Jedis jedis = jedisPool.getResource()){
            jedis.flushDB();
        }
    }
}
