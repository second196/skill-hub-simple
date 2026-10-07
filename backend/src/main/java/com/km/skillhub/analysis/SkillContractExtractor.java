package com.km.skillhub.analysis;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns one skill package into an expected-step contract.
 *
 * <p>Reading is <em>reference driven</em>, not directory driven: {@code SKILL.md} is the
 * only file every skill is guaranteed to have, so it is always read. Any other file is
 * read only when SKILL.md actually points at it. Directory names such as
 * {@code references/} or {@code data/} are used to decide <em>how much</em> of a file to
 * take, never to decide <em>whether</em> to read it.
 *
 * <p>Steps come from SKILL.md. Referenced files usually hold knowledge (rule tables,
 * sample data), not the execution flow, so pulling steps out of them would invent
 * steps that the skill never asked for. Only when SKILL.md yields no step at all do
 * referenced markdown files get a turn.
 */
@Component
public class SkillContractExtractor {

    /** One file of a skill version. */
    public static class Source {
        private final String path;
        private final String content;

        public Source(String path, String content) {
            this.path = path == null ? "" : path;
            this.content = content == null ? "" : content;
        }

        public String getPath() { return path; }
        public String getContent() { return content; }
    }

    /** Extraction outcome, ready to be stored. */
    public static class Result {
        private SkillContract contract = new SkillContract();
        private String parseMethod = "failed";
        private String rawHash;
        private String sourcePath;
        private Map<String, Object> meta = new LinkedHashMap<String, Object>();

        public SkillContract getContract() { return contract; }
        public void setContract(SkillContract contract) { this.contract = contract; }
        public String getParseMethod() { return parseMethod; }
        public void setParseMethod(String parseMethod) { this.parseMethod = parseMethod; }
        public String getRawHash() { return rawHash; }
        public void setRawHash(String rawHash) { this.rawHash = rawHash; }
        public String getSourcePath() { return sourcePath; }
        public void setSourcePath(String sourcePath) { this.sourcePath = sourcePath; }
        public Map<String, Object> getMeta() { return meta; }
        public void setMeta(Map<String, Object> meta) { this.meta = meta; }
    }

    private static final String SKILL_FILE = "skill.md";
    private static final int SKILL_TEXT_LIMIT = 24000;
    private static final int MARKDOWN_LIMIT = 16000;
    private static final int SCRIPT_LIMIT = 4000;
    private static final int MAX_STEPS = 24;
    private static final int MAX_KEYWORDS = 8;
    private static final int MAX_ARTIFACTS = 6;

    /** Relative path with at least one directory segment, e.g. references/a.md. */
    private static final Pattern RELATIVE_PATH = Pattern.compile(
            "(?<![\\w/:.-])((?:\\.{1,2}/)?(?:[A-Za-z0-9_][A-Za-z0-9_.\\-]*/)+[A-Za-z0-9_][A-Za-z0-9_.\\-]*\\.[A-Za-z0-9]{1,6})");

    private static final Pattern EXPLICIT_STEP = Pattern.compile(
            "^(?:step|phase|阶段|步骤)\\s*\\d*\\b.*$", Pattern.CASE_INSENSITIVE);

    private static final Pattern ARTIFACT = Pattern.compile(
            "[A-Za-z0-9_][A-Za-z0-9_.\\-]*\\.(?:md|json|csv|tsv|py|ts|tsx|js|jsx|vue|ya?ml|txt|html|css|sql)");

    private static final Pattern BOLD_TERM = Pattern.compile("\\*\\*([^*\\n]{2,40})\\*\\*");
    private static final Pattern CODE_TERM = Pattern.compile("`([^`\\n]{2,60})`");

    /** Tool names an agent runtime exposes; used to spot "which tool a step expects". */
    private static final List<String> KNOWN_TOOLS = Arrays.asList(
            "Read", "Write", "Edit", "MultiEdit", "Bash", "Glob", "Grep", "Task", "Agent",
            "WebFetch", "WebSearch", "TodoWrite", "NotebookEdit");

