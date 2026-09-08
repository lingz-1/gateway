package com.lingshu.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration(proxyBeanMethods = false)
public class StreamingConfiguration {

    @Bean(destroyMethod = "close")
    public ExecutorService streamingExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
