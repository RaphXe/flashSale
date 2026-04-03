package com.raph.seckill.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raph.seckill.config.RabbitMqConfig;
import com.raph.seckill.dto.SeckillActivityTaskMessage;
import com.raph.seckill.entity.SeckillActivity;
import com.raph.seckill.entity.SeckillGoods;
import com.raph.seckill.repository.SeckillActivityRepository;
import com.raph.seckill.repository.SeckillGoodsRepository;

@Service
public class SeckillActivityScheduleService {

    private static final Logger log = LoggerFactory.getLogger(SeckillActivityScheduleService.class);

    private static final String TASK_TYPE_WARMUP = "WARMUP";
    private static final String TASK_TYPE_START = "START";
    private static final String TASK_TYPE_END = "END";
    private static final String TASK_TYPE_SETTLE = "SETTLE";

    private static final String PLAN_IDEMPOTENT_KEY_PREFIX = "seckill:activity:task:plan:idempotent:";
    private static final String CONSUME_IDEMPOTENT_KEY_PREFIX = "seckill:activity:task:consume:idempotent:";

    private static final String ACTIVITY_DETAIL_CACHE_KEY_PREFIX = "seckill:activity:detail:";
    private static final String ACTIVITY_GOODS_LIST_CACHE_KEY_PREFIX = "seckill:activity:goods:list:";
    private static final String GOODS_DETAIL_CACHE_KEY_PREFIX = "seckill:goods:detail:";
    private static final String GOODS_ACTIVITY_CACHE_KEY_PREFIX = "seckill:goods:activity-goods:";

    private final SeckillActivityRepository seckillActivityRepository;
    private final SeckillGoodsRepository seckillGoodsRepository;
    private final SeckillActivityService seckillActivityService;
    private final SeckillOrderService seckillOrderService;
    private final StringRedisTemplate stringRedisTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    private final long pollAheadMinutes;
    private final long warmupAheadMinutes;
    private final long settleDelayMinutes;
    private final long planIdempotentTtlSeconds;
    private final long consumeLockTtlSeconds;
    private final long consumeDoneTtlSeconds;
    private final long cacheTtlSeconds;

    public SeckillActivityScheduleService(
            SeckillActivityRepository seckillActivityRepository,
            SeckillGoodsRepository seckillGoodsRepository,
            SeckillActivityService seckillActivityService,
            SeckillOrderService seckillOrderService,
            StringRedisTemplate stringRedisTemplate,
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            @Value("${seckill.activity.scheduler.poll-ahead-minutes:60}") long pollAheadMinutes,
            @Value("${seckill.activity.scheduler.warmup-ahead-minutes:5}") long warmupAheadMinutes,
            @Value("${seckill.activity.scheduler.settle-delay-minutes:20}") long settleDelayMinutes,
            @Value("${seckill.activity.scheduler.plan.idempotent-ttl-seconds:259200}") long planIdempotentTtlSeconds,
            @Value("${seckill.activity.scheduler.consume.lock-ttl-seconds:600}") long consumeLockTtlSeconds,
            @Value("${seckill.activity.scheduler.consume.done-ttl-seconds:604800}") long consumeDoneTtlSeconds,
            @Value("${seckill.activity.scheduler.cache-ttl-seconds:7200}") long cacheTtlSeconds
    ) {
        this.seckillActivityRepository = seckillActivityRepository;
        this.seckillGoodsRepository = seckillGoodsRepository;
        this.seckillActivityService = seckillActivityService;
        this.seckillOrderService = seckillOrderService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.pollAheadMinutes = pollAheadMinutes;
        this.warmupAheadMinutes = warmupAheadMinutes;
        this.settleDelayMinutes = settleDelayMinutes;
        this.planIdempotentTtlSeconds = planIdempotentTtlSeconds;
        this.consumeLockTtlSeconds = consumeLockTtlSeconds;
        this.consumeDoneTtlSeconds = consumeDoneTtlSeconds;
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    // 每分钟轮询未来 1 小时内即将开始的活动，并投递预热/开启/关闭/结算任务。
    @Scheduled(cron = "${seckill.activity.scheduler.poll-cron:0 * * * * ?}")
    public void scheduleUpcomingActivities() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime horizon = now.plusMinutes(pollAheadMinutes);
        List<SeckillActivity> activities = seckillActivityRepository
                .findByStartTimeBetweenOrderByStartTimeAsc(now, horizon);

        for (SeckillActivity activity : activities) {
            if (activity == null || activity.getId() == null) {
                continue;
            }
            scheduleActivityTasks(activity);
        }
    }

