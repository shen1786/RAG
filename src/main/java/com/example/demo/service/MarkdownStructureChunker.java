package com.example.demo.service;

import com.example.demo.model.RagUnit;
import com.example.demo.model.SourceType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 结构化分块器。
 * <p>
 * 核心职责：将 Markdown 文档按照结构语义（标题、段落、列表、表格、代码块）解析为语义完整的块，
 * 然后根据配置的最大字符数将这些块分割成适合向量化的 RagUnit。
 * </p>
 *
 * <h3>分块策略</h3>
 * <ol>
 *   <li><b>结构解析</b>：逐行扫描 Markdown，识别标题层级（H1-H6）、代码块（```/~~~）、
 *       列表（有序/无序）、表格（| 分隔）和段落，维护标题路径栈</li>
 *   <li><b>智能分割</b>：根据块类型采用不同分割策略：
 *       <ul>
 *         <li>段落：按句子边界（BreakIterator）分割，保留语义完整性</li>
 *         <li>列表/表格：按行分割，保持条目独立性</li>
 *         <li>代码块：委托给 TextSplitterService 按字符数硬切</li>
 *       </ul>
 *   </li>
 *   <li><b>合并优化</b>：将过小的片段合并直到接近 maxCharsPerChunk，减少碎片化</li>
 * </ol>
 *
 * <h3>标题路径</h3>
 * 每个块都携带从根标题到当前标题的路径（如 "第一章 / 第一节 / 小节1"），
 * 作为 RagUnit 的 title 字段，为检索结果提供上下文层级信息。
 *
 * @see TextSplitterService
 * @see RagUnit
 */
@Service
public class MarkdownStructureChunker {

    /** 标题正则：匹配 1-6 个 # 开头的行，捕获标题级别和标题文本 */
    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$");

    /** 有序列表正则：匹配 "1. " 开头的行 */
    private static final Pattern ORDERED_LIST_PATTERN = Pattern.compile("^\\d+\\.\\s+.+$");

    /** 文本分割服务，用于处理超长文本的硬切分 */
    private final TextSplitterService textSplitterService;

    /** 每个 chunk 的最大字符数，超过则触发分割逻辑 */
    @Value("${chunking.structured.max-chars-per-chunk:1200}")
    private int maxCharsPerChunk;

    public MarkdownStructureChunker(TextSplitterService textSplitterService) {
        this.textSplitterService = textSplitterService;
    }

    /**
     * 将 Markdown 文档分块为 RagUnit 列表。
     * <p>
     * 流程：解析 Markdown 为结构化块 → 对每个块按类型和大小分割 → 组装为带标题路径的 RagUnit。
     *
     * @param markdown   原始 Markdown 文本
     * @param filename   文件名，用于无标题块的 fallback title
     * @param sourceType 来源类型（文档/网页等）
     * @return 分块后的 RagUnit 列表，每个 unit 包含标题路径和内容
     */
    public List<RagUnit> chunk(String markdown, String filename, SourceType sourceType) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }

        List<Block> blocks = extractBlocks(markdown);
        List<RagUnit> units = new ArrayList<>();
        int chunkIndex = 0;

        for (Block block : blocks) {
            List<String> chunkTexts = splitBlock(block);
            for (String chunkText : chunkTexts) {
                if (chunkText == null || chunkText.isBlank()) {
                    continue;
                }
                RagUnit unit = new RagUnit();
                unit.setSourceType(sourceType);
                unit.setTitle(block.headingPath().isBlank() ? filename : block.headingPath());
                unit.setContent(renderContent(block.headingPath(), chunkText));
                unit.setChunkIndex(chunkIndex++);
                units.add(unit);
            }
        }

        return units;
    }

    /**
     * 将 Markdown 文本解析为结构化块列表。
     * <p>
     * 逐行扫描，识别以下结构：
     * <ul>
     *   <li>代码块（```/~~~ 开始和结束）</li>
     *   <li>标题（# - ######）</li>
     *   <li>空行（块分隔符）</li>
     *   <li>列表、表格、段落</li>
     * </ul>
     * 同时维护标题路径栈，记录当前块所属的层级位置。
     *
     * @param markdown 原始 Markdown 文本
     * @return 解析后的 Block 列表
     */
    private List<Block> extractBlocks(String markdown) {
        List<Block> blocks = new ArrayList<>();
        List<String> headingStack = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        BlockType currentType = null;
        boolean inCodeBlock = false;

        for (String rawLine : markdown.replace("\r\n", "\n").split("\n", -1)) {
            String line = rawLine.stripTrailing();
            String trimmed = line.trim();

            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                if (!inCodeBlock) {
                    flushBlock(blocks, current, currentType, currentHeadingPath(headingStack));
                    currentType = BlockType.CODE;
                    inCodeBlock = true;
                }
                current.append(line).append('\n');
                if (inCodeBlock && trimmed.length() >= 3 && (trimmed.startsWith("```") || trimmed.startsWith("~~~"))
                        && current.length() > trimmed.length() + 1) {
                    flushBlock(blocks, current, currentType, currentHeadingPath(headingStack));
                    currentType = null;
                    inCodeBlock = false;
                }
                continue;
            }

            if (inCodeBlock) {
                current.append(line).append('\n');
                continue;
            }

            Matcher headingMatcher = HEADING_PATTERN.matcher(trimmed);
            if (headingMatcher.matches()) {
                flushBlock(blocks, current, currentType, currentHeadingPath(headingStack));
                updateHeadingStack(headingStack, headingMatcher.group(2).trim(), headingMatcher.group(1).length());
                currentType = null;
                continue;
            }

            if (trimmed.isEmpty()) {
                flushBlock(blocks, current, currentType, currentHeadingPath(headingStack));
                currentType = null;
                continue;
            }

            BlockType lineType = detectBlockType(trimmed);
            if (currentType != null && currentType != lineType) {
                flushBlock(blocks, current, currentType, currentHeadingPath(headingStack));
            }

            if (currentType == null) {
                currentType = lineType;
            }

            current.append(line).append('\n');
        }

        flushBlock(blocks, current, currentType, currentHeadingPath(headingStack));
        return blocks;
    }

    /**
     * 将当前累积的文本刷新为一个 Block 并添加到列表中。
     * <p>
     * 仅当 type 非 null 且文本非空时才创建 Block，然后清空 StringBuilder。
     *
     * @param blocks      目标 Block 列表
     * @param current     当前累积的文本内容
     * @param type        当前块类型（为 null 则跳过）
     * @param headingPath 当前标题路径
     */
    private void flushBlock(List<Block> blocks, StringBuilder current, BlockType type, String headingPath) {
        if (current == null || current.isEmpty() || type == null) {
            current.setLength(0);
            return;
        }

        String text = current.toString().trim();
        current.setLength(0);
        if (text.isBlank()) {
            return;
        }

        blocks.add(new Block(type, headingPath, text));
    }

    /**
     * 更新标题层级栈。
     * <p>
     * 维护一个表示当前标题层级的栈。当遇到新标题时：
     * <ul>
     *   <li>如果新标题级别 <= 栈深度，弹出栈顶直到栈深度 < 新级别</li>
     *   <li>将新标题压入栈顶</li>
     * </ul>
     * 例如：遇到 H2 时，会弹出所有 H2 及以下级别的标题，然后压入新 H2。
     *
     * @param headingStack 标题栈
     * @param heading      新标题文本
     * @param level        新标题级别（1-6）
     */
    private void updateHeadingStack(List<String> headingStack, String heading, int level) {
        while (headingStack.size() >= level) {
            headingStack.remove(headingStack.size() - 1);
        }
        headingStack.add(heading);
    }

    /**
     * 获取当前标题路径字符串。
     * <p>
     * 将标题栈中的各级标题用 " / " 连接，形成类似 "第一章 / 第一节 / 小节1" 的路径。
     * 空标题会被过滤掉。
     *
     * @param headingStack 标题栈
     * @return 标题路径字符串，栈为空时返回空字符串
     */
    private String currentHeadingPath(List<String> headingStack) {
        return headingStack.stream()
                .filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + " / " + right)
                .orElse("");
    }

    /**
     * 检测单行文本的块类型。
     * <p>
     * 判断规则：
     * <ul>
     *   <li>以 | 开头或结尾 → TABLE</li>
     *   <li>以 "- ", "* ", "+ " 或 "数字. " 开头 → LIST</li>
     *   <li>其他 → PARAGRAPH</li>
     * </ul>
     *
     * @param trimmedLine 已 trim 的行文本
     * @return 检测到的 BlockType
     */
    private BlockType detectBlockType(String trimmedLine) {
        if (trimmedLine.startsWith("|") || trimmedLine.endsWith("|")) {
            return BlockType.TABLE;
        }
        if (trimmedLine.startsWith("- ") || trimmedLine.startsWith("* ") || trimmedLine.startsWith("+ ")
                || ORDERED_LIST_PATTERN.matcher(trimmedLine).matches()) {
            return BlockType.LIST;
        }
        return BlockType.PARAGRAPH;
    }

    /**
     * 将单个 Block 分割为适合向量化的文本片段。
     * <p>
     * 分割策略根据块类型而异：
     * <ul>
     *   <li><b>PARAGRAPH</b>：优先按句子边界分割（BreakIterator），超长句子再委托 TextSplitterService</li>
     *   <li><b>LIST/TABLE</b>：按行分割，保持每行独立性</li>
     *   <li><b>CODE</b>：委托 TextSplitterService 按字符数硬切</li>
     * </ul>
     * <p>
     * 分割后的片段会进行合并优化：连续小片段会合并直到接近 maxCharsPerChunk，
     * 以减少碎片化并保持上下文连贯性。
     *
     * @param block 待分割的 Block
     * @return 分割后的文本片段列表
     */
    private List<String> splitBlock(Block block) {
        String normalized = normalizeWhitespace(block.text(), block.type());
        if (normalized.length() <= maxCharsPerChunk) {
            return List.of(normalized);
        }

        if (block.type() == BlockType.PARAGRAPH) {
            List<String> sentences = new ArrayList<>();
            for (String sentence : splitBySentence(normalized)) {
                if (sentence.length() > maxCharsPerChunk) {
                    sentences.addAll(textSplitterService.splitText(sentence));
                } else {
                    sentences.add(sentence);
                }
            }
            return sentences;
        }

        List<String> segments = switch (block.type()) {
            case LIST, TABLE -> splitByLines(normalized);
            case CODE -> textSplitterService.splitText(normalized);
            case PARAGRAPH -> List.of(normalized);
        };

        if (segments.isEmpty()) {
            return textSplitterService.splitText(normalized);
        }

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String segment : segments) {
            if (segment.length() > maxCharsPerChunk) {
                if (!current.isEmpty()) {
                    chunks.add(current.toString().trim());
                    current.setLength(0);
                }
                chunks.addAll(textSplitterService.splitText(segment));
                continue;
            }

            if (current.isEmpty()) {
                current.append(segment);
                continue;
            }

            if (current.length() + 1 + segment.length() > maxCharsPerChunk) {
                chunks.add(current.toString().trim());
                current.setLength(0);
                current.append(segment);
            } else {
                current.append(needsNewline(block.type()) ? '\n' : ' ').append(segment);
            }
        }

        if (!current.isEmpty()) {
            chunks.add(current.toString().trim());
        }

        return chunks;
    }

    /**
     * 判断指定块类型在合并片段时是否需要换行符分隔。
     * <p>
     * 列表、表格和代码块需要换行符以保持格式，段落则用空格连接。
     *
     * @param type 块类型
     * @return true 表示用换行符分隔，false 表示用空格分隔
     */
    private boolean needsNewline(BlockType type) {
        return type == BlockType.LIST || type == BlockType.TABLE || type == BlockType.CODE;
    }

    /**
     * 按行分割文本，过滤空行。
     * <p>
     * 适用于 LIST 和 TABLE 类型，保持每行（列表项/表格行）的独立性。
     *
     * @param text 待分割的文本
     * @return 非空行列表
     */
    private List<String> splitByLines(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isBlank()) {
                lines.add(trimmed);
            }
        }
        return lines;
    }

    /**
     * 按句子边界分割文本。
     * <p>
     * 使用 Java 的 BreakIterator（Locale.CHINA）识别句子边界，
     * 保留句子完整性，避免在句子中间截断导致语义丢失。
     * 如果无法识别句子（如纯标点），则将整个文本作为一个句子返回。
     *
     * @param text 待分割的文本
     * @return 句子列表
     */
    private List<String> splitBySentence(String text) {
        List<String> sentences = new ArrayList<>();
        BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.CHINA);
        iterator.setText(text);
        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            String sentence = text.substring(start, end).trim();
            if (!sentence.isBlank()) {
                sentences.add(sentence);
            }
        }
        if (sentences.isEmpty()) {
            sentences.add(text);
        }
        return sentences;
    }

    /**
     * 规范化文本中的空白字符。
     * <p>
     * 规范化策略：
     * <ul>
     *   <li><b>CODE/TABLE</b>：仅 trim，保留原始格式（代码缩进和表格对齐很重要）</li>
     *   <li><b>PARAGRAPH/LIST</b>：将连续空白合并为单个空格，消除多余空格</li>
     * </ul>
     *
     * @param text 原始文本
     * @param type 块类型
     * @return 规范化后的文本
     */
    private String normalizeWhitespace(String text, BlockType type) {
        if (type == BlockType.CODE || type == BlockType.TABLE) {
            return text.trim();
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    /**
     * 渲染最终内容，将标题路径与正文拼接。
     * <p>
     * 如果有标题路径，则在正文前添加标题路径作为上下文前缀，
     * 帮助后续向量化和检索时理解块的层级位置。
     *
     * @param headingPath 标题路径（可为 null 或空）
     * @param body        正文内容
     * @return 拼接后的完整内容
     */
    private String renderContent(String headingPath, String body) {
        if (headingPath == null || headingPath.isBlank()) {
            return body.trim();
        }
        return headingPath + "\n" + body.trim();
    }

    /**
     * Markdown 块类型枚举。
     * <p>
     * 不同类型决定不同的分割策略：
     * <ul>
     *   <li>PARAGRAPH - 段落，按句子分割</li>
     *   <li>LIST - 列表，按行分割</li>
     *   <li>TABLE - 表格，按行分割</li>
     *   <li>CODE - 代码块，按字符数硬切</li>
     * </ul>
     */
    private enum BlockType {
        PARAGRAPH,
        LIST,
        TABLE,
        CODE
    }

    /**
     * 解析后的 Markdown 块。
     *
     * @param type        块类型
     * @param headingPath 标题路径（如 "第一章 / 第一节"）
     * @param text        块的原始文本内容
     */
    private record Block(BlockType type, String headingPath, String text) {
    }
}
