package com.aegis.merged;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;

@SpringBootApplication(exclude = { RedisAutoConfiguration.class, RedisReactiveAutoConfiguration.class,
        RedisRepositoriesAutoConfiguration.class })
public class AegisMergedApplication {

    public static void main(String[] args) {
        SpringApplication.run(AegisMergedApplication.class, args);
    }
}
