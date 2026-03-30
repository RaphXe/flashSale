package com.raph.stockbackend.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RedisService {
    @Autowired
    RedisTemplate<String, Object> template;

    public void setValue(String key, Object value) {
        template.opsForValue().set(key, value);
    }

    public Object getValue(String key) {
        return template.opsForValue().get(key);
    }
}
