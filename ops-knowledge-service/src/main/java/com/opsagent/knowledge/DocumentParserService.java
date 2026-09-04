package com.opsagent.knowledge;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.BodyElementType;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将 Markdown、DOCX、PDF 和 TXT 解析为结构块，并按 Token 预算生成可追踪切片。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
public class DocumentParserService {
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,3})\\s+(.+?)\\s*$");
    private static final Pattern LIST_LINE = Pattern.compile("^\\s*(?:[-*+]\\s+|\\d+[.)]\\s+).+");
    private static final Pattern DOCX_HEADING = Pattern.compile("(?i).*heading\\s*([1-3]).*");
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[。！？!?；;])\\s*|\\n+");

    private final KnowledgeProperties properties;
    private final TokenCounter tokenCounter;

    DocumentParserService(KnowledgeProperties properties, TokenCounter tokenCounter) {
        this.properties = properties;
        this.tokenCounter = tokenCounter;
    }

    List<StructuredChunk> parse(
            Path path,
            String extension,
            String documentTitle,
            int documentVersion) throws IOException {
        validateConfiguration();
        String normalizedExtension = extension.toLowerCase(Locale.ROOT);
        List<DocumentBlock> blocks = switch (normalizedExtension) {
            case "md", "markdown" -> parseMarkdown(path, documentTitle);
            case "txt" -> parseText(path, documentTitle);
            case "docx" -> parseDocx(path, documentTitle);
            case "pdf" -> parsePdf(path, documentTitle);
            default -> throw new IllegalArgumentException("不支持的文档类型：" + extension);
        };
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("文档没有可解析文本，扫描 PDF 暂不支持 OCR");
        }
        return chunks(blocks, normalizedExtension.toUpperCase(Locale.ROOT), documentVersion);
    }

    List<StructuredChunk> chunks(
            List<DocumentBlock> sourceBlocks,
            String sourceFormat,
            int documentVersion) {
        validateConfiguration();
        List<DocumentBlock> blocks = new ArrayList<>();
        for (DocumentBlock block : sourceBlocks) {
            blocks.addAll(splitOversized(block));
        }
        List<List<DocumentBlock>> drafts = buildDrafts(blocks);
        mergeShortDrafts(drafts);
        addOverlap(drafts);
        List<StructuredChunk> result = new ArrayList<>();
        for (List<DocumentBlock> draft : drafts) {
            if (!draft.isEmpty()) {
                result.add(toChunk(draft, sourceFormat, documentVersion));
            }
        }
        return result;
    }

    private List<DocumentBlock> parseMarkdown(Path path, String title) throws IOException {
        String text = clean(Files.readString(path, StandardCharsets.UTF_8));
        List<String> lines = text.lines().toList();
        List<DocumentBlock> blocks = new ArrayList<>();
        List<String> headings = new ArrayList<>(List.of(title));
        for (int index = 0; index < lines.size(); ) {
            String line = lines.get(index);
            if (line.isBlank()) {
                index++;
                continue;
            }
            Matcher heading = MARKDOWN_HEADING.matcher(line);
            if (heading.matches()) {
                int level = heading.group(1).length();
                updateHeading(headings, title, level, heading.group(2).trim());
                addBlock(blocks, BlockType.HEADING, line.trim(), headings, null, null);
                index++;
                continue;
            }
            if (line.stripLeading().startsWith("```")) {
                int end = index + 1;
                while (end < lines.size() && !lines.get(end).stripLeading().startsWith("```")) {
                    end++;
                }
                end = Math.min(lines.size(), end + 1);
                addBlock(blocks, BlockType.CODE, join(lines, index, end), headings, null, null);
                index = end;
                continue;
            }
            BlockType type = markdownType(line);
            int end = index + 1;
            while (end < lines.size()
                    && !lines.get(end).isBlank()
                    && !MARKDOWN_HEADING.matcher(lines.get(end)).matches()
                    && markdownType(lines.get(end)) == type) {
                end++;
            }
            addBlock(blocks, type, join(lines, index, end), headings, null, null);
            index = end;
        }
        return blocks;
    }

    private List<DocumentBlock> parseText(Path path, String title) throws IOException {
        String text = clean(Files.readString(path, StandardCharsets.UTF_8));
        List<DocumentBlock> blocks = new ArrayList<>();
        for (String paragraph : text.split("\\n\\s*\\n")) {
            addBlock(blocks, BlockType.PARAGRAPH, paragraph, List.of(title), null, null);
        }
        return blocks;
    }

    private List<DocumentBlock> parseDocx(Path path, String title) throws IOException {
        List<DocumentBlock> blocks = new ArrayList<>();
        List<String> headings = new ArrayList<>(List.of(title));
        try (InputStream input = Files.newInputStream(path); XWPFDocument document = new XWPFDocument(input)) {
            for (IBodyElement element : document.getBodyElements()) {
                if (element.getElementType() == BodyElementType.PARAGRAPH) {
                    XWPFParagraph paragraph = (XWPFParagraph) element;
                    String text = clean(paragraph.getText());
                    if (text.isBlank()) {
                        continue;
                    }
                    String style = paragraph.getStyle() == null ? "" : paragraph.getStyle();
                    Matcher matcher = DOCX_HEADING.matcher(style);
                    if (matcher.matches()) {
                        updateHeading(headings, title, Integer.parseInt(matcher.group(1)), text);
                        addBlock(blocks, BlockType.HEADING, text, headings, null, null);
                    } else if (style.equalsIgnoreCase("Title")) {
                        addBlock(blocks, BlockType.TITLE, text, headings, null, null);
                    } else {
                        BlockType type = paragraph.getNumID() == null
                                ? BlockType.PARAGRAPH : BlockType.LIST;
                        addBlock(blocks, type, text, headings, null, null);
                    }
                } else if (element.getElementType() == BodyElementType.TABLE) {
                    addBlock(
                            blocks,
                            BlockType.TABLE,
                            tableText((XWPFTable) element),
                            headings,
                            null,
                            null);
                }
            }
        } catch (RuntimeException exception) {
            throw new IOException("DOCX 文档内容无法解析", exception);
        }
        return blocks;
    }

    private List<DocumentBlock> parsePdf(Path path, String title) throws IOException {
        List<DocumentBlock> blocks = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = clean(stripper.getText(document));
                if (pageText.isBlank()) {
                    continue;
                }
                for (String paragraph : pageText.split("\\n\\s*\\n")) {
                    addBlock(
                            blocks,
                            BlockType.PARAGRAPH,
                            paragraph,
                            List.of(title),
                            page,
                            page);
                }
            }
        } catch (RuntimeException exception) {
            throw new IOException("PDF 文档内容无法解析", exception);
        }
        return blocks;
    }

    private List<List<DocumentBlock>> buildDrafts(List<DocumentBlock> blocks) {
        int target = properties.getChunk().getTargetTokens();
        int maximum = properties.getChunk().getMaxTokens();
        List<List<DocumentBlock>> drafts = new ArrayList<>();
        List<DocumentBlock> current = new ArrayList<>();
        for (DocumentBlock block : blocks) {
            int combinedTokens = tokenCounter.count(contentWith(current, block));
            boolean newSection = !current.isEmpty()
                    && !current.get(0).headingPath().equals(block.headingPath());
            boolean targetReached = !current.isEmpty()
                    && tokenCounter.count(content(current)) >= target;
            if (!current.isEmpty()
                    && (newSection || combinedTokens > maximum || targetReached)) {
                drafts.add(current);
                current = new ArrayList<>();
            }
            current.add(block);
        }
        if (!current.isEmpty()) {
            drafts.add(current);
        }
        return drafts;
    }

    private void mergeShortDrafts(List<List<DocumentBlock>> drafts) {
        int minimum = properties.getChunk().getMinTokens();
        int maximum = properties.getChunk().getMaxTokens();
        for (int index = 0; index < drafts.size(); ) {
            List<DocumentBlock> current = drafts.get(index);
            if (tokenCounter.count(content(current)) >= minimum) {
                index++;
                continue;
            }
            if (index + 1 < drafts.size()
                    && sameHeading(current, drafts.get(index + 1))
                    && tokenCounter.count(content(current) + "\n\n" + content(drafts.get(index + 1)))
                            <= maximum) {
                current.addAll(drafts.remove(index + 1));
                continue;
            }
            if (index > 0
                    && sameHeading(drafts.get(index - 1), current)
                    && tokenCounter.count(content(drafts.get(index - 1)) + "\n\n" + content(current))
                            <= maximum) {
                drafts.get(index - 1).addAll(current);
                drafts.remove(index);
                continue;
            }
            index++;
        }
    }

    private void addOverlap(List<List<DocumentBlock>> drafts) {
        int overlap = properties.getChunk().getOverlapTokens();
        int maximum = properties.getChunk().getMaxTokens();
        if (overlap == 0) {
            return;
        }
        for (int index = 1; index < drafts.size(); index++) {
            List<DocumentBlock> previous = drafts.get(index - 1);
            List<DocumentBlock> current = drafts.get(index);
            if (!sameHeading(previous, current)) {
                continue;
            }
            DocumentBlock tail = previous.get(previous.size() - 1);
            String overlapText = tailText(tail.text(), overlap);
            if (overlapText.isBlank()
                    || tokenCounter.count(overlapText + "\n\n" + content(current)) > maximum) {
                continue;
            }
            current.add(0, new DocumentBlock(
                    tail.type(),
                    overlapText,
                    tail.headingPath(),
                    tail.pageStart(),
                    tail.pageEnd(),
                    tail.order(),
                    Map.of("overlap", true)));
        }
    }

    private List<DocumentBlock> splitOversized(DocumentBlock block) {
        int maximum = properties.getChunk().getMaxTokens();
        if (tokenCounter.count(block.text()) <= maximum) {
            return List.of(block);
        }
        List<String> parts = block.type() == BlockType.CODE || block.type() == BlockType.TABLE
                ? splitLines(block.text(), maximum, block.type() == BlockType.TABLE)
                : splitSentences(block.text(), maximum);
        List<DocumentBlock> result = new ArrayList<>();
        for (String part : parts) {
            result.add(new DocumentBlock(
                    block.type(),
                    part,
                    block.headingPath(),
                    block.pageStart(),
                    block.pageEnd(),
                    block.order(),
                    block.metadata()));
        }
        return result;
    }

    private List<String> splitSentences(String text, int maximum) {
        return packSegments(List.of(SENTENCE_BOUNDARY.split(text)), maximum, null);
    }

    private List<String> splitLines(String text, int maximum, boolean repeatHeader) {
        List<String> lines = text.lines().toList();
        String header = repeatHeader && !lines.isEmpty() ? lines.get(0) : null;
        return packSegments(lines, maximum, header);
    }

    private List<String> packSegments(List<String> segments, int maximum, String repeatedPrefix) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String segment : segments) {
            if (segment.isBlank()) {
                continue;
            }
            String candidate = current.isEmpty() ? segment : current + "\n" + segment;
            if (!current.isEmpty() && tokenCounter.count(candidate) > maximum) {
                result.add(current.toString());
                current.setLength(0);
                if (repeatedPrefix != null && !repeatedPrefix.equals(segment)) {
                    current.append(repeatedPrefix);
                }
            }
            if (tokenCounter.count(segment) > maximum) {
                if (!current.isEmpty()) {
                    result.add(current.toString());
                    current.setLength(0);
                }
                result.addAll(hardSplit(segment, maximum));
            } else {
                if (!current.isEmpty()) {
                    current.append('\n');
                }
                current.append(segment);
            }
        }
        if (!current.isEmpty()) {
            result.add(current.toString());
        }
        return result;
    }

    private List<String> hardSplit(String text, int maximum) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            current.appendCodePoint(codePoint);
            if (tokenCounter.count(current.toString()) >= maximum) {
                result.add(current.toString());
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) {
            result.add(current.toString());
        }
        return result;
    }

    private StructuredChunk toChunk(
            List<DocumentBlock> blocks,
            String sourceFormat,
            int documentVersion) {
        String content = content(blocks);
        Set<BlockType> types = new LinkedHashSet<>();
        Integer pageStart = null;
        Integer pageEnd = null;
        for (DocumentBlock block : blocks) {
            types.add(block.type());
            if (block.pageStart() != null) {
                pageStart = pageStart == null ? block.pageStart() : Math.min(pageStart, block.pageStart());
            }
            if (block.pageEnd() != null) {
                pageEnd = pageEnd == null ? block.pageEnd() : Math.max(pageEnd, block.pageEnd());
            }
        }
        return new StructuredChunk(
                content,
                tokenCounter.count(content),
                blocks.get(0).headingPath(),
                pageStart,
                pageEnd,
                List.copyOf(types),
                types.contains(BlockType.CODE),
                types.contains(BlockType.TABLE),
                sourceFormat,
                properties.getChunk().getStrategyVersion(),
                documentVersion);
    }

    private BlockType markdownType(String line) {
        String stripped = line.stripLeading();
        if (LIST_LINE.matcher(line).matches()) {
            return BlockType.LIST;
        }
        if (stripped.startsWith(">")) {
            return BlockType.QUOTE;
        }
        if (line.contains("|") && !line.trim().equals("|")) {
            return BlockType.TABLE;
        }
        return BlockType.PARAGRAPH;
    }

    private String tableText(XWPFTable table) {
        List<String> rows = new ArrayList<>();
        for (XWPFTableRow row : table.getRows()) {
            rows.add(row.getTableCells().stream()
                    .map(cell -> clean(cell.getText()))
                    .reduce((left, right) -> left + " | " + right)
                    .orElse(""));
        }
        return String.join("\n", rows);
    }

    private void updateHeading(List<String> headings, String title, int level, String heading) {
        while (headings.size() > level) {
            headings.remove(headings.size() - 1);
        }
        while (headings.size() < level) {
            headings.add("未命名章节");
        }
        if (headings.isEmpty()) {
            headings.add(title);
        }
        if (headings.size() == level) {
            headings.add(heading);
        } else {
            headings.set(level, heading);
        }
    }

    private void addBlock(
            List<DocumentBlock> blocks,
            BlockType type,
            String text,
            List<String> headings,
            Integer pageStart,
            Integer pageEnd) {
        String normalized = clean(text);
        if (!normalized.isBlank()) {
            blocks.add(new DocumentBlock(
                    type,
                    normalized,
                    headings,
                    pageStart,
                    pageEnd,
                    blocks.size(),
                    Map.of()));
        }
    }

    private String tailText(String text, int maximumTokens) {
        List<String> segments = List.of(SENTENCE_BOUNDARY.split(text));
        String selected = "";
        for (int index = segments.size() - 1; index >= 0; index--) {
            String candidate = selected.isBlank() ? segments.get(index) : segments.get(index) + " " + selected;
            if (tokenCounter.count(candidate) > maximumTokens) {
                break;
            }
            selected = candidate;
        }
        return selected;
    }

    private boolean sameHeading(List<DocumentBlock> left, List<DocumentBlock> right) {
        return !left.isEmpty()
                && !right.isEmpty()
                && left.get(0).headingPath().equals(right.get(0).headingPath());
    }

    private String contentWith(List<DocumentBlock> blocks, DocumentBlock extra) {
        return blocks.isEmpty() ? extra.text() : content(blocks) + "\n\n" + extra.text();
    }

    private String content(List<DocumentBlock> blocks) {
        return blocks.stream()
                .map(DocumentBlock::text)
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");
    }

    private String join(List<String> lines, int start, int end) {
        return String.join("\n", lines.subList(start, end));
    }

    private String clean(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[ \\t]+(?=\\n)", "")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private void validateConfiguration() {
        KnowledgeProperties.Chunk chunk = properties.getChunk();
        if (chunk.getMinTokens() < 1
                || chunk.getTargetTokens() < chunk.getMinTokens()
                || chunk.getMaxTokens() < chunk.getTargetTokens()
                || chunk.getOverlapTokens() < 0
                || chunk.getOverlapTokens() >= chunk.getMaxTokens()
                || chunk.getStrategyVersion() == null
                || chunk.getStrategyVersion().isBlank()) {
            throw new IllegalStateException("结构化切片配置不合法");
        }
    }
}
