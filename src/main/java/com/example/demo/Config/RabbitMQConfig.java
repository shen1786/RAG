package com.example.demo.Config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * RabbitMQ 配置类 —— 定义消息队列的"交通规则"。
 *
 * <h2>通俗解释</h2>
 * <p>可以把 RabbitMQ 想象成一个"快递分拣中心"：</p>
 * <ul>
 *   <li><b>Exchange（交换机）</b>= 分拣台，决定包裹该送到哪条传送带</li>
 *   <li><b>Queue（队列）</b>= 传送带，包裹在上面排队等待处理</li>
 *   <li><b>Binding（绑定）</b>= 分拣台和传送带之间的连接规则</li>
 *   <li><b>Routing Key（路由键）</b>= 包裹上的标签，分拣台根据它决定走哪条传送带</li>
 *   <li><b>DLQ（死信队列）</b>= "问题件"传送带，处理失败的包裹会被转到这里等待人工排查</li>
 * </ul>
 *
 * <h2>本项目的队列架构</h2>
 * <pre>
 * ┌─────────────────────┐
 * │  rag.file.exchange   │  ← 一个 DirectExchange（直连交换机）
 * │  （分拣台）           │
 * └──────┬──────┬───────┘
 *        │      │
 *   routing    routing
 *   key:       key:
 *  file.process  file.delete
 *        │      │
 *        ▼      ▼
 * ┌──────────┐ ┌──────────┐
 * │ 文件处理  │ │ 文件删除  │    ← 两个主队列（正常业务）
 * │ 队列      │ │ 队列      │
 * └────┬─────┘ └────┬─────┘
 *      │ 失败        │ 失败
 *      ▼            ▼
 * ┌──────────┐ ┌──────────┐
 * │ 处理死信  │ │ 删除死信  │    ← 两个死信队列（兜底）
 * │ 队列      │ │ 队列      │
 * └──────────┘ └──────────┘
 * </pre>
 *
 * <h2>消息流转流程</h2>
 * <pre>
 * 1. 用户上传文件 → FileProcessProducer 发消息到"文件处理队列"
 * 2. FileProcessConsumer 从队列取出消息 → 解析 → 切块 → 向量化
 * 3. 如果处理失败 3 次 → 消息自动转入"处理死信队列"
 *
 * 4. 用户删除文件 → FileDeleteProducer 发消息到"文件删除队列"
 * 5. FileDeleteConsumer 从队列取出消息 → 删 Redis 向量 + 删 MySQL + 删 MinIO 文件
 * 6. 如果删除失败 3 次 → 消息自动转入"删除死信队列"
 * </pre>
 */
@Configuration
public class RabbitMQConfig {

    /**
     * 文件处理队列名称
     */
    public static final String FILE_PROCESS_QUEUE = "rag.file.process.queue";

    /**
     * 文件处理死信队列（处理失败的消息）
     */
    public static final String FILE_PROCESS_DLQ = "rag.file.process.dlq";

    /**
     * 文件删除队列
     */
    public static final String FILE_DELETE_QUEUE = "rag.file.delete.queue";

    /**
     * 文件删除死信队列
     */
    public static final String FILE_DELETE_DLQ = "rag.file.delete.dlq";

    /**
     * 交换机名称
     */
    public static final String FILE_EXCHANGE = "rag.file.exchange";

    /**
     * 路由键
     */
    public static final String FILE_PROCESS_ROUTING_KEY = "file.process";
    public static final String FILE_DLQ_ROUTING_KEY = "file.dlq";
    public static final String FILE_DELETE_ROUTING_KEY = "file.delete";
    public static final String FILE_DELETE_DLQ_ROUTING_KEY = "file.delete.dlq";