    @RabbitListener(queues = "${seckill.activity.task.trigger.queue:seckill.activity.task.trigger.queue}")
    public void consumeActivityTask(SeckillActivityTaskMessage message) {
        Long activityId = message == null ? null : message.getActivityId();
        String taskType = message == null ? null : message.getTaskType();
        LocalDateTime executeAt = message == null ? null : message.getExecuteAt();

        if (activityId == null || !StringUtils.hasText(taskType) || executeAt == null) {
            log.warn("活动任务消息非法，忽略消息: {}", message);
            return;
        }

        String consumeKey = buildConsumeIdempotentKey(activityId, taskType, executeAt);
        // 这里的幂等粒度是 activityId + taskType + executeAt，允许同一活动不同时间的同类型任务共存，满足预热/开启/关闭/结算等不同任务类型的调度需求。
        Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(
                consumeKey,
                "PROCESSING",
                Duration.ofSeconds(consumeLockTtlSeconds)
        );
        if (!Boolean.TRUE.equals(acquired)) {
            log.info("活动任务消费命中幂等，忽略重复消息, activityId={}, taskType={}", activityId, taskType);
            return;
        }

        try {
            handleTask(activityId, taskType);
            // 更新redis幂等状态，标记任务已完成
            stringRedisTemplate.opsForValue().set(
                    consumeKey,
                    "DONE",
                    Duration.ofSeconds(consumeDoneTtlSeconds)
            );
        } catch (Exception ex) {
            stringRedisTemplate.delete(consumeKey);
            log.error("活动任务执行异常，将重试, activityId={}, taskType={}", activityId, taskType, ex);
            throw ex;
        }
    }

    private void scheduleActivityTasks(SeckillActivity activity) {
        LocalDateTime startTime = activity.getStartTime();
        LocalDateTime endTime = activity.getEndTime();
        if (startTime == null || endTime == null) {
            return;
        }

        // 每个活动需要四个计划任务：预热、开启、关闭、结算。
        scheduleOneTask(activity.getId(), TASK_TYPE_WARMUP, startTime.minusMinutes(warmupAheadMinutes));
        scheduleOneTask(activity.getId(), TASK_TYPE_START, startTime);
        scheduleOneTask(activity.getId(), TASK_TYPE_END, endTime);
        scheduleOneTask(activity.getId(), TASK_TYPE_SETTLE, endTime.plusMinutes(settleDelayMinutes));
    }

    // 计划任务先写 Redis 幂等，再写 RabbitMQ，防止轮询重复投递。
    private void scheduleOneTask(Long activityId, String taskType, LocalDateTime executeAt) {
        if (activityId == null || !StringUtils.hasText(taskType) || executeAt == null) {
            return;
        }

        String planKey = buildPlanIdempotentKey(activityId, taskType, executeAt);
        // 调度器也需要幂等处理
        Boolean firstPlan = stringRedisTemplate.opsForValue().setIfAbsent(
                planKey,
                "PLANNED",
                Duration.ofSeconds(planIdempotentTtlSeconds)
        );
        if (!Boolean.TRUE.equals(firstPlan)) {
            return;
        }

        long delayMs = Math.max(0L, Duration.between(LocalDateTime.now(), executeAt).toMillis());
        SeckillActivityTaskMessage message = new SeckillActivityTaskMessage();
        message.setActivityId(activityId);
        message.setTaskType(taskType);
        message.setExecuteAt(executeAt);

        // 向 RabbitMQ 发送延时消息，等待执行。
        rabbitTemplate.convertAndSend(
                RabbitMqConfig.ACTIVITY_TASK_TTL_EXCHANGE,
                RabbitMqConfig.ACTIVITY_TASK_TTL_ROUTING_KEY,
                message,
                rabbitMessage -> attachDelayTtl(rabbitMessage, delayMs)
        );
    }

    private void handleTask(Long activityId, String taskType) {
        switch (taskType) {
            case TASK_TYPE_WARMUP -> warmupActivityAndGoods(activityId);
            case TASK_TYPE_START -> updateActivityAndGoodsStatus(activityId, 1);
            case TASK_TYPE_END -> updateActivityAndGoodsStatus(activityId, 2);
            case TASK_TYPE_SETTLE -> settleActivity(activityId);
            default -> log.warn("未知任务类型，忽略消息, activityId={}, taskType={}", activityId, taskType);
        }
    }

