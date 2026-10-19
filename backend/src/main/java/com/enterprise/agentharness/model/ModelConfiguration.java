package com.enterprise.agentharness.model;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class ModelConfiguration {
    public record Policy(long dailyTokenBudget) {}
    @Bean ModelProvider modelProvider(@Value("${eahp.model.provider:mock}") String provider) {
        if(!provider.equals("mock")) throw new IllegalStateException("Only the mock model provider is implemented; real providers require explicit integration");
        return new MockModelProvider();
    }
    @Bean Policy modelPolicy(@Value("${eahp.model.daily-token-budget:20000}") long budget) {
        if(budget<1 || budget>1_000_000_000L) throw new IllegalStateException("Model daily token budget must be 1..1000000000");
        return new Policy(budget);
    }
    @Bean(destroyMethod="shutdownNow") ExecutorService modelExecutor() {
        AtomicInteger counter=new AtomicInteger();
        return new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new SynchronousQueue<>(),task->{
            Thread thread=new Thread(task,"eahp-model-"+counter.incrementAndGet());thread.setDaemon(true);return thread;
        },new ThreadPoolExecutor.AbortPolicy());
    }
    @Bean ThreadPoolTaskScheduler taskScheduler() {
        var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(2);scheduler.setThreadNamePrefix("eahp-scheduler-");return scheduler;
    }
}
