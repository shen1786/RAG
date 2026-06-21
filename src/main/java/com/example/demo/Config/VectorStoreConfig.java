package com.example.demo.Config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import redis.clients.jedis.Connection;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;

import java.time.Duration;
import java.util.List;

@Configuration
public class VectorStoreConfig {

    /**
     * 共享的 JedisPooled 实例，供两个 VectorStore 复用同一连接池。
     */
    @Bean
    public JedisPooled jedisPooled(JedisConnectionFactory jedisConnectionFactory) {
        DefaultJedisClientConfig clientConfig = DefaultJedisClientConfig.builder()
                .ssl(jedisConnectionFactory.isUseSsl())
                .clientName(jedisConnectionFactory.getClientName())
                .timeoutMillis(jedisConnectionFactory.getTimeout())
                .password(jedisConnectionFactory.getPassword())
                .build();

        GenericObjectPoolConfig<Connection> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(32);
        poolConfig.setMaxIdle(16);
        poolConfig.setMinIdle(4);
        poolConfig.setMaxWait(Duration.ofMillis(60000));
        poolConfig.setTimeBetweenEvictionRuns(Duration.ofMillis(30000));
        poolConfig.setMinEvictableIdleDuration(Duration.ofMillis(60000));
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestWhileIdle(true);

        return new JedisPooled(
                poolConfig,
                new HostAndPort(jedisConnectionFactory.getHostName(), jedisConnectionFactory.getPort()),
                clientConfig
        );
    }

    @Bean
    @Primary
    public VectorStore leafVectorStore(EmbeddingModel embeddingModel,
                                       JedisPooled jedisPooled,
                                       @Value("${spring.ai.vectorstore.redis.initialize-schema:true}") boolean initializeSchema,
                                       @Value("${spring.ai.vectorstore.redis.index-name:rag-leaf-index}") String indexName,
                                       @Value("${spring.ai.vectorstore.redis.prefix:rag-leaf-prefix}") String prefix,
                                       ObjectProvider<ObservationRegistry> observationRegistryProvider) {
        return RedisVectorStore.builder(jedisPooled, embeddingModel)
                .initializeSchema(initializeSchema)
                .observationRegistry(observationRegistryProvider.getIfAvailable(() -> ObservationRegistry.NOOP))
                .indexName(indexName)
                .prefix(prefix)
                .metadataFields(defaultMetadataFields())
                .build();
    }

    @Bean
    public VectorStore summaryVectorStore(EmbeddingModel embeddingModel,
                                          JedisPooled jedisPooled,
                                          @Value("${spring.ai.vectorstore.redis.initialize-schema:true}") boolean initializeSchema,
                                          @Value("${spring.ai.vectorstore.redis.summary-index-name:rag-summary-index}") String indexName,
                                          @Value("${spring.ai.vectorstore.redis.summary-prefix:rag-summary-prefix}") String prefix,
                                          ObjectProvider<ObservationRegistry> observationRegistryProvider) {
        return RedisVectorStore.builder(jedisPooled, embeddingModel)
                .initializeSchema(initializeSchema)
                .observationRegistry(observationRegistryProvider.getIfAvailable(() -> ObservationRegistry.NOOP))
                .indexName(indexName)
                .prefix(prefix)
                .metadataFields(defaultMetadataFields())
                .build();
    }

    static List<RedisVectorStore.MetadataField> defaultMetadataFields() {
        return List.of(
                RedisVectorStore.MetadataField.tag("source_id"),
                RedisVectorStore.MetadataField.tag("source_type"),
                RedisVectorStore.MetadataField.tag("unit_id"),
                RedisVectorStore.MetadataField.tag("user_id"),
                RedisVectorStore.MetadataField.tag("node_type"),
                RedisVectorStore.MetadataField.tag("parent_id"),
                RedisVectorStore.MetadataField.text("filename"),
                RedisVectorStore.MetadataField.text("title"),
                RedisVectorStore.MetadataField.numeric("tree_level"),
                RedisVectorStore.MetadataField.numeric("child_count"),
                RedisVectorStore.MetadataField.numeric("chunk_index"),
                RedisVectorStore.MetadataField.numeric("start_time"),
                RedisVectorStore.MetadataField.numeric("end_time")
        );
    }
}
