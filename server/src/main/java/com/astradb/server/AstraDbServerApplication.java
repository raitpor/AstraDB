package com.astradb.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AstraDB 服务入口。
 */
@SpringBootApplication
@EnableScheduling
public class AstraDbServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AstraDbServerApplication.class, args);
    }
}
