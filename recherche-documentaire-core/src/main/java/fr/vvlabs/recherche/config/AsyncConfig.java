package fr.vvlabs.recherche.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    @Bean("ocrExecutor")
    public Executor ocrExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}

