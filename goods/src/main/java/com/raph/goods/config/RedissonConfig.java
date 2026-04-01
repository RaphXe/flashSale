package com.raph.goods.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {
        @Bean
        public org.redisson.api.RedissonClient redissonClient() {
            return org.redisson.Redisson.create();
        }
}
