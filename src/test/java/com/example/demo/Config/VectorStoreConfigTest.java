package com.example.demo.Config;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.test.util.ReflectionTestUtils;

import redis.clients.jedis.JedisPooled;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class VectorStoreConfigTest {

    @Test
    void shouldRegisterMetadataFieldsNeededForScopedRetrieval() {
        VectorStoreConfig config = new VectorStoreConfig();

        @SuppressWarnings("unchecked")
        ObjectProvider<ObservationRegistry> observationProvider = Mockito.mock(ObjectProvider.class);
        when(observationProvider.getIfAvailable(any(Supplier.class)))
                .thenAnswer(invocation -> invocation.<Supplier<ObservationRegistry>>getArgument(0).get());

        JedisConnectionFactory jedisConnectionFactory = Mockito.mock(JedisConnectionFactory.class);
        when(jedisConnectionFactory.isUseSsl()).thenReturn(false);
        when(jedisConnectionFactory.getClientName()).thenReturn("test");
        when(jedisConnectionFactory.getTimeout()).thenReturn(30_000);
        when(jedisConnectionFactory.getPassword()).thenReturn("secret");
        when(jedisConnectionFactory.getHostName()).thenReturn("127.0.0.1");
        when(jedisConnectionFactory.getPort()).thenReturn(6379);

        RedisVectorStore store = (RedisVectorStore) config.leafVectorStore(
                Mockito.mock(EmbeddingModel.class),
                Mockito.mock(JedisPooled.class),
                false,
                "test-index",
                "test-prefix",
                observationProvider
        );

        @SuppressWarnings("unchecked")
        List<RedisVectorStore.MetadataField> metadataFields =
                (List<RedisVectorStore.MetadataField>) ReflectionTestUtils.getField(store, "metadataFields");

        Set<String> metadataFieldNames = metadataFields.stream()
                .map(RedisVectorStore.MetadataField::name)
                .collect(Collectors.toSet());

        assertEquals(VectorStoreConfig.defaultMetadataFields().size(), metadataFields.size());
        assertTrue(metadataFieldNames.contains("user_id"));
        assertTrue(metadataFieldNames.contains("source_id"));
        assertTrue(metadataFieldNames.contains("filename"));
        assertTrue(metadataFieldNames.contains("node_type"));
        assertTrue(metadataFieldNames.contains("chunk_index"));
    }
}
