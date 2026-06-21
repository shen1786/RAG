package com.example.demo.service.processor;

import com.example.demo.model.RagUnit;
import java.io.InputStream;
import java.util.List;

/**
 * 文件解析策略接口 —— 不同文件类型（PDF、Word、图片等）各自实现自己的解析逻辑。
 *
 * <p><b>通俗解释：</b>这是一个"拆包规范"。不管包裹是纸箱（PDF）、塑料袋（Word）还是木箱（图片），
 * 都要按这个规范拆开，取出里面的东西（文本切块）。</p>
 *
 * <p><b>策略模式：</b>系统中每个实现类负责一种文件类型：</p>
 * <ul>
 *   <li>{@link PdfProcessor} — PDF 文档</li>
 *   <li>{@link WordProcessor} — Word 文档（.doc/.docx）</li>
 *   <li>{@link PowerPointProcessor} — PPT 演示文稿</li>
 *   <li>{@link TabularProcessor} — Excel / CSV 表格</li>
 *   <li>{@link TextProcessor} — 纯文本 / Markdown</li>
 *   <li>{@link ImageProcessor} — 图片（OCR + 多模态 AI 描述）</li>
 *   <li>{@link VideoProcessor} — 视频（抽帧 + 语音转文字）</li>
 * </ul>
 *
 * <p><b>调用链路：</b>{@code FileProcessConsumer} 收到 MQ 任务后，
 * 通过 {@code MediaProcessorRegistry} 根据 MIME 类型找到对应的 Processor，
 * 然后调用 {@link #process(InputStream, String, String)} 解析文件。</p>
 *
 * @see MediaProcessorRegistry 自动发现并注册所有实现类
 */
public interface MediaProcessor {

    /**
     * 判断当前处理器是否支持指定的 MIME 类型。
     *
     * <p><b>通俗解释：</b>就像快递分拣时看标签——这个包裹是 PDF？Word？还是图片？
     * 是我负责的类型就返回 true。</p>
     *
     * @param mimeType 文件的 MIME 类型（如 "application/pdf"、"image/png"）
     * @return 支持则返回 true，否则返回 false
     */
    boolean supports(String mimeType);

    /**
     * 解析文件，将其内容切成多个 {@link RagUnit}（文档切块）。
     *
     * <p><b>通俗解释：</b>把一整篇文档"切"成一段一段的小块，
     * 每个小块后续都会被向量化，存入 Redis，供语义检索使用。</p>
     *
     * <p><b>以 PDF 为例：</b>一个 10 页的 PDF 可能会被切成 20~30 个段落级别的切块，
     * 每个切块包含原文文本、所属页码、文件名等元数据。</p>
     *
     * @param input    文件的输入流（调用方负责关闭）
     * @param filename 原始文件名（如 "报告.pdf"）
     * @param mimeType 文件的 MIME 类型
     * @return 解析后的叶子切块列表（不含摘要节点，摘要由上层的 HierarchicalIndexingService 构建）
     */
    List<RagUnit> process(InputStream input, String filename, String mimeType);

    /**
     * 解析文件（支持文件 URL），用于需要访问原始文件地址的场景（如图片的多模态 AI 描述）。
     *
     * <p><b>为什么需要这个重载方法：</b>普通文本类文件只需要读流就够了，
     * 但图片类文件需要把 URL 传给多模态大模型（如通义千问-VL）来生成描述，
     * 所以多了一个 fileUrl 参数。</p>
     *
     * <p><b>默认实现：</b>直接调用三参数版本（忽略 fileUrl）。
     * 只有 {@link ImageProcessor} 等需要 URL 的实现类才会覆写此方法。</p>
     *
     * @param input    文件的输入流
     * @param filename 原始文件名
     * @param mimeType 文件的 MIME 类型
     * @param fileUrl  文件在 MinIO 中的访问地址（带签名的临时 URL）
     * @return 解析后的叶子切块列表
     */
    default List<RagUnit> process(InputStream input, String filename, String mimeType, String fileUrl) {
        return process(input, filename, mimeType);
    }
}
