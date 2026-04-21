package com.raph.seckill.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raph.seckill.config.RabbitMqConfig;
import com.raph.seckill.dto.SeckillOrderTimeoutMessage;
import com.raph.seckill.entity.SeckillLocalMessage;
import com.raph.seckill.entity.SeckillOrder;
import com.raph.seckill.repository.SeckillLocalMessageRepository;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;

@Service
public class SeckillLocalMessageService {

    private static final Logger log = LoggerFactory.getLogger(SeckillLocalMessageService.class);

    public static final String BIZ_TYPE_SECKILL_ORDER_TIMEOUT = "SECKILL_ORDER_TIMEOUT";

    private static final int DESTINATION_TYPE_RABBITMQ = 2;
    private static final int MESSAGE_STATUS_PENDING = 0;
    private static final int MESSAGE_STATUS_SENDING = 1;
    private static final int MESSAGE_STATUS_CONFIRMED = 3;
    private static final int MESSAGE_STATUS_FAILED = 4;
    private static final int MESSAGE_STATUS_DEAD = 5;
    private static final int DEFAULT_MAX_RETRY_COUNT = 10;

    private final SeckillLocalMessageRepository localMessageRepository;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final Snowflake snowflake;
    private final long timeoutDelayMs;

    public SeckillLocalMessageService(
            SeckillLocalMessageRepository localMessageRepository,
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            @Value("${snowflake.worker-id:1}") long workerId,
            @Value("${snowflake.datacenter-id:1}") long datacenterId,
            @Value("${seckill.order.timeout.delay-ms:900000}") long timeoutDelayMs
    ) {
        this.localMessageRepository = localMessageRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.snowflake = IdUtil.getSnowflake(workerId, datacenterId);
        this.timeoutDelayMs = timeoutDelayMs;
    }

    @Transactional
    public void saveOrderTimeoutMessage(SeckillOrder order) {
        if (order == null || !StringUtils.hasText(order.getSeckillOrderNo())) {
            return;
        }

        String orderNo = order.getSeckillOrderNo().trim();
        if (localMessageRepository.existsByBizTypeAndBizKey(BIZ_TYPE_SECKILL_ORDER_TIMEOUT, orderNo)) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        SeckillOrderTimeoutMessage timeoutMessage = new SeckillOrderTimeoutMessage();
        timeoutMessage.setSeckillOrderNo(orderNo);
        timeoutMessage.setActivityId(order.getActivityId());
        timeoutMessage.setGoodsId(order.getGoodsId());
        timeoutMessage.setQuantity(order.getQuantity());
        timeoutMessage.setExpireTime(order.getExpireTime());

        SeckillLocalMessage localMessage = new SeckillLocalMessage();
        localMessage.setId(snowflake.nextId());
        localMessage.setMessageId("SCK_MSG_" + snowflake.nextId());
        localMessage.setBizType(BIZ_TYPE_SECKILL_ORDER_TIMEOUT);
        localMessage.setBizKey(orderNo);
        localMessage.setSeckillOrderNo(orderNo);
        localMessage.setActivityId(order.getActivityId());
        localMessage.setGoodsId(order.getGoodsId());
        localMessage.setUserId(order.getUserId());
        localMessage.setQuantity(order.getQuantity());
        localMessage.setDestinationType(DESTINATION_TYPE_RABBITMQ);
        localMessage.setDestination(RabbitMqConfig.ORDER_TTL_EXCHANGE);
        localMessage.setRoutingKey(RabbitMqConfig.ORDER_TTL_ROUTING_KEY);
        localMessage.setPayload(writePayload(timeoutMessage));
        localMessage.setStatus(MESSAGE_STATUS_PENDING);
        localMessage.setRetryCount(0);
        localMessage.setMaxRetryCount(DEFAULT_MAX_RETRY_COUNT);
        localMessage.setNextRetryTime(now);
        localMessage.setVersion(0);
        localMessage.setCreateTime(now);
        localMessage.setUpdateTime(now);

        // 本地消息和秒杀订单共用同一个数据库事务：订单提交成功，待投递消息也必然存在。
        localMessageRepository.save(localMessage);
    }

