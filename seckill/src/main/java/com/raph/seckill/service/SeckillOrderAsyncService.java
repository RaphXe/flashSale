package com.raph.seckill.service;

import java.time.Duration;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.raph.seckill.config.RabbitMqConfig;
import com.raph.seckill.dto.CreateSeckillOrderMessage;
import com.raph.seckill.dto.CreateSeckillOrderRequest;
import com.raph.seckill.dto.SeckillOrderTimeoutMessage;
import com.raph.seckill.entity.SeckillOrder;

@Service
public class SeckillOrderAsyncService {

    private static final Logger log = LoggerFactory.getLogger(SeckillOrderAsyncService.class);
    private static final String ORDER_CONSUME_IDEMPOTENT_KEY_PREFIX = "seckill:order:consume:idempotent:";
    private static final String ORDER_TIMEOUT_MESSAGE_SENT_KEY_PREFIX = "seckill:order:timeout:message:sent:";
    private static final String ORDER_TIMEOUT_CONSUME_IDEMPOTENT_KEY_PREFIX = "seckill:order:timeout:consume:idempotent:";

    private final KafkaTemplate<String, CreateSeckillOrderMessage> kafkaTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final SeckillOrderService seckillOrderService;
    private final String createOrderTopic;
    private final long consumeLockTtlSeconds;
    private final long consumeDoneTtlSeconds;
    private final long timeoutDelayMs;
    private final long timeoutMessageSentTtlSeconds;
    private final long timeoutConsumeLockTtlSeconds;
    private final long timeoutConsumeDoneTtlSeconds;

    public SeckillOrderAsyncService(
            KafkaTemplate<String, CreateSeckillOrderMessage> kafkaTemplate,
            RabbitTemplate rabbitTemplate,
            StringRedisTemplate stringRedisTemplate,
            SeckillOrderService seckillOrderService,
            @Value("${seckill.order.kafka.topic:seckill-order-create}") String createOrderTopic,
            @Value("${seckill.order.consume.idempotent.lock-ttl-seconds:600}") long consumeLockTtlSeconds,
            @Value("${seckill.order.consume.idempotent.done-ttl-seconds:604800}") long consumeDoneTtlSeconds,
            @Value("${seckill.order.timeout.delay-ms:900000}") long timeoutDelayMs,
            @Value("${seckill.order.timeout.message.sent-ttl-seconds:172800}") long timeoutMessageSentTtlSeconds,
            @Value("${seckill.order.timeout.consume.idempotent.lock-ttl-seconds:600}") long timeoutConsumeLockTtlSeconds,
            @Value("${seckill.order.timeout.consume.idempotent.done-ttl-seconds:604800}") long timeoutConsumeDoneTtlSeconds
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
        this.seckillOrderService = seckillOrderService;
        this.createOrderTopic = createOrderTopic;
        this.consumeLockTtlSeconds = consumeLockTtlSeconds;
        this.consumeDoneTtlSeconds = consumeDoneTtlSeconds;
        this.timeoutDelayMs = timeoutDelayMs;
        this.timeoutMessageSentTtlSeconds = timeoutMessageSentTtlSeconds;
        this.timeoutConsumeLockTtlSeconds = timeoutConsumeLockTtlSeconds;
        this.timeoutConsumeDoneTtlSeconds = timeoutConsumeDoneTtlSeconds;
    }

    // 生产者，提交创建订单请求，发送到Kafka
    public String submitCreateOrder(CreateSeckillOrderRequest request) {
        seckillOrderService.validateCreateRequestPayload(request);
        // 同步创建订单号
        String orderNo = seckillOrderService.allocateOrderNo();
        // 使用userId作为消息key，确保同一用户订单有序
        String messageKey = buildMessageKey(request.getUserId());

        CreateSeckillOrderMessage message = buildMessage(request, orderNo);
        kafkaTemplate.send(createOrderTopic, messageKey, message);
        return orderNo;
    }