    /**
     * 消息转换器 —— 把 Java 对象序列化为 JSON 放入队列，取出时再反序列化回来。
     *
     * <p><b>为什么需要：</b>默认情况下 RabbitMQ 传的是 Java 序列化字节流（又大又不安全），
     * 改用 JSON 后：体积更小、可读性好、跨语言兼容（比如 Python 消费者也能解析）。</p>
     *
     * <p><b>实际效果：</b>Producer 发送 {@code FileProcessTask} 对象时，
     * Spring 自动调用 Jackson 把它转成 JSON 字符串再发到 RabbitMQ；
     * Consumer 收到时自动从 JSON 还原成 {@code FileProcessTask} 对象。</p>
     */
    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * RabbitTemplate —— Spring 提供的"消息发送工具"，Producer 用它往队列里发消息。
     *
     * <p><b>通俗解释：</b>相当于"快递员的扫码枪"，你把包裹（Java 对象）放上去，
     * 它自动扫码（序列化）然后投到正确的快递柜（Exchange + RoutingKey）。</p>
     *
     * <p><b>这里做了什么：</b>创建 RabbitTemplate 实例，并设置消息转换器为 JSON，
     * 确保发送出去的消息都是 JSON 格式。</p>
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         MessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        return template;
    }

    /**
     * 消息监听容器工厂 —— 配置 Consumer（消费者）的行为参数。
     *
     * <p><b>通俗解释：</b>相当于"快递员的工作规范"：一次拿几个包裹、怎么签收、最多雇几个快递员。</p>
     *
     * <p><b>关键配置说明：</b></p>
     * <ul>
     *   <li><b>MANUAL 手动确认</b> — 消费者处理完后自己调 ack/nack 告诉 MQ 结果，
     *       而不是收到就自动确认。这样处理失败时消息不会丢失。</li>
     *   <li><b>prefetchCount=1</b> — 一次只拉 1 条消息。因为文件处理很耗时（几十秒到几分钟），
     *       如果一次拉太多，其他消费者会饿死。</li>
     *   <li><b>concurrentConsumers=3, max=10</b> — 默认 3 个消费者线程，
     *       队列积压时自动扩到 10 个，处理完了再缩回 3 个。</li>
     *   <li><b>defaultRequeueRejected=false</b> — 处理失败的消息不重新入队，
     *       而是走死信队列（DLQ），避免"毒消息"一直重试卡死队列。</li>
     *   <li><b>idleEventInterval=7200000</b> — 空闲事件间隔 2 小时，
     *       文件处理可能很久，设大一点避免 MQ 认为消费者挂了。</li>
     * </ul>
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter messageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);

        // 手动确认模式：消费者处理完后自己调 ack/nack
        factory.setAcknowledgeMode(org.springframework.amqp.core.AcknowledgeMode.MANUAL);

        // 预取数量：每次只拉取1条消息处理，避免一个消费者霸占所有消息
        factory.setPrefetchCount(1);

        // 消费者线程数：默认3个，队列积压时最多扩到10个
        factory.setConcurrentConsumers(3);
        factory.setMaxConcurrentConsumers(10);

        // 处理失败的消息不重新入队，走死信队列（DLQ）
        factory.setDefaultRequeueRejected(false);
        // 空闲事件间隔 2 小时，防止长时间处理被 MQ 误判为超时
        factory.setIdleEventInterval(7200000L);

        return factory;
    }

    /**
     * 文件处理主队列 —— 存放"待处理的文件上传任务"。
     *
     * <p><b>参数说明：</b></p>
     * <ul>
     *   <li>{@code durable=true} — 持久化队列，RabbitMQ 重启后队列和消息不会丢失</li>
     *   <li>{@code exclusive=false} — 非独占，允许多个消费者连接</li>
     *   <li>{@code autoDelete=false} — 不自动删除，即使没有消费者也不会消失</li>
     *   <li>{@code x-dead-letter-exchange} — 消息处理失败时，转发到这个交换机（死信交换机，复用同一个）</li>
     *   <li>{@code x-dead-letter-routing-key} — 死信消息的路由键，决定进入哪个死信队列</li>
     * </ul>
     */
    @Bean
    public Queue fileProcessQueue() {
        Map<String, Object> args = new HashMap<>();
        // 指定死信交换机：处理失败的消息会被转发到这里
        args.put("x-dead-letter-exchange", FILE_EXCHANGE);
        // 指定死信路由键：失败消息会根据这个 key 进入对应的死信队列
        args.put("x-dead-letter-routing-key", FILE_DLQ_ROUTING_KEY);
        return new Queue(FILE_PROCESS_QUEUE, true, false, false, args);
    }

    /**
     * 文件处理死信队列（DLQ）—— 存放处理失败 3 次的消息。
     *
     * <p><b>通俗解释：</b>就像快递站的"问题件货架"，送了 3 次都送不到的包裹会被放到这里，
     * 等待人工检查（可能是地址写错了、收件人不在等）。</p>
     *
     * <p><b>什么时候消息会进来：</b></p>
     * <ul>
     *   <li>消费者处理抛异常，nack 了消息，且 {@code defaultRequeueRejected=false}</li>
     *   <li>消息在队列中等待超过 TTL（如果设置了的话）</li>
     *   <li>队列满了，新消息挤进来导致旧消息被淘汰</li>
     * </ul>
     */
    @Bean
    public Queue fileProcessDLQ() {
        return new Queue(FILE_PROCESS_DLQ, true);
    }

    /**
     * 文件删除主队列 —— 存放"待删除文件"的异步任务。
     *
     * <p><b>为什么要异步删除：</b>删除一个文件需要同时清理 3 个地方（Redis 向量、MySQL 记录、MinIO 文件），
     * 如果同步执行，用户要等很久。扔到队列里异步处理，用户立即得到响应。</p>
     *
     * <p><b>死信机制：</b>和文件处理队列一样，删除失败 3 次的消息会进入 {@link #fileDeleteDLQ()}。</p>
     */
    @Bean
    public Queue fileDeleteQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", FILE_EXCHANGE);
        args.put("x-dead-letter-routing-key", FILE_DELETE_DLQ_ROUTING_KEY);
        return new Queue(FILE_DELETE_QUEUE, true, false, false, args);
    }

    /**
     * 文件删除死信队列 —— 存放删除失败的消息。
     *
     * <p><b>实际场景：</b>比如 MinIO 临时不可用，导致文件删不掉，
     * 重试 3 次后消息就会来到这里，等 MinIO 恢复后可以手动重新投递。</p>
     */
    @Bean
    public Queue fileDeleteDLQ() {
        return new Queue(FILE_DELETE_DLQ, true);
    }

    /**
     * 直连交换机（DirectExchange）—— 根据 Routing Key 精确匹配，把消息路由到对应的队列。
     *
     * <p><b>通俗解释：</b>就像快递分拣台，工作人员看包裹上的标签（Routing Key），
     * 然后放到对应的传送带上。"file.process" 标签的走处理传送带，"file.delete" 标签的走删除传送带。</p>
     *
     * <p><b>为什么选 DirectExchange 而不是 Fanout/Topic：</b></p>
     * <ul>
     *   <li>Fanout — 广播给所有队列，浪费（我们只有 2 种业务，不需要广播）</li>
     *   <li>Topic — 支持通配符匹配，功能更强但这里不需要</li>
     *   <li>Direct — 最简单高效，精确匹配 Routing Key，刚好满足需求</li>
     * </ul>
     */
    @Bean
    public DirectExchange fileExchange() {
        return new DirectExchange(FILE_EXCHANGE, true, false);
    }

    // ────── 绑定关系：告诉交换机"什么 Routing Key 的消息该去哪个队列" ──────

    /**
     * 绑定：file.process → 文件处理队列。
     * Producer 发送时指定 routingKey="file.process"，消息就会进入 fileProcessQueue。
     */
    @Bean
    public Binding fileProcessBinding(Queue fileProcessQueue, DirectExchange fileExchange) {
        return BindingBuilder.bind(fileProcessQueue)
                .to(fileExchange)
                .with(FILE_PROCESS_ROUTING_KEY);
    }

    /**
     * 绑定：file.dlq → 文件处理死信队列。
     * 当主处理队列中的消息被 nack 后，根据死信路由键进入这个队列。
     */
    @Bean
    public Binding fileProcessDLQBinding(Queue fileProcessDLQ, DirectExchange fileExchange) {
        return BindingBuilder.bind(fileProcessDLQ)
                .to(fileExchange)
                .with(FILE_DLQ_ROUTING_KEY);
    }

    /**
     * 绑定：file.delete → 文件删除队列。
     * Producer 发送时指定 routingKey="file.delete"，消息就会进入 fileDeleteQueue。
     */
    @Bean
    public Binding fileDeleteBinding(Queue fileDeleteQueue, DirectExchange fileExchange) {
        return BindingBuilder.bind(fileDeleteQueue)
                .to(fileExchange)
                .with(FILE_DELETE_ROUTING_KEY);
    }

    /**
     * 绑定：file.delete.dlq → 文件删除死信队列。
     * 当删除队列中的消息处理失败后，根据死信路由键进入这个队列。
     */
    @Bean
    public Binding fileDeleteDLQBinding(Queue fileDeleteDLQ, DirectExchange fileExchange) {
        return BindingBuilder.bind(fileDeleteDLQ)
                .to(fileExchange)
                .with(FILE_DELETE_DLQ_ROUTING_KEY);
    }
}