    @Scheduled(fixedDelayString = "${seckill.local-message.publisher.fixed-delay-ms:3000}")
    public void publishPendingMessages() {
        List<Integer> claimableStatuses = Arrays.asList(MESSAGE_STATUS_PENDING, MESSAGE_STATUS_FAILED);
        List<SeckillLocalMessage> messages = localMessageRepository
                .findTop100ByStatusInAndNextRetryTimeLessThanEqualOrderByCreateTimeAsc(
                        claimableStatuses,
                        LocalDateTime.now()
                );

        for (SeckillLocalMessage message : messages) {
            publishOne(message, claimableStatuses);
        }
    }

    private void publishOne(SeckillLocalMessage message, List<Integer> claimableStatuses) {
        if (message == null || message.getId() == null || message.getVersion() == null) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        int claimed = localMessageRepository.claimForSending(
                message.getId(),
                message.getVersion(),
                claimableStatuses,
                MESSAGE_STATUS_SENDING,
                now
        );
        if (claimed == 0) {
            return;
        }

        try {
            publishRabbitMessage(message);
            LocalDateTime sentTime = LocalDateTime.now();
            // RabbitMQ 延迟关闭订单没有额外确认回调，投递成功即可作为这条本地消息的最终确认。
            localMessageRepository.markSent(
                    message.getId(),
                    MESSAGE_STATUS_CONFIRMED,
                    sentTime,
                    sentTime,
                    sentTime
            );
        } catch (Exception ex) {
            markPublishFailed(message, ex);
        }
    }

    private void publishRabbitMessage(SeckillLocalMessage message) throws JsonProcessingException {
        if (message.getDestinationType() == null || message.getDestinationType() != DESTINATION_TYPE_RABBITMQ) {
            throw new IllegalStateException("不支持的本地消息目标类型: " + message.getDestinationType());
        }
        if (!BIZ_TYPE_SECKILL_ORDER_TIMEOUT.equals(message.getBizType())) {
            throw new IllegalStateException("不支持的本地消息业务类型: " + message.getBizType());
        }

        SeckillOrderTimeoutMessage timeoutMessage = objectMapper.readValue(
                message.getPayload(),
                SeckillOrderTimeoutMessage.class
        );

        rabbitTemplate.convertAndSend(
                message.getDestination(),
                message.getRoutingKey(),
                timeoutMessage,
                this::attachDelayTtl
        );
    }

    private Message attachDelayTtl(Message message) {
        message.getMessageProperties().setExpiration(String.valueOf(timeoutDelayMs));
        return message;
    }

    private void markPublishFailed(SeckillLocalMessage message, Exception ex) {
        int retryCount = defaultIfNull(message.getRetryCount(), 0) + 1;
        int maxRetryCount = defaultIfNull(message.getMaxRetryCount(), DEFAULT_MAX_RETRY_COUNT);
        int nextStatus = retryCount >= maxRetryCount ? MESSAGE_STATUS_DEAD : MESSAGE_STATUS_FAILED;
        LocalDateTime now = LocalDateTime.now();

        localMessageRepository.markFailed(
                message.getId(),
                nextStatus,
                retryCount,
                now.plus(calculateRetryBackoff(retryCount)),
                abbreviateError(ex),
                now
        );

        log.warn("秒杀本地消息投递失败, id={}, bizType={}, bizKey={}, retryCount={}",
                message.getId(), message.getBizType(), message.getBizKey(), retryCount, ex);
    }

    private String writePayload(SeckillOrderTimeoutMessage timeoutMessage) {
        try {
            return objectMapper.writeValueAsString(timeoutMessage);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("序列化秒杀本地消息失败", ex);
        }
    }

    private Duration calculateRetryBackoff(int retryCount) {
        long delaySeconds = Math.min(300L, (long) Math.pow(2, Math.min(retryCount, 8)));
        return Duration.ofSeconds(delaySeconds);
    }

    private String abbreviateError(Exception ex) {
        String message = ex == null ? "unknown" : ex.getMessage();
        if (!StringUtils.hasText(message)) {
            message = ex.getClass().getSimpleName();
        }
        return message.length() > 1024 ? message.substring(0, 1024) : message;
    }

    private Integer defaultIfNull(Integer value, Integer defaultValue) {
        return value == null ? defaultValue : value;
    }
}
