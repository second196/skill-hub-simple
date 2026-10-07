package com.km.skillhub.analysis;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The extractor is the one piece driven by heuristics, so its behaviour on the two
 * shapes that actually exist in this repository is pinned down here: a self-contained
 * skill, and a skill whose rules live in referenced files.
 */
class SkillContractExtractorTest {

    private final SkillContractExtractor extractor = new SkillContractExtractor();

    private static SkillContractExtractor.Source file(String path, String content) {
        return new SkillContractExtractor.Source(path, content);
    }

    @Test
    void readsSelfContainedSkillFromSkillMarkdownOnly() {
        String skill = ""
                + "---\n"
                + "name: token-efficient-development\n"
                + "description: Use when implementing, fixing, or reviewing code and the agent must stay within scope.\n"
                + "---\n"
                + "\n"
                + "# Token-Efficient Development\n"
                + "\n"
                + "Keep every action tied to the requested outcome.\n"
                + "\n"
                + "## Scope gate\n"
                + "\n"
                + "1. Restate the requested outcome.\n"
                + "2. Extract acceptance criteria.\n"
                + "\n"
                + "## Focused inspection\n"
                + "\n"
                + "- Find the implementation with targeted searches.\n"
                + "\n"
                + "## Minimal implementation\n"
                + "\n"
                + "- Change only files required by the criteria.\n"
                + "\n"
                + "## Verification and stopping\n"
                + "\n"
                + "- Run the narrowest relevant validation.\n";

        SkillContractExtractor.Result result = extractor.extract(
                Arrays.asList(file("token-efficient-development/SKILL.md", skill)));

        assertEquals("rule", result.getParseMethod());
        assertEquals(4, result.getContract().getSteps().size());
        assertEquals("Scope gate", result.getContract().getSteps().get(0).getTitle());
        assertTrue(result.getContract().getSteps().get(0).getKeywords().contains("scope"));
        // "Use when ..." in the description becomes a trigger phrase.
        assertFalse(result.getContract().getTriggers().isEmpty());
        assertNotNull(result.getRawHash());
    }

    @Test
    void prefersExplicitStepHeadingsAndPullsReferencedFiles() {
        String skill = ""
                + "---\n"
                + "name: ui-ux-pro-max\n"
                + "description: \"UI guidance. This skill should be used when designing interfaces.\"\n"
                + "---\n"
                + "\n"
                + "# UI/UX Pro Max\n"
                + "\n"
                + "## When to Apply\n"
                + "\n"
                + "Use for visual design work.\n"
                + "\n"
                + "## Running the search tool\n"
                + "\n"
                + "```bash\n"
                + "python scripts/search.py \"query\" --domain ux\n"
                + "```\n"
                + "\n"
                + "The full rule text lives in references/quick-reference.md.\n"
                + "\n"
                + "## Step 1: Analyze User Requirements\n"
                + "\n"
                + "Extract product type and stack from the user request.\n"
                + "\n"
                + "## Step 2: Generate Design System\n"
                + "\n"
                + "Run the search tool and read `MASTER.md`.\n"
                + "\n"
                + "## Step 3: Apply Rules (optional)\n"
                + "\n"
                + "Read references/pro-rules.md before delivery.\n";

        List<SkillContractExtractor.Source> files = new ArrayList<SkillContractExtractor.Source>();
        files.add(file("ui-ux-pro-max/SKILL.md", skill));
        files.add(file("ui-ux-pro-max/references/quick-reference.md", "## Guideline A\nContrast 4.5:1\n"));
        files.add(file("ui-ux-pro-max/references/pro-rules.md", "## Guideline B\nReserve space\n"));
        files.add(file("ui-ux-pro-max/scripts/search.py", "# Search helper\nimport argparse\n"));
        // Never referenced: must not be pulled in even though it is a markdown file.
        files.add(file("ui-ux-pro-max/data/unrelated.md", "## Not a step\nshould be ignored\n"));

        SkillContractExtractor.Result result = extractor.extract(files);
        SkillContract contract = result.getContract();

        // Explicit "Step N" headings win over the plain H2 sections.
        assertEquals(3, contract.getSteps().size());
        assertEquals("Step 1: Analyze User Requirements", contract.getSteps().get(0).getTitle());
        assertTrue(contract.getSteps().get(2).isOptional(), "Step 3 is marked optional");

        @SuppressWarnings("unchecked")
        List<String> referenced = (List<String>) result.getMeta().get("referencedPaths");
        assertTrue(referenced.contains("ui-ux-pro-max/references/quick-reference.md"));
        assertTrue(referenced.contains("ui-ux-pro-max/scripts/search.py"));
        assertFalse(referenced.contains("ui-ux-pro-max/data/unrelated.md"),
                "a file SKILL.md never points at must not be read");
    }

    @Test
    void ignoresRemoteUrlsWhenDiscoveringReferences() {
        List<String> refs = extractor.discoverReferences(
                "See https://github.com/example/repo/blob/main/references/online.md for details.\n"
                        + "Local copy: references/local.md");
        assertTrue(refs.contains("references/local.md"));
        for (String ref : refs) {
            assertFalse(ref.contains("github.com"), "URLs are not package files: " + ref);
        }
    }

    @Test
    void reportsFailureInsteadOfGuessingWhenNoStepsExist() {
        SkillContractExtractor.Result result = extractor.extract(Arrays.asList(
                file("empty/SKILL.md", "---\nname: empty\n---\n\n# Empty\n\njust prose, no structure\n")));

        assertEquals("failed", result.getParseMethod());
        assertTrue(result.getContract().getSteps().isEmpty());
        assertEquals(0, result.getContract().requiredStepCount());
        assertNotNull(result.getMeta().get("error"));
    }

    @Test
    void failsWhenPackageHasNoSkillMarkdown() {
        SkillContractExtractor.Result result = extractor.extract(
                Arrays.asList(file("pkg/README.md", "# readme")));

        assertEquals("failed", result.getParseMethod());
        assertTrue(result.getContract().getSteps().isEmpty());
    }

    @Test
    void parsesMultilineFrontmatterLists() {
        String skill = ""
                + "---\n"
                + "name: multi\n"
                + "triggers:\n"
                + "  - 生成海报\n"
                + "  - 设计页面\n"
                + "---\n"
                + "\n"
                + "## Step 1: Do the thing\n"
                + "\n"
                + "Body.\n";

        SkillContractExtractor.Result result = extractor.extract(
                Arrays.asList(file("multi/SKILL.md", skill)));

        List<String> triggers = result.getContract().getTriggers();
        assertTrue(triggers.contains("生成海报"), "multiline list item parsed");
        assertTrue(triggers.contains("设计页面"));
        assertEquals(1, result.getContract().getSteps().size());
    }

    @Test
    void foldsTargetMarkupOutOfStepTitles() {
        String skill = ""
                + "---\nname: markup\n---\n"
                + "\n## Step 1: Read the `SKILL.md` **first**\n\nBody.\n";

        SkillContractExtractor.Result result = extractor.extract(
                Arrays.asList(file("markup/SKILL.md", skill)));

        assertEquals("Step 1: Read the SKILL.md first", result.getContract().getSteps().get(0).getTitle());
    }
}
