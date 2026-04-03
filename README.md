
这是一个面向高并发秒杀场景的微服务课程项目，目标是把常见后端技术组合在一个可运行的业务系统里，重点练习：

- 微服务拆分与网关统一入口
- 缓存、布隆过滤、分布式锁的组合使用
- 异步消息削峰与超时订单处理
- 日志采集与检索
- Docker Compose 一键拉起整套环境

## 1. 项目整体结构

后端按业务拆分为多个 Spring Boot 服务，前端使用 Vue 3：

- `gateway-api`：统一网关，路由转发 + JWT 鉴权
- `user`：用户基础能力（注册、登录相关数据）
- `goods`：商品管理、搜索、缓存预热
- `stock`：库存服务
- `order`：普通订单服务
- `seckill`：秒杀活动与秒杀订单（异步链路）
- `stock-frontend`：Vue 3 前端

基础设施由 `docker-compose.yml` 统一编排：

- MySQL 主从（`db` + `db-replica`）
- ProxySQL（读写路由）
- Redis
- Kafka
- RabbitMQ
- Elasticsearch
- Fluent Bit
- Nginx（反向代理）

## 2. 核心技术与实现

## 2.1 网关与认证（Spring Cloud Gateway + JWT）

实现位置：`gateway-api`

- 通过 Spring Cloud Gateway 统一管理路由，按 `/api/user/**`、`/api/goods/**` 等路径转发到下游服务。
- 登录成功后由网关签发 JWT，后续请求统一校验 JWT。
- 鉴权过滤器会把解析出的用户 ID 写入请求头 `X-User-Id`，传递给下游服务。
- 对路径做了标准化处理（去掉末尾 `/`），避免 `/api/auth/login/` 这类请求被误判为未放行路径。
- 禁止外部直接访问 `/api/internal/**`。

## 2.2 商品服务（Redis + Elasticsearch + 布隆过滤 + Redisson）

实现位置：`goods`

- 商品搜索采用“ES 优先，失败降级 Redis，再降级 MySQL”的分层策略。
- 商品详情与搜索结果都写入 Redis，并加入随机过期时间，降低缓存雪崩风险。
- 使用 Guava BloomFilter 预判商品是否可能存在，减少无效 DB 查询（防缓存穿透）。
- 查询回源时配合 Redisson 分布式锁，避免并发下的缓存击穿。
- 启动阶段支持两件事：
  - MySQL 全量同步到 Elasticsearch
  - 基于 ES 聚合热点商品进行缓存预热

## 2.3 秒杀服务（Kafka + RabbitMQ + Redis 幂等）

实现位置：`seckill`

- 下单入口先快速接收请求并投递 Kafka，消费者异步创建秒杀订单，实现削峰。
- 订单消费过程用 Redis `setIfAbsent` 做幂等锁，防止重复消费生成重复订单。
- 秒杀订单创建后发送延迟消息到 RabbitMQ TTL 队列，超时未支付自动关闭订单。
- 超时消费者同样做 Redis 幂等控制，避免重复关闭。
- 同时使用布隆过滤、缓存与 Redisson，管理秒杀商品详情读取场景。

## 2.4 缓存预热（Logback JSON + Fluent Bit + Elasticsearch）

实现位置：`goods/logback-spring.xml`、`fluent-bit/fluent-bit.conf`

- 商品服务引入 `logstash-logback-encoder` 输出结构化日志。
- Docker 日志通过 fluentd driver 进入 Fluent Bit。
- Fluent Bit 对日志进行解析后，写入 Elasticsearch 索引，便于检索和分析。

## 2.5 数据层与基础能力

- MySQL 8 + JPA/JDBC 负责核心业务数据。
- MySQL 主从复制 + ProxySQL 支撑读写分离。
- Redis 除了缓存，还承担幂等状态、锁辅助等职责。
- 订单号生成采用雪花算法参数（workerId/datacenterId 可配置）。

## 2.6 前端实现（Vue 3 + Pinia + Vue Router + Axios）

实现位置：`stock-frontend`

- Vue Router 负责页面路由。
- Pinia 管理登录态与业务状态。
- Axios 对接网关 API。
- Vite 提供开发与构建能力，Vitest 用于单元测试。

## 3. 运行方式

## 3.1 使用 Docker Compose（推荐）

前置条件：

- Docker + Docker Compose

启动：

```bash
docker compose up -d --build
```

访问：

- 网关：`http://localhost:8080`
- Nginx：`http://localhost`
- RabbitMQ 管理台：`http://localhost:15672`
- Elasticsearch：`http://localhost:9200`

说明：

- 首次启动会执行 `init.sql` 初始化库表。
- 服务之间通信在容器网络中使用服务名（如 `redis`、`proxysql`），不使用 `localhost`。

## 3.2 本地开发启动（按模块）

前置条件：

- JDK 17
- Maven 3.9+
- Node.js 20+
- 本地可用的 MySQL / Redis / Kafka / RabbitMQ / Elasticsearch（或复用 Docker 提供的基础设施）

后端按模块启动示例：

```bash
mvn -f gateway-api/pom.xml spring-boot:run
mvn -f user/pom.xml spring-boot:run
mvn -f goods/pom.xml spring-boot:run
mvn -f stock/pom.xml spring-boot:run
mvn -f order/pom.xml spring-boot:run
mvn -f seckill/pom.xml spring-boot:run
```

前端启动：

```bash
cd stock-frontend
npm install
npm run dev
```

## TODO: 将活动时间校验放在进入kafka队列之前、创建seckillActivity的一致性校验。

现在我想要实现秒杀活动的定时开启与关闭。请你帮我实现：1、创建数据库轮询任务，每分钟轮询查看未来1小时内是否有即将开启的活动，若有则将计划写入redis（保证幂等性）和rabbitMQ 2、活动开始前5分钟，将活动和活动中的商品预热到redis中3、活动结束后20分钟，结算活动的库存，并将剩余库存释放到stock服务中，之所以是20分钟，因为订单超时时间为15分钟。也就是说20分钟后，所有订单都应该结算完毕，此时没有待释放的订单，如果你对此条内容有所疑问，可以暂不执行。4、活动状态不参与订单校验（避免延时），校验仅由时间确定，此条我已经实现