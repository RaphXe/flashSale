package com.raph.order.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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

import com.raph.order.config.RabbitMqConfig;
import com.raph.order.dto.CreateOrderMessage;
import com.raph.order.dto.CreateOrderRequest;
import com.raph.order.dto.OrderItemRequest;
import com.raph.order.dto.OrderTimeoutMessage;
import com.raph.order.entity.Order;

@Service
public class OrderAsyncService {

    private static final Logger log = LoggerFactory.getLogger(OrderAsyncService.class);
    private static final String ORDER_CONSUME_IDEMPOTENT_KEY_PREFIX = "order:consume:idempotent:";
    private static final String ORDER_TIMEOUT_MESSAGE_SENT_KEY_PREFIX = "order:timeout:message:sent:";
    private static final String ORDER_TIMEOUT_CONSUME_IDEMPOTENT_KEY_PREFIX = "order:timeout:consume:idempotent:";

    private final KafkaTemplate<String, CreateOrderMessage> kafkaTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final OrderService orderService;
    private final String createOrderTopic;
    private final long consumeLockTtlSeconds;
    private final long consumeDoneTtlSeconds;
    private final long timeoutDelayMs;
    private final long timeoutMessageSentTtlSeconds;
    private final long timeoutConsumeLockTtlSeconds;
    private final long timeoutConsumeDoneTtlSeconds;

    public OrderAsyncService(
            KafkaTemplate<String, CreateOrderMessage> kafkaTemplate,
            RabbitTemplate rabbitTemplate,
            StringRedisTemplate stringRedisTemplate,
            OrderService orderService,
            @Value("${order.kafka.topic:order-create}") String createOrderTopic,
            @Value("${order.consume.idempotent.lock-ttl-seconds:600}") long consumeLockTtlSeconds,
            @Value("${order.consume.idempotent.done-ttl-seconds:604800}") long consumeDoneTtlSeconds,
            @Value("${order.timeout.delay-ms:1800000}") long timeoutDelayMs,
            @Value("${order.timeout.message.sent-ttl-seconds:172800}") long timeoutMessageSentTtlSeconds,
            @Value("${order.timeout.consume.idempotent.lock-ttl-seconds:600}") long timeoutConsumeLockTtlSeconds,
            @Value("${order.timeout.consume.idempotent.done-ttl-seconds:604800}") long timeoutConsumeDoneTtlSeconds
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
        this.orderService = orderService;
        this.createOrderTopic = createOrderTopic;
        this.consumeLockTtlSeconds = consumeLockTtlSeconds;
        this.consumeDoneTtlSeconds = consumeDoneTtlSeconds;
        this.timeoutDelayMs = timeoutDelayMs;
        this.timeoutMessageSentTtlSeconds = timeoutMessageSentTtlSeconds;
        this.timeoutConsumeLockTtlSeconds = timeoutConsumeLockTtlSeconds;
        this.timeoutConsumeDoneTtlSeconds = timeoutConsumeDoneTtlSeconds;
    }

    public String submitCreateOrder(CreateOrderRequest request) {
        orderService.validateCreateRequestPayload(request);
        String orderNo = orderService.allocateOrderNo();
        String messageKey = buildMessageKey(request.getUserId());

        CreateOrderMessage message = buildMessage(request, orderNo);
        kafkaTemplate.send(createOrderTopic, messageKey, message);
        return orderNo;
    }