    /** Section titles that describe the skill, not a step of it. */
    private static final List<String> NON_STEP_TITLES = Arrays.asList(
            "when to apply", "when to use", "when to skip", "overview", "description",
            "introduction", "install", "installation", "license", "references",
            "resources", "notes", "quick reference", "table of contents", "prerequisites",
            "background", "faq", "changelog", "about", "summary", "appendix",
            "rule categories", "何时", "概述", "简介", "安装", "参考", "说明", "目录");

    public Result extract(List<Source> files) {
        Result result = new Result();
        if (files == null || files.isEmpty()) {
            result.getMeta().put("error", "技能包内没有可读文件");
            return result;
        }

        Source skillFile = pickRootSkillFile(files);
        if (skillFile == null) {
            result.getMeta().put("error", "未找到 SKILL.md");
            return result;
        }
        result.setSourcePath(skillFile.getPath());

        String skillText = cap(skillFile.getContent().replace("\r\n", "\n"), SKILL_TEXT_LIMIT);
        Map<String, String> frontmatter = parseFrontmatter(skillText);

        // Reference discovery: only files this SKILL.md actually points at.
        Map<String, Source> byPath = indexFiles(files);
        List<Source> referenced = new ArrayList<Source>();
        for (String ref : discoverReferences(skillText)) {
            Source hit = resolveReference(byPath, ref);
            if (hit != null && !hit.getPath().equals(skillFile.getPath()) && !containsPath(referenced, hit.getPath())) {
                referenced.add(hit);
            }
        }

        List<StepSection> sections = parseSections(skillText);
        List<SkillContract.Step> steps = buildSteps(sections, true);
        boolean fallback = false;
        if (steps.isEmpty()) {
            // Rare: SKILL.md is only a pointer. Borrow the flow from referenced markdown.
            fallback = true;
            for (Source ref : referenced) {
                if (!isMarkdown(ref.getPath())) continue;
                steps = buildSteps(parseSections(cap(ref.getContent(), MARKDOWN_LIMIT)), true);
                if (!steps.isEmpty()) break;
            }
        }

        SkillContract contract = new SkillContract();
        contract.setSteps(steps);
        contract.setTriggers(extractTriggers(frontmatter));
        contract.setEntryCommands(extractEntryCommands(skillText));
        contract.setForbidden(extractForbidden(skillText));
        contract.setPreconditions(new ArrayList<String>());
        result.setContract(contract);

        Map<String, Object> meta = result.getMeta();
        meta.put("stepCount", Integer.valueOf(steps.size()));
        meta.put("filesInPackage", Integer.valueOf(files.size()));
        meta.put("referencedFiles", Integer.valueOf(referenced.size()));
        meta.put("referencedPaths", referencedPaths(referenced));
        meta.put("usedReferencedFallback", Boolean.valueOf(fallback));

        if (steps.isEmpty()) {
            // No baseline means completeness cannot be judged; say so instead of guessing.
            result.setParseMethod("failed");
            meta.put("error", "未能从 SKILL.md 提取出可执行步骤");
        } else {
            result.setParseMethod("rule");
            meta.put("confidence", Double.valueOf(confidenceOf(steps)));
        }

        result.setRawHash(sha256(skillText + "\n" + renderReferencedDigest(referenced)));
        return result;
    }

    // ---------------------------------------------------------------- files

    private Source pickRootSkillFile(List<Source> files) {
        Source best = null;
        int bestDepth = Integer.MAX_VALUE;
        for (Source file : files) {
            String name = fileName(file.getPath());
            if (!SKILL_FILE.equals(name)) continue;
            int depth = depthOf(file.getPath());
            if (depth < bestDepth) {
                bestDepth = depth;
                best = file;
            }
        }
        return best;
    }

    private Map<String, Source> indexFiles(List<Source> files) {
        Map<String, Source> byPath = new HashMap<String, Source>();
        for (Source file : files) {
            byPath.put(normalizePath(file.getPath()), file);
        }
        return byPath;
    }

    private Source resolveReference(Map<String, Source> byPath, String rawRef) {
        String ref = normalizePath(rawRef);
        if (ref.isEmpty()) return null;
        Source exact = byPath.get(ref);
        if (exact != null) return exact;
        Source suffixHit = null;
        for (Map.Entry<String, Source> entry : byPath.entrySet()) {
            if (entry.getKey().endsWith("/" + ref) || entry.getKey().equals(ref)) {
                if (suffixHit == null || entry.getKey().length() < suffixHit.getPath().length()) {
                    suffixHit = entry.getValue();
                }
            }
        }
        return suffixHit;
    }

