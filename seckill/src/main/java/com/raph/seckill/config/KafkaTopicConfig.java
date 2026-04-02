package com.raph.seckill.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic seckillCreateOrderTopic(
            @Value("${seckill.order.kafka.topic:seckill-order-create}") String topic,
            @Value("${seckill.order.kafka.partitions:6}") int partitions,
            @Value("${seckill.order.kafka.replicas:1}") short replicas
    ) {
        return TopicBuilder.name(topic)
                .partitions(partitions)
                .replicas(replicas)
                .build();
    }
}