    @KafkaListener(topics = "${order.kafka.topic:order-create}", groupId = "${spring.kafka.consumer.group-id:order-consumer-group}")
    public void consumeCreateOrder(
            CreateOrderMessage message,
            @Header(value = KafkaHeaders.RECEIVED_KEY, required = false) String messageKey
    ) {
        String orderNo = message == null ? null : message.getOrderNo();
        if (!StringUtils.hasText(orderNo)) {
            log.warn("异步创建订单失败，消息缺少订单号, key={}", messageKey);
            return;
        }

        String idempotentKey = buildConsumeIdempotentKey(orderNo);
        Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(
                idempotentKey,
                "PROCESSING",
                Duration.ofSeconds(consumeLockTtlSeconds)
        );
        if (!Boolean.TRUE.equals(acquired)) {
            log.info("异步创建订单命中幂等校验，忽略重复消息, key={}, orderNo={}", messageKey, orderNo);
            return;
        }

        try {
            Order createdOrder = orderService.createSync(toRequest(message), orderNo);
            publishTimeoutMessageOnce(createdOrder);
            stringRedisTemplate.opsForValue().set(
                    idempotentKey,
                    "DONE",
                    Duration.ofSeconds(consumeDoneTtlSeconds)
            );
        } catch (IllegalArgumentException ex) {
            if (shouldRecoverByExistingOrder(ex)) {
                orderService.findByOrderNo(orderNo).ifPresent(this::publishTimeoutMessageOnce);
            }
            stringRedisTemplate.opsForValue().set(
                    idempotentKey,
                    "REJECTED",
                    Duration.ofSeconds(consumeDoneTtlSeconds)
            );
            log.warn("异步创建订单失败，丢弃消息, key={}, err={}", messageKey, ex.getMessage());
        } catch (Exception ex) {
            stringRedisTemplate.delete(idempotentKey);
            log.error("异步创建订单异常，将由Kafka重试, key={}", messageKey, ex);
            throw ex;
        }
    }

    @RabbitListener(queues = "${order.timeout.queue:order.timeout.queue}")
    public void consumeOrderTimeout(OrderTimeoutMessage message) {
        String orderNo = message == null ? null : message.getOrderNo();
        if (!StringUtils.hasText(orderNo)) {
            log.warn("处理订单超时失败，消息缺少订单号");
            return;
        }

        String idempotentKey = buildTimeoutConsumeIdempotentKey(orderNo);
        Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(
                idempotentKey,
                "PROCESSING",
                Duration.ofSeconds(timeoutConsumeLockTtlSeconds)
        );
        if (!Boolean.TRUE.equals(acquired)) {
            log.info("订单超时消费命中幂等校验，忽略重复消息, orderNo={}", orderNo);
            return;
        }

        try {
            boolean expired = orderService.expireOrderIfPending(orderNo);
            stringRedisTemplate.opsForValue().set(
                    idempotentKey,
                    expired ? "DONE" : "SKIPPED",
                    Duration.ofSeconds(timeoutConsumeDoneTtlSeconds)
            );
            if (expired) {
                log.info("订单超时自动取消成功, orderNo={}", orderNo);
            }
        } catch (Exception ex) {
            stringRedisTemplate.delete(idempotentKey);
            log.error("订单超时处理异常，将由RabbitMQ重试, orderNo={}", orderNo, ex);
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

    private CreateOrderMessage buildMessage(CreateOrderRequest request, String orderNo) {
        CreateOrderMessage message = new CreateOrderMessage();
        message.setOrderNo(orderNo);
        message.setUserId(request.getUserId());
        message.setType(request.getType());
        message.setOrderStatus(request.getOrderStatus());
        message.setPayStatus(request.getPayStatus());
        message.setExpireTime(request.getExpireTime());
        message.setItems(request.getItems() == null ? new ArrayList<>() : new ArrayList<>(request.getItems()));
        return message;
    }

    private CreateOrderRequest toRequest(CreateOrderMessage message) {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setUserId(message.getUserId());
        request.setType(message.getType());
        request.setOrderStatus(message.getOrderStatus());
        request.setPayStatus(message.getPayStatus());
        request.setExpireTime(message.getExpireTime());

        List<OrderItemRequest> items = message.getItems() == null ? new ArrayList<>() : new ArrayList<>(message.getItems());
        request.setItems(items);
        return request;
    }

    private void publishTimeoutMessageOnce(Order order) {
        if (order == null || !StringUtils.hasText(order.getOrderNo())) {
            return;
        }

        String sentKey = buildTimeoutMessageSentKey(order.getOrderNo());
        Boolean firstPublish = stringRedisTemplate.opsForValue().setIfAbsent(
                sentKey,
                "SENT",
                Duration.ofSeconds(timeoutMessageSentTtlSeconds)
        );
        if (!Boolean.TRUE.equals(firstPublish)) {
            return;
        }

        OrderTimeoutMessage timeoutMessage = new OrderTimeoutMessage();
        timeoutMessage.setOrderNo(order.getOrderNo());
        timeoutMessage.setUserId(order.getUserId());
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
        return message != null && message.contains("订单已存在");
    }
}
