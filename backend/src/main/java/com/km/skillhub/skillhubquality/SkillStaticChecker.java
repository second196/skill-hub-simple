package com.km.skillhub.skillhubquality;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * L1 格式检查（五项指标，维度与扣分口径对齐 kmplm SkillStaticChecker）：
 * L1-A 名称与说明 / L1-B 引用文件 / L1-C 步骤结构 / L1-D 失败与反例 /
 * L1-E 可执行表述。纯规则实现，不依赖 LLM。
 *
 * <p>score = 100 − error×errorPenalty − warn×warnPenalty（下限 0）；
 * status = error &gt; 0 ? error : (warn &gt; 0 ? warn : ok)。</p>
 */
@Service
public class SkillStaticChecker {

    /** One file of the skill package under review. */
    public static class SkillFile {
        private final String path;
        private final String content;

        public SkillFile(String path, String content) {
            this.path = path == null ? "" : path;
            this.content = content == null ? "" : content;
        }

        public String getPath() { return path; }
        public String getContent() { return content; }
    }

    /** One L1 finding. */
    public static class Issue {
        private String metricCode;
        private String level;
        private String location;
        private String message;
        private String suggestion;
        private String priority;

        public String getMetricCode() { return metricCode; }
        public void setMetricCode(String metricCode) { this.metricCode = metricCode; }
        public String getLevel() { return level; }
        public void setLevel(String level) { this.level = level; }
        public String getLocation() { return location; }
        public void setLocation(String location) { this.location = location; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getSuggestion() { return suggestion; }
        public void setSuggestion(String suggestion) { this.suggestion = suggestion; }
        public String getPriority() { return priority; }
        public void setPriority(String priority) { this.priority = priority; }
    }

    /** L1 result: score, status and issues. */
    public static class StaticReport {
        private int score;
        /** ok | warn | error */
        private String status;
        private List<Issue> issues = new ArrayList<Issue>();
        private Map<String, Object> metrics = new LinkedHashMap<String, Object>();

        public int getScore() { return score; }
        public void setScore(int score) { this.score = score; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public List<Issue> getIssues() { return issues; }
        public void setIssues(List<Issue> issues) { this.issues = issues == null ? new ArrayList<Issue>() : issues; }
        public Map<String, Object> getMetrics() { return metrics; }
        public void setMetrics(Map<String, Object> metrics) { this.metrics = metrics == null ? new LinkedHashMap<String, Object>() : metrics; }
    }

    public static final String METRIC_A = "L1-A";
    public static final String METRIC_B = "L1-B";
    public static final String METRIC_C = "L1-C";
    public static final String METRIC_D = "L1-D";
    public static final String METRIC_E = "L1-E";

    public static final String LEVEL_ERROR = "error";
    public static final String LEVEL_WARN = "warn";