    List<String> discoverReferences(String text) {
        Set<String> found = new LinkedHashSet<String>();
        Matcher matcher = RELATIVE_PATH.matcher(text);
        while (matcher.find()) {
            String ref = matcher.group(1);
            if (ref == null || ref.isEmpty()) continue;
            String lower = ref.toLowerCase(Locale.ROOT);
            if (lower.startsWith("http") || lower.contains("://")) continue;
            found.add(ref);
            if (found.size() >= 40) break;
        }
        return new ArrayList<String>(found);
    }

    private boolean containsPath(List<Source> list, String path) {
        for (Source item : list) {
            if (item.getPath().equals(path)) return true;
        }
        return false;
    }

    private List<String> referencedPaths(List<Source> list) {
        List<String> paths = new ArrayList<String>();
        for (Source item : list) paths.add(item.getPath());
        return paths;
    }

    /** Content digest of referenced files so contract invalidation covers them too. */
    private String renderReferencedDigest(List<Source> referenced) {
        StringBuilder builder = new StringBuilder();
        List<Source> sorted = new ArrayList<Source>(referenced);
        java.util.Collections.sort(sorted, new java.util.Comparator<Source>() {
            public int compare(Source a, Source b) { return a.getPath().compareTo(b.getPath()); }
        });
        for (Source item : sorted) {
            // Only a fingerprint per file; the body itself must not bloat the hash input.
            builder.append(item.getPath()).append(':').append(sha256(item.getContent())).append('\n');
            if (builder.length() > 20000) break;
        }
        return builder.toString();
    }

    // ---------------------------------------------------------- frontmatter

    Map<String, String> parseFrontmatter(String content) {
        Map<String, String> values = new HashMap<String, String>();
        String text = content.replace("\uFEFF", "");
        if (!text.startsWith("---")) return values;
        int end = text.indexOf("\n---", 3);
        if (end < 0) return values;
        String block = text.substring(3, end);
        String[] lines = block.split("\n");
        String currentKey = null;
        List<String> currentList = null;
        for (String rawLine : lines) {
            String line = rawLine.replace("\r", "");
            if (line.trim().isEmpty()) continue;
            boolean indented = line.startsWith(" ") || line.startsWith("\t");
            String trimmed = line.trim();
            if (indented && trimmed.startsWith("- ") && currentKey != null) {
                if (currentList == null) currentList = new ArrayList<String>();
                currentList.add(unquote(trimmed.substring(2).trim()));
                values.put(currentKey, join(currentList));
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon <= 0) continue;
            currentKey = trimmed.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            currentList = null;
            String value = trimmed.substring(colon + 1).trim();
            if (value.startsWith("[") && value.endsWith("]")) {
                List<String> items = new ArrayList<String>();
                for (String piece : value.substring(1, value.length() - 1).split(",")) {
                    String item = unquote(piece.trim());
                    if (!item.isEmpty()) items.add(item);
                }
                currentList = items;
                values.put(currentKey, join(items));
            } else if (!value.isEmpty()) {
                values.put(currentKey, unquote(value));
            }
        }
        return values;
    }

    private List<String> extractTriggers(Map<String, String> frontmatter) {
        List<String> triggers = new ArrayList<String>();
        String declared = frontmatter.get("triggers");
        if (declared != null) {
            for (String piece : declared.split("\\s*[|,]\\s*")) {
                String item = piece.trim();
                if (!item.isEmpty() && triggers.size() < 12) triggers.add(item);
            }
        }
        String description = frontmatter.get("description");
        if (description != null) {
            // "Use when ..." sentences are the most reliable trigger source.
            for (String sentence : description.split("(?<=[.!?])\\s+")) {
                String lower = sentence.toLowerCase(Locale.ROOT);
                if ((lower.contains("use when") || lower.contains("use this skill") || lower.contains("should be used"))
                        && triggers.size() < 12) {
                    triggers.add(cap(sentence.trim(), 200));
                }
            }
        }
        return triggers;
    }