    // Kafka消费者，异步处理创建订单请求
    @KafkaListener(topics = "${seckill.order.kafka.topic:seckill-order-create}", groupId = "${spring.kafka.consumer.group-id:seckill-order-consumer-group}")
    public void consumeCreateOrder(
            CreateSeckillOrderMessage message,
            @Header(value = KafkaHeaders.RECEIVED_KEY, required = false) String messageKey
    ) {
        String orderNo = message == null ? null : message.getSeckillOrderNo();
        if (!StringUtils.hasText(orderNo)) {
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
            log.info("异步创建秒杀订单命中幂等校验，忽略重复消息, key={}, orderNo={}", messageKey, orderNo);
            return;
        }

        try {
            SeckillOrder createdOrder = seckillOrderService.createSync(toRequest(message), orderNo);
            publishTimeoutMessageOnce(createdOrder);
            stringRedisTemplate.opsForValue().set(
                    idempotentKey,
                    "DONE",
                    Duration.ofSeconds(consumeDoneTtlSeconds)
            );
        } catch (IllegalArgumentException ex) {
            // 如果因为重复消费导致订单已存在，补发一次超时消息，保证超时关闭链路完整。
            if (shouldRecoverByExistingOrder(ex)) {
                seckillOrderService.findByOrderNo(orderNo).ifPresent(this::publishTimeoutMessageOnce);
            }
            stringRedisTemplate.opsForValue().set(
                    idempotentKey,
                    "REJECTED",
                    Duration.ofSeconds(consumeDoneTtlSeconds)
            );
            // 业务异常，记录日志但不重试，避免死循环
            log.warn("异步创建秒杀订单失败，丢弃消息, key={}, err={}", messageKey, ex.getMessage());
        } catch (Exception ex) {
            stringRedisTemplate.delete(idempotentKey);
            log.error("异步创建秒杀订单异常，将由Kafka重试, key={}", messageKey, ex);
            throw ex;
        }
    }

    @RabbitListener(queues = "${seckill.order.timeout.queue:seckill.order.timeout.queue}")
    public void consumeOrderTimeout(SeckillOrderTimeoutMessage message) {
        String orderNo = message == null ? null : message.getSeckillOrderNo();
        if (!StringUtils.hasText(orderNo)) {
            log.warn("处理秒杀订单超时失败，消息缺少订单号");
            return;
        }

        String idempotentKey = buildTimeoutConsumeIdempotentKey(orderNo);
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
            boolean expired = seckillOrderService.expireOrderIfPending(orderNo);
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

    private String buildTimeoutMessageSentKey(String orderNo) {
        return ORDER_TIMEOUT_MESSAGE_SENT_KEY_PREFIX + orderNo;
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

    private void publishTimeoutMessageOnce(SeckillOrder order) {
        if (order == null || !StringUtils.hasText(order.getSeckillOrderNo())) {
            return;
        }

        String sentKey = buildTimeoutMessageSentKey(order.getSeckillOrderNo());
        Boolean firstPublish = stringRedisTemplate.opsForValue().setIfAbsent(
                sentKey,
                "SENT",
                Duration.ofSeconds(timeoutMessageSentTtlSeconds)
        );
        if (!Boolean.TRUE.equals(firstPublish)) {
            return;
        }

        SeckillOrderTimeoutMessage timeoutMessage = new SeckillOrderTimeoutMessage();
        timeoutMessage.setSeckillOrderNo(order.getSeckillOrderNo());
        timeoutMessage.setActivityId(order.getActivityId());
        timeoutMessage.setGoodsId(order.getGoodsId());
        timeoutMessage.setQuantity(order.getQuantity());
        timeoutMessage.setExpireTime(order.getExpireTime());

        rabbitTemplate.convertAndSend(
                RabbitMqConfig.ORDER_TTL_EXCHANGE,
                RabbitMqConfig.ORDER_TTL_ROUTING_KEY,
                timeoutMessage,
                this::attachDelayTtl
        );
    }

    private Message attachDelayTtl(Message message) {
        message.getMessageProperties().setExpiration(String.valueOf(timeoutDelayMs));
        return message;
    }

    private boolean shouldRecoverByExistingOrder(IllegalArgumentException ex) {
        String message = ex == null ? null : ex.getMessage();
        return message != null && message.contains("已存在同活动同商品的秒杀订单");
    }
}
