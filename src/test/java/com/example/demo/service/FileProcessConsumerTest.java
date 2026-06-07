package com.example.demo.service;

import com.example.demo.model.DocumentFileStatus;
import com.example.demo.model.RagNodeType;
import com.example.demo.model.RagUnit;
import com.example.demo.model.SourceType;
import com.example.demo.model.dto.FileProcessTask;
import com.example.demo.service.processor.MediaProcessor;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileProcessConsumerTest {

    @Mock
    private RagUnitService ragUnitService;

    @Mock
    private DocumentFileService documentFileService;

    @Mock
    private HierarchicalIndexingService hierarchicalIndexingService;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private Channel channel;

    @Mock
    private MediaProcessor mediaProcessor;

    @Test
    void shouldCleanupIndexedDataAndMarkFailedWhenVectorizationFails() throws Exception {
        FileProcessConsumer consumer = spy(new FileProcessConsumer(
                ragUnitService,
                documentFileService,
                hierarchicalIndexingService,
                transactionTemplate
        ));
        FileProcessTask task = task();
        List<RagUnit> leafUnits = List.of(unit("leaf-1"));
        List<RagUnit> allUnits = List.of(unit("leaf-1"));

        doReturn(new ByteArrayInputStream("ok".getBytes())).when(consumer).downloadFromMinio(task.getMinioUrl());
        when(documentFileService.isActive(task.getUserId(), task.getFileHash())).thenReturn(true);
        when(ragUnitService.findProcessorByMimeType(task.getMimeType())).thenReturn(mediaProcessor);
        when(mediaProcessor.process(any(), eq(task.getFilename()), eq(task.getMimeType()), eq(task.getMinioUrl())))
                .thenReturn(leafUnits);
        when(hierarchicalIndexingService.buildHierarchy(task.getSourceId(), task.getFilename(), leafUnits)).thenReturn(allUnits);
        doThrow(new RuntimeException("redis write failed")).when(consumer).saveDataWithTransaction(allUnits, task);
        when(channel.isOpen()).thenReturn(true);

        consumer.processFile(task, channel, 7L);

        verify(ragUnitService).removeIndexedData(task.getSourceId());
        verify(documentFileService).markFailed(task.getUserId(), task.getFileHash(), "文件处理失败，请稍后重试");
        verify(documentFileService, never()).updateStatus(task.getUserId(), task.getFileHash(), DocumentFileStatus.SUCCESS, 1, null);
        verify(channel).basicAck(7L, false);
    }

    @Test
    void shouldAckWithoutCleanupWhenProcessingCancelled() throws Exception {
        FileProcessConsumer consumer = spy(new FileProcessConsumer(
                ragUnitService,
                documentFileService,
                hierarchicalIndexingService,
                transactionTemplate
        ));
        FileProcessTask task = task();

        doReturn(new ByteArrayInputStream("ok".getBytes())).when(consumer).downloadFromMinio(task.getMinioUrl());
        when(documentFileService.isActive(task.getUserId(), task.getFileHash())).thenReturn(false);
        when(channel.isOpen()).thenReturn(true);

        consumer.processFile(task, channel, 9L);

        verify(ragUnitService, never()).removeIndexedData(any(), any());
        verify(documentFileService, never()).markFailed(any(), any(), any());
        verify(channel).basicAck(9L, false);
    }

    private static FileProcessTask task() {
        return FileProcessTask.builder()
                .sourceId("source-1")
                .filename("sample.docx")
                .fileHash("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .userId("user-1")
                .mimeType("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                .minioPath("source-1/sample.docx")
                .minioUrl("http://localhost/sample.docx")
                .build();
    }

    private static RagUnit unit(String id) {
        RagUnit unit = new RagUnit();
        unit.setId(id);
        unit.setContent("content");
        unit.setSourceType(SourceType.TEXT);
        unit.setNodeType(RagNodeType.LEAF);
        return unit;
    }
}
