package com.codeanalyzer.model;

import java.util.List;
import java.util.Map;

public class ProjectStructure {

    private FileNode tree;
    private ProjectStats stats;
    private List<String> entryPoints;
    private List<String> configFiles;
    private List<String> buildFiles;

    public static class ProjectStats {
        private int totalFiles;
        private int totalDirectories;
        private long totalSizeBytes;
        private Map<String, Integer> languageDistribution; // language -> file count
        private Map<String, Integer> extensionDistribution; // ext -> file count
        private int maxDepth;
        private List<String> topLevelDirs;

        // Getters and setters
        public int getTotalFiles() { return totalFiles; }
        public void setTotalFiles(int totalFiles) { this.totalFiles = totalFiles; }

        public int getTotalDirectories() { return totalDirectories; }
        public void setTotalDirectories(int totalDirectories) { this.totalDirectories = totalDirectories; }

        public long getTotalSizeBytes() { return totalSizeBytes; }
        public void setTotalSizeBytes(long totalSizeBytes) { this.totalSizeBytes = totalSizeBytes; }

        public Map<String, Integer> getLanguageDistribution() { return languageDistribution; }
        public void setLanguageDistribution(Map<String, Integer> languageDistribution) {
            this.languageDistribution = languageDistribution;
        }

        public Map<String, Integer> getExtensionDistribution() { return extensionDistribution; }
        public void setExtensionDistribution(Map<String, Integer> extensionDistribution) {
            this.extensionDistribution = extensionDistribution;
        }

        public int getMaxDepth() { return maxDepth; }
        public void setMaxDepth(int maxDepth) { this.maxDepth = maxDepth; }

        public List<String> getTopLevelDirs() { return topLevelDirs; }
        public void setTopLevelDirs(List<String> topLevelDirs) { this.topLevelDirs = topLevelDirs; }
    }

    // Getters and setters
    public FileNode getTree() { return tree; }
    public void setTree(FileNode tree) { this.tree = tree; }

    public ProjectStats getStats() { return stats; }
    public void setStats(ProjectStats stats) { this.stats = stats; }

    public List<String> getEntryPoints() { return entryPoints; }
    public void setEntryPoints(List<String> entryPoints) { this.entryPoints = entryPoints; }

    public List<String> getConfigFiles() { return configFiles; }
    public void setConfigFiles(List<String> configFiles) { this.configFiles = configFiles; }

    public List<String> getBuildFiles() { return buildFiles; }
    public void setBuildFiles(List<String> buildFiles) { this.buildFiles = buildFiles; }
}
