package com.dingring.infrastructure.rag.parser;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MarkdownStructureParser {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern FENCE = Pattern.compile("^\\s*(`{3,}|~{3,})\\s*([^\\s`]*)?.*$");
    private static final Pattern LIST = Pattern.compile("^\\s*(?:[-+*]|\\d+[.)])\\s+.+$");
    private static final Pattern ORDERED_LIST = Pattern.compile("^\\s*\\d+[.)]\\s+.+$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\s*\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$");
    private static final Pattern THEMATIC_BREAK = Pattern.compile("^\\s{0,3}(?:([-*_])\\s*){3,}$");
    private static final Pattern FAQ_QUESTION = Pattern.compile("^\\s*(?:Q(?:uestion)?|问题|问)[:：]\\s*.+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern FAQ_ANSWER = Pattern.compile("^\\s*(?:A(?:nswer)?|答案|答)[:：]\\s*.+$", Pattern.CASE_INSENSITIVE);

    public List<MarkdownBlock> parse(String markdown, String documentTitle) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        List<Line> lines = lines(markdown);
        List<Heading> headings = new ArrayList<>();
        if (documentTitle != null && !documentTitle.isBlank()) {
            headings.add(new Heading(0, documentTitle.trim()));
        }
        List<MarkdownBlock> result = new ArrayList<>();
        int cursor = 0;
        if (isFrontMatterStart(lines)) {
            int end = 1;
            while (end < lines.size() && !lines.get(end).text().trim().equals("---")) {
                end++;
            }
            if (end < lines.size()) {
                result.add(block(result.size(), BlockType.FRONT_MATTER, lines, 0, end,
                        headingPath(headings), "", Map.of()));
                cursor = end + 1;
            }
        }

        while (cursor < lines.size()) {
            if (lines.get(cursor).text().isBlank()) {
                cursor++;
                continue;
            }
            String text = lines.get(cursor).text();
            Matcher fence = FENCE.matcher(text);
            if (fence.matches()) {
                String marker = fence.group(1);
                String language = fence.group(2) == null ? "" : fence.group(2);
                int end = cursor + 1;
                while (end < lines.size() && !closesFence(lines.get(end).text(), marker)) {
                    end++;
                }
                if (end >= lines.size()) {
                    end = lines.size() - 1;
                }
                result.add(block(result.size(), BlockType.CODE_FENCE, lines, cursor, end,
                        headingPath(headings), language, Map.of("closed", closesFence(lines.get(end).text(), marker))));
                cursor = end + 1;
                continue;
            }

            Matcher heading = HEADING.matcher(text);
            if (heading.matches()) {
                int level = heading.group(1).length();
                String title = heading.group(2).trim();
                while (!headings.isEmpty() && headings.get(headings.size() - 1).level() >= level) {
                    headings.remove(headings.size() - 1);
                }
                headings.add(new Heading(level, title));
                result.add(block(result.size(), BlockType.HEADING, lines, cursor, cursor,
                        headingPath(headings), "", Map.of("level", level, "normalizedTitle", normalizeTitle(title))));
                cursor++;
                continue;
            }

            if (isTableStart(lines, cursor)) {
                int end = cursor + 2;
                while (end < lines.size() && isTableRow(lines.get(end).text())) {
                    end++;
                }
                result.add(block(result.size(), BlockType.TABLE, lines, cursor, end - 1,
                        headingPath(headings), "", Map.of("headerRows", 2)));
                cursor = end;
                continue;
            }

            if (LIST.matcher(text).matches()) {
                int end = cursor + 1;
                boolean ordered = ORDERED_LIST.matcher(text).matches();
                while (end < lines.size() && belongsToList(lines.get(end).text())) {
                    end++;
                }
                result.add(block(result.size(), BlockType.LIST, lines, cursor, end - 1,
                        headingPath(headings), "", Map.of("ordered", ordered)));
                cursor = end;
                continue;
            }

            if (text.stripLeading().startsWith(">")) {
                int end = cursor + 1;
                while (end < lines.size() && lines.get(end).text().stripLeading().startsWith(">")) {
                    end++;
                }
                result.add(block(result.size(), BlockType.QUOTE, lines, cursor, end - 1,
                        headingPath(headings), "", Map.of()));
                cursor = end;
                continue;
            }

            if (THEMATIC_BREAK.matcher(text).matches()) {
                result.add(block(result.size(), BlockType.THEMATIC_BREAK, lines, cursor, cursor,
                        headingPath(headings), "", Map.of()));
                cursor++;
                continue;
            }

            if (looksLikeHtml(text)) {
                int end = cursor + 1;
                while (end < lines.size() && !lines.get(end).text().isBlank()
                        && looksLikeHtml(lines.get(end).text())) {
                    end++;
                }
                result.add(block(result.size(), BlockType.HTML_BLOCK, lines, cursor, end - 1,
                        headingPath(headings), "", Map.of()));
                cursor = end;
                continue;
            }

            if (FAQ_QUESTION.matcher(text).matches()) {
                int end = cursor + 1;
                boolean hasAnswer = false;
                while (end < lines.size() && !lines.get(end).text().isBlank() && !isBoundary(lines, end)) {
                    hasAnswer |= FAQ_ANSWER.matcher(lines.get(end).text()).matches();
                    end++;
                }
                Map<String, Object> attributes = new LinkedHashMap<>();
                attributes.put("contentType", "faq");
                attributes.put("hasAnswer", hasAnswer);
                result.add(block(result.size(), BlockType.PARAGRAPH, lines, cursor, end - 1,
                        headingPath(headings), "", attributes));
                cursor = end;
                continue;
            }

            int end = cursor + 1;
            while (end < lines.size() && !lines.get(end).text().isBlank() && !isBoundary(lines, end)) {
                end++;
            }
            result.add(block(result.size(), BlockType.PARAGRAPH, lines, cursor, end - 1,
                    headingPath(headings), "", Map.of()));
            cursor = end;
        }
        return List.copyOf(result);
    }

    private static boolean isBoundary(List<Line> lines, int index) {
        String text = lines.get(index).text();
        return FENCE.matcher(text).matches()
                || HEADING.matcher(text).matches()
                || LIST.matcher(text).matches()
                || text.stripLeading().startsWith(">")
                || THEMATIC_BREAK.matcher(text).matches()
                || FAQ_QUESTION.matcher(text).matches()
                || isTableStart(lines, index)
                || looksLikeHtml(text);
    }

    private static boolean isFrontMatterStart(List<Line> lines) {
        return !lines.isEmpty() && lines.get(0).text().trim().equals("---");
    }

    private static boolean closesFence(String line, String marker) {
        char delimiter = marker.charAt(0);
        int required = marker.length();
        String stripped = line.stripLeading();
        int count = 0;
        while (count < stripped.length() && stripped.charAt(count) == delimiter) {
            count++;
        }
        return count >= required && stripped.substring(count).isBlank();
    }

    private static boolean isTableStart(List<Line> lines, int index) {
        return index + 1 < lines.size()
                && isTableRow(lines.get(index).text())
                && TABLE_SEPARATOR.matcher(lines.get(index + 1).text()).matches();
    }

    private static boolean isTableRow(String line) {
        return line.indexOf('|') >= 0 && !line.isBlank();
    }

    private static boolean belongsToList(String line) {
        if (line.isBlank()) {
            return false;
        }
        return LIST.matcher(line).matches() || Character.isWhitespace(line.charAt(0));
    }

    private static boolean looksLikeHtml(String line) {
        String value = line.stripLeading();
        return value.startsWith("<") && value.length() > 1 && (Character.isLetter(value.charAt(1)) || value.charAt(1) == '/');
    }

    private static MarkdownBlock block(int index, BlockType type, List<Line> lines, int start, int end,
                                       List<String> headingPath, String language,
                                       Map<String, Object> attributes) {
        Line first = lines.get(start);
        Line last = lines.get(end);
        StringBuilder text = new StringBuilder();
        for (int i = start; i <= end; i++) {
            if (i > start) {
                text.append('\n');
            }
            text.append(lines.get(i).text());
        }
        return new MarkdownBlock(index, type, text.toString(), headingPath,
                first.number(), last.number(), first.startOffset(), last.endOffset(), language, attributes);
    }

    private static List<String> headingPath(List<Heading> headings) {
        return headings.stream().map(Heading::title).toList();
    }

    private static String normalizeTitle(String title) {
        return title.replaceAll("[`*_~]", "").replaceAll("\\s+", " ").trim().toLowerCase();
    }

    private static List<Line> lines(String markdown) {
        List<Line> result = new ArrayList<>();
        int start = 0;
        int number = 1;
        for (int i = 0; i <= markdown.length(); i++) {
            if (i == markdown.length() || markdown.charAt(i) == '\n') {
                int end = i;
                if (end > start && markdown.charAt(end - 1) == '\r') {
                    end--;
                }
                result.add(new Line(number++, markdown.substring(start, end), start, end));
                start = i + 1;
            }
        }
        return result;
    }

    private record Line(int number, String text, int startOffset, int endOffset) {
    }

    private record Heading(int level, String title) {
    }
}