    private static final int MAX_NAME_LEN = 64;
    private static final int MAX_DESC_LEN = 1024;
    private static final Pattern NAME_RE = Pattern.compile(
            "^(?!.*--)[a-z0-9]([a-z0-9-]*[a-z0-9])?$");
    private static final Set<String> ALLOWED_FRONTMATTER = new HashSet<String>(Arrays.asList(
            "name", "description", "license", "allowed-tools", "metadata", "compatibility",
            "version", "category", "tags", "title"));
    /** 显式绑定单一运行时（darwin runtime 红灯）。 */
    private static final Pattern RUNTIME_LOCK = Pattern.compile(
            "(?i)(仅\\s*(?:可用|支持)|only\\s+(?:work|support)|在\\s*Claude\\s*Code|Claude\\s*Code\\s*only|"
                    + "Cursor\\s*only|Codex\\s*only|~/?\\.claude/skills/)");
    /** 相对路径引用，如 scripts/export.py。 */
    private static final Pattern RELATIVE_PATH = Pattern.compile(
            "(?<![\\w/:.-])((?:\\.{1,2}/)?(?:[A-Za-z0-9_][A-Za-z0-9_.\\-]*/)+[A-Za-z0-9_][A-Za-z0-9_.\\-]*\\.[A-Za-z0-9]{1,6})");
    private static final Pattern NUMBERED_STEP = Pattern.compile(
            "^\\s*(?:step\\s*)?(\\d+)[.)、]\\s+\\S",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern EXPLICIT_STEP = Pattern.compile(
            "^(?:step|phase|阶段|步骤)\\s*\\d*\\b.*$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern FAILURE_BRANCH = Pattern.compile(
            "(?i)(失败|报错|出错|error|fail(?:ed|ure)?|fallback|重试|retry|回退|退出码|exit\\s*code)");
    private static final Pattern FORBIDDEN = Pattern.compile(
            "(?im)^\\s*(?:[-*]\\s*)?(?:do not|don't|never|avoid|禁止|不要|不得|别)\\s*.{2,}");
    private static final List<String> SOFT_WORDS = Arrays.asList(
            "根据情况", "视情况", "适当", "酌情", "尽量", "尽可能", "必要时", "灵活", "合理",
            "差不多", "大概", "等等", "and so on", "etc.", "if needed", "if necessary",
            "as appropriate", "as needed", "when appropriate", "somehow", "handle it");

    private final int errorPenalty;
    private final int warnPenalty;

    public SkillStaticChecker(@Value("${skillhub.quality.l1.error-penalty:15}") int errorPenalty,
                              @Value("${skillhub.quality.l1.warn-penalty:5}") int warnPenalty) {
        this.errorPenalty = errorPenalty <= 0 ? 15 : errorPenalty;
        this.warnPenalty = warnPenalty <= 0 ? 5 : warnPenalty;
    }

    /** 五项静态检查；空包直接 error / 0 分。 */
    public StaticReport check(List<SkillFile> skillFiles) {
        List<Issue> issues = new ArrayList<Issue>();
        Map<String, Object> metrics = new LinkedHashMap<String, Object>();
        List<SkillFile> files = skillFiles == null ? Collections.<SkillFile>emptyList() : skillFiles;

        if (files.isEmpty()) {
            issues.add(issue(METRIC_A, LEVEL_ERROR, "skill-package",
                    "技能包内没有可读文件",
                    "补齐 SKILL.md 与被引用的资源文件后再检查",
                    "P0"));
            return buildReport(issues, metrics);
        }

        SkillFile skillFile = pickRootSkillFile(files);
        if (skillFile == null) {
            issues.add(issue(METRIC_A, LEVEL_ERROR, "SKILL.md",
                    "未找到 SKILL.md",
                    "在技能包根目录提供 SKILL.md，并写好 name 与 description",
                    "P0"));
            return buildReport(issues, metrics);
        }

        String skillText = skillFile.getContent().replace("\r\n", "\n").replace("\uFEFF", "");
        Map<String, String> frontmatter = parseFrontmatter(skillText);
        String body = bodyOf(skillText);

        checkFrontmatter(skillFile.getPath(), frontmatter, issues, metrics);
        checkReferences(skillFile.getPath(), body, files, issues, metrics);
        checkWorkflowStructure(skillFile.getPath(), body, issues);
        checkFailureAndForbidden(skillFile.getPath(), body, issues);
        checkExecutability(skillFile.getPath(), body, issues);

        return buildReport(issues, metrics);
    }

    // ------------------------------------------------------------- checks

    /** L1-A 名称与说明（Frontmatter）。 */
    private void checkFrontmatter(String path, Map<String, String> frontmatter,
                                  List<Issue> issues, Map<String, Object> metrics) {
        String name = trimToNull(frontmatter.get("name"));
        String description = trimToNull(frontmatter.get("description"));
        metrics.put("hasName", Boolean.valueOf(name != null));
        metrics.put("hasDescription", Boolean.valueOf(description != null));

        for (String key : frontmatter.keySet()) {
            if (!ALLOWED_FRONTMATTER.contains(key)) {
                issues.add(issue(METRIC_A, LEVEL_WARN, path + ":frontmatter:" + key,
                        "frontmatter 含未识别字段：" + key,
                        "删除无关字段，或改为正文说明",
                        "P2"));
            }
        }

        if (name == null) {
            issues.add(issue(METRIC_A, LEVEL_ERROR, path + ":frontmatter:name",
                    "Frontmatter 缺少 name",
                    "在 SKILL.md 顶部 frontmatter 补上 name（与目录名一致或可识别）",
                    "P0"));
        } else {
            if (name.length() < 2) {
                issues.add(issue(METRIC_A, LEVEL_WARN, path + ":frontmatter:name",
                        "name 过短，难以识别",
                        "把 name 写成能代表技能职责的短语",
                        "P1"));
            }
            if (name.length() > MAX_NAME_LEN) {
                issues.add(issue(METRIC_A, LEVEL_ERROR, path + ":frontmatter:name",
                        "name 超长（" + name.length() + " > " + MAX_NAME_LEN + "）",
                        "将 name 压缩到 64 字符以内",
                        "P0"));
            } else if (!NAME_RE.matcher(name).matches()) {
                issues.add(issue(METRIC_A, LEVEL_WARN, path + ":frontmatter:name",
                        "name 不符合推荐命名（小写字母数字连字符）",
                        "改为如 my-export-skill 的 kebab-case 命名",
                        "P1"));
            }
        }
        if (description == null) {
            issues.add(issue(METRIC_A, LEVEL_ERROR, path + ":frontmatter:description",
                    "Frontmatter 缺少 description",
                    "补上说明：做什么、何时用、关键触发词",
                    "P0"));
        } else {
            if (description.length() < 10) {
                issues.add(issue(METRIC_A, LEVEL_WARN, path + ":frontmatter:description",
                        "description 过短，无法界定使用时机",
                        "补充能力边界与使用时机（做什么、何时用）",
                        "P1"));
            }
            if (description.length() > MAX_DESC_LEN) {
                issues.add(issue(METRIC_A, LEVEL_ERROR, path + ":frontmatter:description",
                        "description 超长（" + description.length() + " > " + MAX_DESC_LEN + "）",
                        "压缩 description 到 1024 字符以内",
                        "P0"));
            }
        }
    }

    /** L1-B 引用文件：正文引用的相对路径是否真实存在。 */
    private void checkReferences(String skillPath, String body, List<SkillFile> files,
                                 List<Issue> issues, Map<String, Object> metrics) {
        Set<String> paths = new HashSet<String>();
        for (SkillFile file : files) {
            paths.add(file.getPath().replace('\\', '/').toLowerCase(Locale.ROOT));
        }
        Set<String> seen = new HashSet<String>();
        int missing = 0;
        Matcher matcher = RELATIVE_PATH.matcher(body);
        while (matcher.find()) {
            String ref = matcher.group(1).replace('\\', '/');
            String lower = ref.toLowerCase(Locale.ROOT);
            if (lower.startsWith("http/") || lower.startsWith("https/")) continue;
            if (!seen.add(lower)) continue;
            if (paths.contains(lower)) continue;
            // 允许只匹配文件名后缀（引用写在别的目录层级）
            boolean found = false;
            for (String path : paths) {
                if (path.endsWith("/" + lower) || path.equals(lower)) {
                    found = true;
                    break;
                }
            }
            if (found) continue;
            missing++;
            issues.add(issue(METRIC_B, LEVEL_ERROR, skillPath + " -> " + ref,
                    "引用的文件不存在：" + ref,
                    "补齐该文件，或删除/更正正文中的引用路径",
                    "P0"));
        }
        metrics.put("missingRefs", Integer.valueOf(missing));
    }

    /** L1-C 步骤结构：是否存在有序、可执行的工作流步骤。 */
    private void checkWorkflowStructure(String skillPath, String body, List<Issue> issues) {
        Matcher numbered = NUMBERED_STEP.matcher(body);
        int numberedHits = 0;
        while (numbered.find() && numberedHits < 1) numberedHits++;
        boolean explicit = EXPLICIT_STEP.matcher(body).find();
        if (numberedHits == 0 && !explicit) {
            issues.add(issue(METRIC_C, LEVEL_WARN, skillPath + ":steps",
                    "未发现有序的工作流步骤",
                    "把执行过程写成有序的 1/2/3 步骤，每步写清输入与输出",
                    "P1"));
        }
    }

    /** L1-D 失败与反例：失败分支与禁止事项。 */
    private void checkFailureAndForbidden(String skillPath, String body, List<Issue> issues) {
        if (!FAILURE_BRANCH.matcher(body).find()) {
            issues.add(issue(METRIC_D, LEVEL_WARN, skillPath + ":failure",
                    "未描述失败分支或重试策略",
                    "补上「若 X 失败则 Y」的失败处理与回退策略",
                    "P1"));
        }
        if (!FORBIDDEN.matcher(body).find()) {
            issues.add(issue(METRIC_D, LEVEL_WARN, skillPath + ":forbidden",
                    "缺少禁止事项 / 反例说明",
                    "列出不要做什么：禁止操作、危险动作、常见误用",
                    "P2"));
        }
    }

    /** L1-E 可执行表述：软化措辞与单一运行时绑定。 */
    private void checkExecutability(String skillPath, String body, List<Issue> issues) {
        String softHit = null;
        for (String word : SOFT_WORDS) {
            if (containsIgnoreCase(body, word)) {
                softHit = word;
                break;
            }
        }
        if (softHit != null) {
            issues.add(issue(METRIC_E, LEVEL_WARN, skillPath + ":soft-words",
                    "存在模糊软化措辞：「" + softHit + "」",
                    "改为可直接执行的具体动作、参数与判定标准",
                    "P1"));
        }
        if (RUNTIME_LOCK.matcher(body).find()) {
            issues.add(issue(METRIC_E, LEVEL_WARN, skillPath + ":runtime-lock",
                    "绑定了单一运行环境（如仅 Claude Code 可用）",
                    "去掉单一环境绑定，或说明跨环境的等价做法",
                    "P1"));
        }
    }

    // ------------------------------------------------------------- helpers

    private StaticReport buildReport(List<Issue> issues, Map<String, Object> metrics) {
        int errorCount = 0;
        int warnCount = 0;
        for (Issue issue : issues) {
            if (LEVEL_ERROR.equals(issue.getLevel())) errorCount += 1;
            else warnCount += 1;
        }
        int score = 100 - errorCount * errorPenalty - warnCount * warnPenalty;
        if (score < 0) score = 0;

        String status = "ok";
        if (errorCount > 0) status = LEVEL_ERROR;
        else if (warnCount > 0) status = LEVEL_WARN;

        StaticReport report = new StaticReport();
        report.setScore(score);
        report.setStatus(status);
        report.setIssues(issues);
        metrics.put("errorCount", Integer.valueOf(errorCount));
        metrics.put("warnCount", Integer.valueOf(warnCount));
        report.setMetrics(metrics);
        return report;
    }

    /** 包根 SKILL.md 优先，其次任意路径里的 skill.md。 */
    static SkillFile pickRootSkillFile(List<SkillFile> files) {
        SkillFile fallback = null;
        for (SkillFile file : files) {
            String path = file.getPath().replace('\\', '/');
            String name = path.contains("/")
                    ? path.substring(path.lastIndexOf('/') + 1) : path;
            if (!"SKILL.md".equalsIgnoreCase(name)) continue;
            if (!path.contains("/")) return file;
            if (fallback == null) fallback = file;
        }
        return fallback;
    }

    static Map<String, String> parseFrontmatter(String text) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (text == null) return out;
        String normalized = text.replace("\r\n", "\n");
        if (!normalized.startsWith("---")) return out;
        int lineEnd = normalized.indexOf('\n');
        if (lineEnd < 0) return out;
        int close = normalized.indexOf("\n---", lineEnd);
        if (close < 0) return out;
        String block = normalized.substring(lineEnd + 1, close);
        for (String line : block.split("\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (!key.isEmpty()) out.put(key, stripQuotes(value));
        }
        return out;
    }

    private static String bodyOf(String text) {
        if (text == null) return "";
        String normalized = text.replace("\r\n", "\n");
        if (normalized.startsWith("---")) {
            int close = normalized.indexOf("\n---", 3);
            if (close >= 0) {
                int bodyStart = normalized.indexOf('\n', close + 1);
                return bodyStart < 0 ? "" : normalized.substring(bodyStart + 1);
            }
        }
        return normalized;
    }

    private static String stripQuotes(String value) {
        String v = value.trim();
        if (v.length() >= 2) {
            char first = v.charAt(0);
            char last = v.charAt(v.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return v.substring(1, v.length() - 1).trim();
            }
        }
        return v;
    }

    private static boolean containsIgnoreCase(String body, String word) {
        if (body == null || word == null) return false;
        return body.toLowerCase(Locale.ROOT).contains(word.toLowerCase(Locale.ROOT));
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Issue issue(String metricCode, String level, String location,
                               String message, String suggestion, String priority) {
        Issue item = new Issue();
        item.setMetricCode(metricCode);
        item.setLevel(level);
        item.setLocation(location);
        item.setMessage(message);
        item.setSuggestion(suggestion);
        item.setPriority(priority);
        return item;
    }
}
