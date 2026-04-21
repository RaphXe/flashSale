package com.raph.seckill.service;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import com.raph.seckill.dto.CreateSeckillOrderMessage;
import com.raph.seckill.dto.CreateSeckillOrderRequest;
import com.raph.seckill.dto.SeckillOrderTimeoutMessage;

@Service
public class SeckillOrderAsyncService {

    private static final Logger log = LoggerFactory.getLogger(SeckillOrderAsyncService.class);
    private static final String ORDER_CONSUME_IDEMPOTENT_KEY_PREFIX = "seckill:order:consume:idempotent:";
    private static final String ORDER_TIMEOUT_CONSUME_IDEMPOTENT_KEY_PREFIX = "seckill:order:timeout:consume:idempotent:";

    private final KafkaTemplate<String, CreateSeckillOrderMessage> kafkaTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final SeckillOrderService seckillOrderService;
    private final String createOrderTopic;
    private final long consumeLockTtlSeconds;
    private final long consumeDoneTtlSeconds;
    private final long timeoutConsumeLockTtlSeconds;
    private final long timeoutConsumeDoneTtlSeconds;
    private final MeterRegistry meterRegistry;
    private final Timer createOrderTimer;

    public SeckillOrderAsyncService(
            KafkaTemplate<String, CreateSeckillOrderMessage> kafkaTemplate,
            StringRedisTemplate stringRedisTemplate,
            SeckillOrderService seckillOrderService,
            @Value("${seckill.order.kafka.topic:seckill-order-create}") String createOrderTopic,
            @Value("${seckill.order.consume.idempotent.lock-ttl-seconds:600}") long consumeLockTtlSeconds,
            @Value("${seckill.order.consume.idempotent.done-ttl-seconds:604800}") long consumeDoneTtlSeconds,
            @Value("${seckill.order.timeout.consume.idempotent.lock-ttl-seconds:600}") long timeoutConsumeLockTtlSeconds,
            @Value("${seckill.order.timeout.consume.idempotent.done-ttl-seconds:604800}") long timeoutConsumeDoneTtlSeconds,
            MeterRegistry meterRegistry
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
        this.seckillOrderService = seckillOrderService;
        this.createOrderTopic = createOrderTopic;
        this.consumeLockTtlSeconds = consumeLockTtlSeconds;
        this.consumeDoneTtlSeconds = consumeDoneTtlSeconds;
        this.timeoutConsumeLockTtlSeconds = timeoutConsumeLockTtlSeconds;
        this.timeoutConsumeDoneTtlSeconds = timeoutConsumeDoneTtlSeconds;
        this.meterRegistry = meterRegistry;
        this.createOrderTimer = Timer.builder("seckill_order_create_duration")
                .description("Kafka consumer createSync processing duration")
                .register(meterRegistry);
    }

    // 生产者，提交创建订单请求，发送到Kafka
    public String submitCreateOrder(CreateSeckillOrderRequest request) {
        try {
            seckillOrderService.validateCreateRequestPayload(request);
        } catch (IllegalArgumentException ex) {
            meterRegistry.counter("seckill_order_async_submit", "result", "validation_rejected").increment();
            throw ex;
        }
        // 同步创建订单号
        String orderNo = seckillOrderService.allocateOrderNo();
        // 使用userId作为消息key，确保同一用户订单有序
        String messageKey = buildMessageKey(request.getUserId());
        seckillOrderService.markOrderCreateStateProcessing(orderNo);

        CreateSeckillOrderMessage message = buildMessage(request, orderNo);
        try {
            kafkaTemplate.send(createOrderTopic, messageKey, message);
            meterRegistry.counter("seckill_order_async_submit", "result", "accepted").increment();
        } catch (RuntimeException ex) {
            seckillOrderService.markOrderCreateStateFailed(orderNo, "下单请求发送失败，请稍后重试");
            meterRegistry.counter("seckill_order_async_submit", "result", "send_error").increment();
            throw ex;
        }
        return orderNo;
    }

