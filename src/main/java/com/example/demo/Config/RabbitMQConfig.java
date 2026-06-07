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
 * RabbitMQ配置类
 * 定义文件处理相关的队列、交换机和绑定关系
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
     * 消息转换器 - 使用JSON格式
     */
    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * 配置RabbitTemplate使用JSON转换器
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         MessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        return template;
    }

    /**
     * 配置消息监听容器工厂使用JSON转换器
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter messageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);

        // 手动确认模式
        factory.setAcknowledgeMode(org.springframework.amqp.core.AcknowledgeMode.MANUAL);

        // 预取数量：每次只拉取1条消息处理
        factory.setPrefetchCount(1);

        // 消费者数量
        factory.setConcurrentConsumers(3);
        factory.setMaxConcurrentConsumers(10);

        // 设置消息超时时间（2小时），防止长时间处理被中断
        factory.setDefaultRequeueRejected(false);
        factory.setIdleEventInterval(7200000L);

        return factory;
    }

    /**
     * 文件处理队列
     * durable=true: 持久化队列，服务器重启后队列不会丢失
     */
    @Bean
    public Queue fileProcessQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", FILE_EXCHANGE);
        args.put("x-dead-letter-routing-key", FILE_DLQ_ROUTING_KEY);
        return new Queue(FILE_PROCESS_QUEUE, true, false, false, args);
    }

    /**
     * 死信队列 - 用于存储处理失败的消息
     */
    @Bean
    public Queue fileProcessDLQ() {
        return new Queue(FILE_PROCESS_DLQ, true);
    }

    /**
     * 文件删除队列
     */
    @Bean
    public Queue fileDeleteQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", FILE_EXCHANGE);
        args.put("x-dead-letter-routing-key", FILE_DELETE_DLQ_ROUTING_KEY);
        return new Queue(FILE_DELETE_QUEUE, true, false, false, args);
    }

    /**
     * 文件删除死信队列
     */
    @Bean
    public Queue fileDeleteDLQ() {
        return new Queue(FILE_DELETE_DLQ, true);
    }

    /**
     * 直连交换机
     */
    @Bean
    public DirectExchange fileExchange() {
        return new DirectExchange(FILE_EXCHANGE, true, false);
    }

    /**
     * 绑定文件处理队列到交换机
     */
    @Bean
    public Binding fileProcessBinding(Queue fileProcessQueue, DirectExchange fileExchange) {
        return BindingBuilder.bind(fileProcessQueue)
                .to(fileExchange)
                .with(FILE_PROCESS_ROUTING_KEY);
    }

    /**
     * 绑定死信队列到交换机
     */
    @Bean
    public Binding fileProcessDLQBinding(Queue fileProcessDLQ, DirectExchange fileExchange) {
        return BindingBuilder.bind(fileProcessDLQ)
                .to(fileExchange)
                .with(FILE_DLQ_ROUTING_KEY);
    }

    /**
     * 绑定文件删除队列到交换机
     */
    @Bean
    public Binding fileDeleteBinding(Queue fileDeleteQueue, DirectExchange fileExchange) {
        return BindingBuilder.bind(fileDeleteQueue)
                .to(fileExchange)
                .with(FILE_DELETE_ROUTING_KEY);
    }

    /**
     * 绑定文件删除死信队列到交换机
     */
    @Bean
    public Binding fileDeleteDLQBinding(Queue fileDeleteDLQ, DirectExchange fileExchange) {
        return BindingBuilder.bind(fileDeleteDLQ)
                .to(fileExchange)
                .with(FILE_DELETE_DLQ_ROUTING_KEY);
    }
}