    private List<String> extractEntryCommands(String text) {
        List<String> commands = new ArrayList<String>();
        Matcher matcher = Pattern.compile("(?:^|[\\s\"'`(\\[])([/$])([a-z][a-z0-9-]{2,30})", Pattern.CASE_INSENSITIVE).matcher(text);
        while (matcher.find() && commands.size() < 8) {
            String command = matcher.group(1) + matcher.group(2);
            if (!commands.contains(command)) commands.add(command);
        }
        return commands;
    }

    private List<String> extractForbidden(String text) {
        List<String> forbidden = new ArrayList<String>();
        Matcher matcher = Pattern.compile("(?im)^\\s*(?:[-*]\\s*)?(?:do not|don't|never|avoid|禁止|不要)\\s+(.{4,120})$").matcher(text);
        while (matcher.find() && forbidden.size() < 8) {
            String item = matcher.group(1).trim();
            if (!item.isEmpty()) forbidden.add(cap(item, 120));
        }
        return forbidden;
    }

    // -------------------------------------------------------------- sections

    static class StepSection {
        String title;
        int level;
        StringBuilder body = new StringBuilder();
    }

    List<StepSection> parseSections(String content) {
        List<StepSection> sections = new ArrayList<StepSection>();
        StepSection current = null;
        boolean inCodeBlock = false;
        for (String rawLine : content.split("\n")) {
            String line = rawLine.replace("\r", "");
            if (line.trim().startsWith("```")) {
                inCodeBlock = !inCodeBlock;
                if (current != null) current.body.append(line).append('\n');
                continue;
            }
            Matcher heading = inCodeBlock ? null : Pattern.compile("^(#{1,4})\\s+(.*\\S)\\s*$").matcher(line);
            if (heading != null && heading.matches()) {
                StepSection section = new StepSection();
                section.level = heading.group(1).length();
                section.title = stripInlineMarkup(heading.group(2));
                sections.add(section);
                current = section;
                continue;
            }
            if (current != null) current.body.append(line).append('\n');
        }
        return sections;
    }

    private List<SkillContract.Step> buildSteps(List<StepSection> sections, boolean filterNonStepTitles) {
        List<StepSection> candidates = new ArrayList<StepSection>();
        for (StepSection section : sections) {
            if (section.title == null || section.title.trim().isEmpty()) continue;
            if (section.level < 2 || section.level > 4) continue;
            if (EXPLICIT_STEP.matcher(section.title.trim()).matches()) candidates.add(section);
        }
        if (candidates.isEmpty()) {
            for (StepSection section : sections) {
                if (section.title == null || section.title.trim().isEmpty()) continue;
                if (section.level != 2) continue;
                if (filterNonStepTitles && isNonStepTitle(section.title)) continue;
                candidates.add(section);
            }
        }
        List<SkillContract.Step> steps = new ArrayList<SkillContract.Step>();
        int index = 0;
        for (StepSection section : candidates) {
            if (steps.size() >= MAX_STEPS) break;
            index += 1;
            String body = section.body.toString();
            SkillContract.Step step = new SkillContract.Step();
            step.setId("s" + index);
            step.setTitle(cap(section.title.trim(), 120));
            step.setDescription(cap(collapse(body), 240));
            step.setKeywords(keywordsOf(section.title, body));
            step.setExpectedTools(toolsOf(body));
            step.setExpectedArtifacts(artifactsOf(body));
            step.setOptional(isOptionalTitle(section.title));
            steps.add(step);
        }
        return steps;
    }

    private boolean isNonStepTitle(String title) {
        String lower = stripInlineMarkup(title).toLowerCase(Locale.ROOT).trim();
        for (String blocked : NON_STEP_TITLES) {
            if (lower.equals(blocked) || lower.startsWith(blocked)) return true;
        }
        return false;
    }

    private boolean isOptionalTitle(String title) {
        String lower = title.toLowerCase(Locale.ROOT);
        return lower.contains("optional") || lower.contains("可选") || lower.contains("选做")
                || lower.contains("if needed") || lower.contains("as needed");
    }

