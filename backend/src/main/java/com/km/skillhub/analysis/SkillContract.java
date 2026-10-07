package com.km.skillhub.analysis;

import java.util.ArrayList;
import java.util.List;

/**
 * Expected execution steps extracted from one skill version.
 *
 * <p>A contract is the baseline for "did the agent actually do what the skill asked".
 * It is parsed once per (slug, version) and reused by every observed turn.
 *
 * <p>Identity is per leaf skill: composite packages (a parent folder that only groups
 * sub-skills) carry no request of their own, so only leaf skills get a contract.
 */
public class SkillContract {

    /** One expected step. Missing a non-optional step is a completeness problem. */
    public static class Step {
        private String id;
        private String title;
        private String description;
        private List<String> keywords = new ArrayList<String>();
        private List<String> expectedTools = new ArrayList<String>();
        private List<String> expectedArtifacts = new ArrayList<String>();
        /** Optional steps never count against completeness. */
        private boolean optional;
        /** Steps sharing a group may run in any order; null means "in the listed order". */
        private String parallelGroup;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public List<String> getKeywords() { return keywords; }
        public void setKeywords(List<String> keywords) { this.keywords = keywords == null ? new ArrayList<String>() : keywords; }
        public List<String> getExpectedTools() { return expectedTools; }
        public void setExpectedTools(List<String> expectedTools) { this.expectedTools = expectedTools == null ? new ArrayList<String>() : expectedTools; }
        public List<String> getExpectedArtifacts() { return expectedArtifacts; }
        public void setExpectedArtifacts(List<String> expectedArtifacts) { this.expectedArtifacts = expectedArtifacts == null ? new ArrayList<String>() : expectedArtifacts; }
        public boolean isOptional() { return optional; }
        public void setOptional(boolean optional) { this.optional = optional; }
        public String getParallelGroup() { return parallelGroup; }
        public void setParallelGroup(String parallelGroup) { this.parallelGroup = parallelGroup; }
    }

    private int schemaVersion = 1;
    private List<Step> steps = new ArrayList<Step>();
    private List<String> preconditions = new ArrayList<String>();
    /** Phrases that should lead an agent to pick this skill. */
    private List<String> triggers = new ArrayList<String>();
    /** Things the skill explicitly says not to do. */
    private List<String> forbidden = new ArrayList<String>();
    private List<String> entryCommands = new ArrayList<String>();

    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }
    public List<Step> getSteps() { return steps; }
    public void setSteps(List<Step> steps) { this.steps = steps == null ? new ArrayList<Step>() : steps; }
    public List<String> getPreconditions() { return preconditions; }
    public void setPreconditions(List<String> preconditions) { this.preconditions = preconditions == null ? new ArrayList<String>() : preconditions; }
    public List<String> getTriggers() { return triggers; }
    public void setTriggers(List<String> triggers) { this.triggers = triggers == null ? new ArrayList<String>() : triggers; }
    public List<String> getForbidden() { return forbidden; }
    public void setForbidden(List<String> forbidden) { this.forbidden = forbidden == null ? new ArrayList<String>() : forbidden; }
    public List<String> getEntryCommands() { return entryCommands; }
    public void setEntryCommands(List<String> entryCommands) { this.entryCommands = entryCommands == null ? new ArrayList<String>() : entryCommands; }

    /** Non-optional step count; zero means the contract cannot judge completeness. */
    public int requiredStepCount() {
        int count = 0;
        for (Step step : steps) {
            if (!step.isOptional()) count += 1;
        }
        return count;
    }
}