    // Kafka消费者，异步处理创建订单请求
    @KafkaListener(topics = "${seckill.order.kafka.topic:seckill-order-create}", groupId = "${spring.kafka.consumer.group-id:seckill-order-consumer-group}")
    public void consumeCreateOrder(
            CreateSeckillOrderMessage message,
            @Header(value = KafkaHeaders.RECEIVED_KEY, required = false) String messageKey
    ) {
        if (message == null) {
            meterRegistry.counter("seckill_order_kafka_consume_invalid", "reason", "null_message").increment();
            log.warn("异步创建秒杀订单失败，消息为空, key={}", messageKey);
            return;
        }

        String orderNo = message.getSeckillOrderNo();

        if (orderNo == null) {
            meterRegistry.counter("seckill_order_kafka_consume_invalid", "reason", "missing_order_no").increment();
            log.warn("异步创建秒杀订单失败，消息缺少订单号, key={}", messageKey);
            return;
        }

        // 对订单进行幂等校验，防止重复消费导致重复订单
        String idempotentKey = buildConsumeIdempotentKey(orderNo);
        Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(
                idempotentKey,
                "PROCESSING",
                Duration.ofSeconds(consumeLockTtlSeconds)
        );
        if (!Boolean.TRUE.equals(acquired)) {
            meterRegistry.counter("seckill_order_kafka_consume_idempotent_hit").increment();
            meterRegistry.counter("seckill_order_kafka_consume", "result", "idempotent_ignored").increment();
            log.info("异步创建秒杀订单命中幂等校验，忽略重复消息, key={}, orderNo={}", messageKey, orderNo);
            return;
        }

        try {
            createOrderTimer.record(() ->
                    seckillOrderService.createSync(toRequest(message), orderNo)
            );
            seckillOrderService.markOrderCreateStateSuccess(orderNo);
            // 订单创建成功，记录到缓存中
            stringRedisTemplate.opsForValue().set(
                    idempotentKey,
                    "DONE",
                    Duration.ofSeconds(consumeDoneTtlSeconds)
            );
            meterRegistry.counter("seckill_order_kafka_consume", "result", "success").increment();
        } catch (IllegalArgumentException ex) {
            // 如果因为重复消费导致订单已存在，本地消息表中的超时消息会继续由投递器补偿发送。
            if (shouldRecoverByExistingOrder(ex)) {
                seckillOrderService.markOrderCreateStateSuccess(orderNo);
            } else {
                seckillOrderService.markOrderCreateStateFailed(orderNo, ex.getMessage());
            }
            // 订单创建失败，记录到缓存中，避免重复消费导致死循环
            stringRedisTemplate.opsForValue().set(
                    idempotentKey,
                    "REJECTED",
                    Duration.ofSeconds(consumeDoneTtlSeconds)
            );
            meterRegistry.counter("seckill_order_kafka_consume", "result", "business_rejected").increment();
            // 业务异常，记录日志但不重试，避免死循环
            log.warn("异步创建秒杀订单失败，丢弃消息, key={}, err={}", messageKey, ex.getMessage());
        } catch (Exception ex) {
            stringRedisTemplate.delete(idempotentKey);
            meterRegistry.counter("seckill_order_kafka_consume", "result", "exception_retry").increment();
            log.error("异步创建秒杀订单异常，将由Kafka重试, key={}", messageKey, ex);
            throw ex;
        }
    }

    // RabbitMQ消费者，处理订单超时消息，关闭过期订单
    @RabbitListener(queues = "${seckill.order.timeout.queue:seckill.order.timeout.queue}")
    public void consumeOrderTimeout(SeckillOrderTimeoutMessage message) {
        String orderNo = message == null ? null : message.getSeckillOrderNo();
        if (!StringUtils.hasText(orderNo)) {
            log.warn("处理秒杀订单超时失败，消息缺少订单号");
            return;
        }

        String idempotentKey = buildTimeoutConsumeIdempotentKey(orderNo);
        // 尝试获取锁，防止重复消费导致重复处理
        Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(
                idempotentKey,
                "PROCESSING",
                Duration.ofSeconds(timeoutConsumeLockTtlSeconds)
        );
        if (!Boolean.TRUE.equals(acquired)) {
            log.info("秒杀订单超时消费命中幂等校验，忽略重复消息, orderNo={}", orderNo);
            return;
        }

        try {
            // 关闭订单
            boolean expired = seckillOrderService.expireOrderIfPending(orderNo);
            // 更新redis
            stringRedisTemplate.opsForValue().set(
                    idempotentKey,
                    expired ? "DONE" : "SKIPPED",
                    Duration.ofSeconds(timeoutConsumeDoneTtlSeconds)
            );
            if (expired) {
                log.info("秒杀订单超时自动关闭成功, orderNo={}", orderNo);
            }
        } catch (Exception ex) {
            stringRedisTemplate.delete(idempotentKey);
            log.error("秒杀订单超时处理异常，将由RabbitMQ重试, orderNo={}", orderNo, ex);
            throw ex;
        }
    }

    private String buildConsumeIdempotentKey(String orderNo) {
        return ORDER_CONSUME_IDEMPOTENT_KEY_PREFIX + orderNo;
    }

    private String buildTimeoutConsumeIdempotentKey(String orderNo) {
        return ORDER_TIMEOUT_CONSUME_IDEMPOTENT_KEY_PREFIX + orderNo;
    }

    private String buildMessageKey(Long userId) {
        return userId == null ? "0" : String.valueOf(userId);
    }

    private CreateSeckillOrderMessage buildMessage(CreateSeckillOrderRequest request, String orderNo) {
        CreateSeckillOrderMessage message = new CreateSeckillOrderMessage();
        message.setSeckillOrderNo(orderNo);
        message.setActivityId(request.getActivityId());
        message.setGoodsId(request.getGoodsId());
        message.setUserId(request.getUserId());
        message.setQuantity(request.getQuantity());
        message.setSeckillPrice(request.getSeckillPrice());
        message.setExpireTime(request.getExpireTime());
        message.setStatus(request.getStatus());
        return message;
    }

    private CreateSeckillOrderRequest toRequest(CreateSeckillOrderMessage message) {
        CreateSeckillOrderRequest request = new CreateSeckillOrderRequest();
        request.setActivityId(message.getActivityId());
        request.setGoodsId(message.getGoodsId());
        request.setUserId(message.getUserId());
        request.setQuantity(message.getQuantity());
        request.setSeckillPrice(message.getSeckillPrice());
        request.setExpireTime(message.getExpireTime());
        request.setStatus(message.getStatus());
        return request;
    }

    private boolean shouldRecoverByExistingOrder(IllegalArgumentException ex) {
        String message = ex == null ? null : ex.getMessage();
        return message != null && message.contains("已存在同活动同商品的秒杀订单");
    }
}