    // 开始前 5 分钟预热活动与商品缓存，降低活动开始瞬时 DB 压力。
    private void warmupActivityAndGoods(Long activityId) {
        SeckillActivity activity = seckillActivityRepository.findById(activityId).orElse(null);
        if (activity == null) {
            return;
        }

        List<SeckillGoods> goodsList = seckillGoodsRepository.findByActivityId(activityId);
        writeCache(ACTIVITY_DETAIL_CACHE_KEY_PREFIX + activityId, toCacheActivity(activity));
        writeCache(ACTIVITY_GOODS_LIST_CACHE_KEY_PREFIX + activityId, toCacheGoodsList(goodsList));

        for (SeckillGoods goods : goodsList) {
            if (goods == null || goods.getId() == null || goods.getGoodsId() == null) {
                continue;
            }
            SeckillGoods cacheGoods = toCacheGoods(goods);
            writeCache(GOODS_DETAIL_CACHE_KEY_PREFIX + goods.getId(), cacheGoods);
            writeCache(GOODS_ACTIVITY_CACHE_KEY_PREFIX + activityId + ":" + goods.getGoodsId(), cacheGoods);
        }
    }

    // 活动开启/关闭时同步活动与商品状态，便于管理端查询。
    private void updateActivityAndGoodsStatus(Long activityId, int status) {
        SeckillActivity activity = seckillActivityRepository.findById(activityId).orElse(null);
        if (activity != null && (activity.getStatus() == null || activity.getStatus() != status)) {
            activity.setStatus(status);
            activity.setUpdateTime(LocalDateTime.now());
            seckillActivityRepository.save(activity);
        }

        List<SeckillGoods> goodsList = seckillGoodsRepository.findByActivityId(activityId);
        if (goodsList.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        boolean changed = false;
        for (SeckillGoods goods : goodsList) {
            if (goods == null) {
                continue;
            }
            if (goods.getStatus() == null || goods.getStatus() != status) {
                goods.setStatus(status);
                goods.setUpdateTime(now);
                changed = true;
            }
        }

        if (changed) {
            seckillGoodsRepository.saveAll(goodsList);
        }
    }

    // 活动结束 20 分钟后执行库存结算。
    // 这里先取消仍处于待支付状态的订单，再释放活动下未售库存到 stock 服务。
    private void settleActivity(Long activityId) {
        int canceled = seckillOrderService.forceCancelPendingOrdersByActivity(activityId);
        seckillActivityService.settleActivityStock(activityId);
        updateActivityAndGoodsStatus(activityId, 2);
        log.info("活动库存结算完成, activityId={}, canceledPendingOrders={}", activityId, canceled);
    }

    private Message attachDelayTtl(Message message, long delayMs) {
        message.getMessageProperties().setExpiration(String.valueOf(delayMs));
        return message;
    }

    private String buildPlanIdempotentKey(Long activityId, String taskType, LocalDateTime executeAt) {
        return PLAN_IDEMPOTENT_KEY_PREFIX + taskType + ":" + activityId + ":" + executeAt;
    }

    private String buildConsumeIdempotentKey(Long activityId, String taskType, LocalDateTime executeAt) {
        return CONSUME_IDEMPOTENT_KEY_PREFIX + taskType + ":" + activityId + ":" + executeAt;
    }

    private void writeCache(String key, Object value) {
        try {
            String payload = objectMapper.writeValueAsString(value);
            stringRedisTemplate.opsForValue().set(key, payload, Duration.ofSeconds(cacheTtlSeconds));
        } catch (JsonProcessingException ex) {
            log.warn("活动预热写缓存失败, key={}", key, ex);
        }
    }

    private SeckillActivity toCacheActivity(SeckillActivity activity) {
        SeckillActivity cache = new SeckillActivity();
        cache.setId(activity.getId());
        cache.setName(activity.getName());
        cache.setStartTime(activity.getStartTime());
        cache.setEndTime(activity.getEndTime());
        cache.setStatus(activity.getStatus());
        cache.setLimitPerPerson(activity.getLimitPerPerson());
        cache.setCreateTime(activity.getCreateTime());
        cache.setUpdateTime(activity.getUpdateTime());
        cache.setSeckillGoods(new ArrayList<>());
        return cache;
    }

    private List<SeckillGoods> toCacheGoodsList(List<SeckillGoods> goodsList) {
        List<SeckillGoods> cacheList = new ArrayList<>();
        for (SeckillGoods goods : goodsList) {
            if (goods == null) {
                continue;
            }
            cacheList.add(toCacheGoods(goods));
        }
        return cacheList;
    }

    private SeckillGoods toCacheGoods(SeckillGoods goods) {
        return new SeckillGoods(
                goods.getId(),
                null,
                goods.getActivityId(),
                goods.getGoodsId(),
                goods.getSeckillPrice(),
                goods.getSeckillStock(),
                goods.getAvailableStock(),
                goods.getLockStock(),
                goods.getPerUserLimit(),
                goods.getVersion(),
                goods.getStatus(),
                goods.getCreateTime(),
                goods.getUpdateTime()
        );
    }
}