    private List<String> keywordsOf(String title, String body) {
        Set<String> keywords = new LinkedHashSet<String>();
        for (String word : tokenize(title)) keywords.add(word);
        Matcher bold = BOLD_TERM.matcher(body);
        while (bold.find() && keywords.size() < MAX_KEYWORDS) {
            for (String word : tokenize(bold.group(1))) keywords.add(word);
        }
        Matcher code = CODE_TERM.matcher(body);
        while (code.find() && keywords.size() < MAX_KEYWORDS) {
            String term = code.group(1).trim().toLowerCase(Locale.ROOT);
            if (term.length() >= 3 && term.length() <= 40 && !term.contains(" ")) keywords.add(term);
        }
        List<String> result = new ArrayList<String>();
        for (String keyword : keywords) {
            if (result.size() >= MAX_KEYWORDS) break;
            result.add(keyword);
        }
        return result;
    }

    private List<String> toolsOf(String body) {
        List<String> tools = new ArrayList<String>();
        for (String tool : KNOWN_TOOLS) {
            if (Pattern.compile("\\b" + Pattern.quote(tool) + "\\b").matcher(body).find() && !tools.contains(tool)) {
                tools.add(tool);
            }
        }
        return tools;
    }

    private List<String> artifactsOf(String body) {
        Set<String> artifacts = new LinkedHashSet<String>();
        Matcher matcher = ARTIFACT.matcher(body);
        while (matcher.find() && artifacts.size() < MAX_ARTIFACTS) {
            String name = matcher.group();
            if (name.length() <= 64) artifacts.add(name);
        }
        return new ArrayList<String>(artifacts);
    }

    private double confidenceOf(List<SkillContract.Step> steps) {
        int scored = 0;
        for (SkillContract.Step step : steps) {
            if ((step.getKeywords() != null && !step.getKeywords().isEmpty())
                    || (step.getExpectedTools() != null && !step.getExpectedTools().isEmpty())) {
                scored += 1;
            }
        }
        return steps.isEmpty() ? 0d : Math.round((double) scored / steps.size() * 100d) / 100d;
    }

    // --------------------------------------------------------------- utils

    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<String>();
        if (text == null) return tokens;
        for (String piece : text.toLowerCase(Locale.ROOT).split("[^a-z0-9\\u4e00-\\u9fa5]+")) {
            if (piece.length() < 3) continue;
            if (isStopWord(piece)) continue;
            if (!tokens.contains(piece)) tokens.add(piece);
        }
        return tokens;
    }

    private boolean isStopWord(String word) {
        return STOP_WORDS.contains(word);
    }

    private static final Set<String> STOP_WORDS = new HashSet<String>(Arrays.asList(
            "the", "and", "for", "with", "that", "this", "from", "into", "are", "was",
            "when", "then", "use", "using", "should", "must", "can", "will", "not",
            "step", "steps", "phase", "阶段", "步骤", "使用", "以及", "如果", "进行"));

    private static String stripInlineMarkup(String text) {
        return text == null ? "" : text.replaceAll("[*_`]", "").trim();
    }

    private static String collapse(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private static String cap(String text, int limit) {
        if (text == null) return null;
        return text.length() <= limit ? text : text.substring(0, limit) + "…";
    }

    private static String unquote(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && (trimmed.charAt(0) == '"' || trimmed.charAt(0) == '\'')
                && trimmed.charAt(trimmed.length() - 1) == trimmed.charAt(0)) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String join(List<String> items) {
        StringBuilder builder = new StringBuilder();
        for (String item : items) {
            if (builder.length() > 0) builder.append(" | ");
            builder.append(item);
        }
        return builder.toString();
    }

    private static String fileName(String path) {
        String normalized = normalizePath(path);
        int slash = normalized.lastIndexOf('/');
        return (slash >= 0 ? normalized.substring(slash + 1) : normalized).toLowerCase(Locale.ROOT);
    }

    private static int depthOf(String path) {
        int depth = 0;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') depth += 1;
        }
        return depth;
    }

    private static String normalizePath(String path) {
        return path == null ? "" : path.replace('\\', '/').trim();
    }

    private static boolean isMarkdown(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".md") || lower.endsWith(".mdx") || lower.endsWith(".markdown");
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (Exception ex) {
            return null;
        }
    }
}
