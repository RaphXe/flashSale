/*
 Navicat Premium Data Transfer
 
 Source Server         : MySQL
 Source Server Type    : MySQL
 Source Server Version : 80037 (8.0.37)
 Source Host           : localhost:3306
 Source Schema         : stock_database
 
 Target Server Type    : MySQL
 Target Server Version : 80037 (8.0.37)
 File Encoding         : 65001
 
 Date: 31/03/2026 09:55:53
 */
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;
-- ----------------------------
-- Table structure for goods
-- ----------------------------
DROP TABLE IF EXISTS `goods`;
CREATE TABLE `goods` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `price` decimal(10, 2) NOT NULL,
  `status` tinyint NOT NULL,
  `create_time` datetime NOT NULL,
  `update_time` datetime NOT NULL,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;
-- ----------------------------
-- Table structure for order
-- ----------------------------
DROP TABLE IF EXISTS `order`;
CREATE TABLE `order` (
  `id` bigint NOT NULL,
  `order_no` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `user_id` bigint NULL DEFAULT NULL,
  `type` tinyint NULL DEFAULT NULL,
  `order_status` tinyint NULL DEFAULT NULL,
  `amount` decimal(10, 2) NULL DEFAULT NULL,
  `pay_status` tinyint NULL DEFAULT NULL,
  `create_time` datetime NULL DEFAULT NULL,
  `pay_time` datetime NULL DEFAULT NULL,
  `expire_time` datetime NULL DEFAULT NULL,
  `update_time` datetime NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `unique_order_no_`(`order_no` ASC) USING BTREE,
  INDEX `fk_user_order`(`user_id` ASC) USING BTREE,
  CONSTRAINT `fk_user_order` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;
-- ----------------------------
-- Table structure for order_item
-- ----------------------------
DROP TABLE IF EXISTS `order_item`;
CREATE TABLE `order_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL,
  `goods_id` bigint NOT NULL,
  `buy_price` decimal(10, 2) NOT NULL,
  `quantity` int NOT NULL,
  `item_amount` decimal(10, 2) NOT NULL,
  `activity_id` bigint NULL DEFAULT NULL,
  `create_time` datetime NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `order_id`(`order_id` ASC) USING BTREE,
  INDEX `goods_id`(`goods_id` ASC) USING BTREE,
  CONSTRAINT `order_item_ibfk_1` FOREIGN KEY (`order_id`) REFERENCES `order` (`id`) ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT `order_item_ibfk_2` FOREIGN KEY (`goods_id`) REFERENCES `goods` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;
-- ----------------------------
-- Table structure for seckill_activity
-- ----------------------------
DROP TABLE IF EXISTS `seckill_activity`;
CREATE TABLE `seckill_activity` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `start_time` datetime NOT NULL,
  `end_time` datetime NOT NULL,
  `status` tinyint NOT NULL,
  `limit_per_person` int NOT NULL,
  `create_time` datetime NOT NULL,
  `update_time` datetime NOT NULL,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;
-- ----------------------------
-- Table structure for seckill_goods
-- ----------------------------
DROP TABLE IF EXISTS `seckill_goods`;
CREATE TABLE `seckill_goods` (
  `id` bigint NOT NULL,
  `activity_id` bigint NOT NULL,
  `goods_id` bigint NOT NULL,
  `seckill_price` decimal(10, 2) NOT NULL,
  `seckill_stock` int NOT NULL,
  `available_stock` int NOT NULL,
  `lock_stock` int NOT NULL,
  `per_user_limit` int NOT NULL,
  `version` int NOT NULL,
  `status` tinyint NOT NULL,
  `create_time` datetime NOT NULL,
  `update_time` datetime NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `seckill_goods_to_activity`(`activity_id` ASC) USING BTREE,
  INDEX `seckill_goods_to_product`(`goods_id` ASC) USING BTREE,
  CONSTRAINT `seckill_goods_to_activity` FOREIGN KEY (`activity_id`) REFERENCES `seckill_activity` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `seckill_goods_to_product` FOREIGN KEY (`goods_id`) REFERENCES `goods` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;
-- ----------------------------
-- Table structure for seckill_order
-- ----------------------------
DROP TABLE IF EXISTS `seckill_order`;
CREATE TABLE `seckill_order` (
  `id` bigint NOT NULL,
  `seckill_order_no` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `activity_id` bigint NOT NULL,
  `goods_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `quantity` int NOT NULL,
  `seckill_price` decimal(10, 2) NOT NULL,
  `amount` decimal(10, 2) NOT NULL,
  `status` tinyint NOT NULL,
  `order_id` bigint NULL DEFAULT NULL,
  `expire_time` datetime NOT NULL,
  `create_time` datetime NOT NULL,
  `update_time` datetime NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `unique_order_no`(`seckill_order_no` ASC) USING BTREE,
  UNIQUE INDEX `unique_activity_user_product`(`activity_id` ASC, `goods_id` ASC, `user_id` ASC) USING BTREE,
  INDEX `fk_product`(`goods_id` ASC) USING BTREE,
  INDEX `fk_user`(`user_id` ASC) USING BTREE,
  INDEX `fk_order_id`(`order_id` ASC) USING BTREE,
  CONSTRAINT `fk_activity` FOREIGN KEY (`activity_id`) REFERENCES `seckill_activity` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_order_id` FOREIGN KEY (`order_id`) REFERENCES `order` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_product` FOREIGN KEY (`goods_id`) REFERENCES `goods` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;
-- ----------------------------
-- Table structure for seckill_local_message
-- ----------------------------
DROP TABLE IF EXISTS `seckill_local_message`;
CREATE TABLE `seckill_local_message` (
  `id` bigint NOT NULL COMMENT '主键，建议使用雪花ID',
  `message_id` varchar(64) NOT NULL COMMENT '消息唯一ID，用于MQ幂等',
  `biz_type` varchar(64) NOT NULL COMMENT '业务类型，例如 SECKILL_ORDER_TIMEOUT',
  `biz_key` varchar(128) NOT NULL COMMENT '业务唯一键，秒杀下单场景使用 seckill_order_no',
  `seckill_order_no` varchar(64) NOT NULL COMMENT '秒杀订单号',
  `activity_id` bigint NOT NULL COMMENT '秒杀活动ID',
  `goods_id` bigint NOT NULL COMMENT '商品ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `quantity` int NOT NULL COMMENT '购买数量',
  `destination_type` tinyint NOT NULL COMMENT '目标类型：1-Kafka，2-RabbitMQ，3-HTTP，4-内部补偿任务',
  `destination` varchar(255) NOT NULL COMMENT '目标地址，例如 topic、exchange、接口名',
  `routing_key` varchar(255) DEFAULT NULL COMMENT 'RabbitMQ routing key 或 Kafka message key',
  `payload` json NOT NULL COMMENT '消息体快照，保存最终投递所需的完整业务数据',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '消息状态：0-待投递，1-投递中，2-已投递，3-已确认，4-投递失败，5-死亡',
  `retry_count` int NOT NULL DEFAULT 0 COMMENT '已重试次数',
  `max_retry_count` int NOT NULL DEFAULT 10 COMMENT '最大重试次数',
  `next_retry_time` datetime NOT NULL COMMENT '下次可重试时间',
  `last_error` varchar(1024) DEFAULT NULL COMMENT '最近一次失败原因',
  `sent_time` datetime DEFAULT NULL COMMENT '最近一次投递时间',
  `confirmed_time` datetime DEFAULT NULL COMMENT '最终确认时间',
  `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本号，防止并发扫描重复更新',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_time` datetime NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_message_id` (`message_id`) USING BTREE,
  UNIQUE KEY `uk_biz_type_key` (`biz_type`, `biz_key`) USING BTREE,
  KEY `idx_status_next_retry` (`status`, `next_retry_time`) USING BTREE,
  KEY `idx_seckill_order_no` (`seckill_order_no`) USING BTREE,
  KEY `idx_user_activity_goods` (`user_id`, `activity_id`, `goods_id`) USING BTREE,
  KEY `idx_create_time` (`create_time`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic COMMENT = '秒杀本地消息表，用于分布式事务最终一致性';
-- ----------------------------
-- Table structure for stock
-- ----------------------------
DROP TABLE IF EXISTS `stock`;
CREATE TABLE `stock` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `goods_id` bigint NOT NULL,
  `total_stock` int NOT NULL,
  `available_stock` int NOT NULL,
  `locked_stock` int NOT NULL,
  `update_time` datetime NOT NULL,
  `version` int NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `product_to_inventory`(`goods_id` ASC) USING BTREE,
  CONSTRAINT `product_to_inventory` FOREIGN KEY (`goods_id`) REFERENCES `goods` (`id`) ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT `chk_stock_balance` CHECK (
    (
      (`available_stock` >= 0)
      and (`locked_stock` >= 0)
      and (`total_stock` >= 0)
      and (
        (`available_stock` + `locked_stock`) = `total_stock`
      )
    )
  )
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;
-- ----------------------------
-- Table structure for users
-- ----------------------------
DROP TABLE IF EXISTS `users`;
CREATE TABLE `users` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `password` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `email` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `phone` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `status` tinyint NOT NULL,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 3 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;
SET FOREIGN_KEY_CHECKS = 1;
