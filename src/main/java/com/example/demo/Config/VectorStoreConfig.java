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
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;

@Configuration
public class VectorStoreConfig {

    @Bean
    @Primary
    public VectorStore leafVectorStore(EmbeddingModel embeddingModel,
                                       JedisConnectionFactory jedisConnectionFactory,
                                       @Value("${spring.ai.vectorstore.redis.initialize-schema:true}") boolean initializeSchema,
                                       @Value("${spring.ai.vectorstore.redis.index-name:rag-leaf-index}") String indexName,
                                       @Value("${spring.ai.vectorstore.redis.prefix:rag-leaf-prefix}") String prefix,
                                       ObjectProvider<ObservationRegistry> observationRegistryProvider) {
        return RedisVectorStore.builder(jedisPooled(jedisConnectionFactory), embeddingModel)
                .initializeSchema(initializeSchema)
                .observationRegistry(observationRegistryProvider.getIfAvailable(() -> ObservationRegistry.NOOP))
                .indexName(indexName)
                .prefix(prefix)
                .build();
    }

    @Bean
    public VectorStore summaryVectorStore(EmbeddingModel embeddingModel,
                                          JedisConnectionFactory jedisConnectionFactory,
                                          @Value("${spring.ai.vectorstore.redis.initialize-schema:true}") boolean initializeSchema,
                                          @Value("${spring.ai.vectorstore.redis.summary-index-name:rag-summary-index}") String indexName,
                                          @Value("${spring.ai.vectorstore.redis.summary-prefix:rag-summary-prefix}") String prefix,
                                          ObjectProvider<ObservationRegistry> observationRegistryProvider) {
        return RedisVectorStore.builder(jedisPooled(jedisConnectionFactory), embeddingModel)
                .initializeSchema(initializeSchema)
                .observationRegistry(observationRegistryProvider.getIfAvailable(() -> ObservationRegistry.NOOP))
                .indexName(indexName)
                .prefix(prefix)
                .build();
    }

    private JedisPooled jedisPooled(JedisConnectionFactory jedisConnectionFactory) {
        DefaultJedisClientConfig clientConfig = DefaultJedisClientConfig.builder()
                .ssl(jedisConnectionFactory.isUseSsl())
                .clientName(jedisConnectionFactory.getClientName())
                .timeoutMillis(jedisConnectionFactory.getTimeout())
                .password(jedisConnectionFactory.getPassword())
                .build();

        return new JedisPooled(
                new HostAndPort(jedisConnectionFactory.getHostName(), jedisConnectionFactory.getPort()),
                clientConfig
        );
    }
}
