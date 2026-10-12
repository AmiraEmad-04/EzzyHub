package com.codeanalyzer.service;

import com.codeanalyzer.config.AppConfig;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

@Service
public class ZipExtractorService {

    private static final Logger log = LoggerFactory.getLogger(ZipExtractorService.class);

    // Binary extensions we skip for content reading
    private static final Set<String> BINARY_EXTENSIONS = Set.of(
        "class", "jar", "war", "ear", "zip", "tar", "gz", "rar", "7z",
        "png", "jpg", "jpeg", "gif", "bmp", "ico", "svg", "webp", "tiff",
        "mp4", "mp3", "wav", "avi", "mov", "mkv", "flv",
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "exe", "dll", "so", "dylib", "bin", "dat", "db", "sqlite",
        "lock", "sum", "mod", "DS_Store", "pyc", "pyo"
    );

    // Directories to skip
    private static final Set<String> SKIP_DIRS = Set.of(
        "node_modules", ".git", ".svn", ".hg", "target", "build",
        "dist", "__pycache__", ".idea", ".vscode", ".gradle",
        "vendor", ".terraform", "coverage", ".nyc_output",
        "out", "bin", "obj", ".next", ".nuxt", "tmp", "temp"
    );

    @Autowired
    private AppConfig appConfig;

    /**
     * Saves MultipartFile to a temp file and returns its path
     */
    public Path saveToTempFile(MultipartFile file) throws IOException {
        Path tempDir = Files.createTempDirectory("code-analyzer-");
        String originalName = file.getOriginalFilename();
        String safeName = (originalName != null) ? originalName.replaceAll("[^a-zA-Z0-9._-]", "_") : "upload.zip";
        Path tempFile = tempDir.resolve(safeName);
        file.transferTo(tempFile);
        log.info("Saved uploaded file to: {}", tempFile);
        return tempFile;
    }

    /**
     * Extract ZIP file entries into a map of path -> content (null for binary/skipped)
     */
    public Map<String, String> extractContents(Path zipPath) throws IOException {
        int maxFilesForLLM = appConfig.getInt("MAX_FILES_FOR_LLM", 60);
        int maxCharsPerFile = appConfig.getInt("MAX_CHARS_PER_FILE", 4000);
        long maxTotalChars = appConfig.getLong("MAX_TOTAL_CHARS_LLM", 120000);
        int maxZipEntries = appConfig.getInt("MAX_ZIP_ENTRIES", 20000);

        Map<String, String> contents = new LinkedHashMap<>();
        long totalChars = 0;
        int textFileCount = 0;

        try (ZipFile zipFile = ZipFile.builder().setFile(zipPath.toFile()).get()) {
            Enumeration<ZipArchiveEntry> entries = zipFile.getEntries();
            List<ZipArchiveEntry> sortedEntries = new ArrayList<>();
            while (entries.hasMoreElements()) {
                sortedEntries.add(entries.nextElement());
                // Bail out early on archives crafted with an excessive entry count
                // (e.g. zip-bomb style archives) before sorting/processing them all.
                if (sortedEntries.size() > maxZipEntries) {
                    throw new IllegalArgumentException("ZIP contains more than " + maxZipEntries + " entries");
                }
            }

            // Sort: prioritize important files first
            sortedEntries.sort(Comparator.comparingInt(e -> priorityScore(e.getName())));

            for (ZipArchiveEntry entry : sortedEntries) {
                String entryName = normalizePath(entry.getName());

                // Skip directories
                if (entry.isDirectory()) continue;

                // Reject path traversal / absolute entry names (zip-slip) defensively.
                if (isUnsafePath(entryName)) {
                    log.warn("Skipping unsafe ZIP entry path: {}", entry.getName());
                    continue;
                }

                // Skip hidden/ignored directories
                if (shouldSkipPath(entryName)) continue;

                // Register the file (even if binary - for tree structure)
                contents.put(entryName, null);

                // Read content for text files only
                if (!isBinary(entryName) && textFileCount < maxFilesForLLM && totalChars < maxTotalChars) {
                    try (InputStream is = zipFile.getInputStream(entry)) {
                        byte[] bytes = is.readNBytes((int) Math.min(entry.getSize(), maxCharsPerFile * 2L));
                        if (isTextContent(bytes)) {
                            String content = new String(bytes, StandardCharsets.UTF_8);
                            if (content.length() > maxCharsPerFile) {
                                content = content.substring(0, maxCharsPerFile) + "\n... [truncated]";
                            }
                            contents.put(entryName, content);
                            totalChars += content.length();
                            textFileCount++;
                        }
                    } catch (Exception e) {
                        log.debug("Could not read content of {}: {}", entryName, e.getMessage());
                    }
                }
            }
        }

        log.info("Extracted {} files ({} with content, {} total chars)", 
            contents.size(), textFileCount, totalChars);
        return contents;
    }

    /**
     * Get all entry names (paths) from ZIP without reading content
     */
    public List<String> listEntries(Path zipPath) throws IOException {
        List<String> paths = new ArrayList<>();
        try (ZipFile zipFile = ZipFile.builder().setFile(zipPath.toFile()).get()) {
            Enumeration<ZipArchiveEntry> entries = zipFile.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                paths.add(normalizePath(entry.getName()));
            }
        }
        return paths;
    }

    private String normalizePath(String path) {
        return path.replace("\\", "/").replaceAll("//+", "/");
    }

    /**
     * Detects zip-slip style entry names: absolute paths or ".." traversal segments.
     */
    private boolean isUnsafePath(String normalizedPath) {
        if (normalizedPath.startsWith("/")) return true;
        if (normalizedPath.matches("^[A-Za-z]:.*")) return true; // Windows drive-letter absolute path
        for (String part : normalizedPath.split("/")) {
            if (part.equals("..")) return true;
        }
        return false;
    }

    private boolean shouldSkipPath(String path) {
        String[] parts = path.split("/");
        for (String part : parts) {
            if (SKIP_DIRS.contains(part)) return true;
            if (part.startsWith(".") && part.length() > 1 && !isImportantDotFile(part)) return true;
        }
        return false;
    }

    private boolean isImportantDotFile(String name) {
        return name.equals(".env") || name.equals(".env.example") || name.equals(".gitignore")
            || name.equals(".eslintrc") || name.equals(".prettierrc") || name.equals(".babelrc")
            || name.endsWith(".json") || name.endsWith(".yml") || name.endsWith(".yaml");
    }

    private boolean isBinary(String path) {
        int dotIdx = path.lastIndexOf('.');
        if (dotIdx < 0) return false;
        String ext = path.substring(dotIdx + 1).toLowerCase();
        return BINARY_EXTENSIONS.contains(ext);
    }

    private boolean isTextContent(byte[] bytes) {
        if (bytes.length == 0) return true;
        int check = Math.min(bytes.length, 512);
        int nonText = 0;
        for (int i = 0; i < check; i++) {
            int b = bytes[i] & 0xFF;
            if (b == 0) return false; // null byte = binary
            if (b < 8 || (b > 13 && b < 32 && b != 27)) nonText++;
        }
        return (double) nonText / check < 0.1;
    }

    /**
     * Priority score for sorting (lower = higher priority)
     */
    private int priorityScore(String name) {
        String lower = name.toLowerCase();
        String fileName = lower.contains("/") ? lower.substring(lower.lastIndexOf('/') + 1) : lower;

        if (fileName.equals("readme.md") || fileName.equals("readme.txt")) return 0;
        if (fileName.equals("package.json") || fileName.equals("pom.xml") || 
            fileName.equals("build.gradle") || fileName.equals("cargo.toml") ||
            fileName.equals("requirements.txt") || fileName.equals("go.mod")) return 1;
        if (fileName.equals("main.java") || fileName.equals("main.go") || 
            fileName.equals("main.py") || fileName.equals("app.py") ||
            fileName.equals("index.js") || fileName.equals("main.rs") ||
            fileName.equals("application.java") || fileName.contains("application")) return 2;
        if (fileName.endsWith(".java") || fileName.endsWith(".go") || 
            fileName.endsWith(".py") || fileName.endsWith(".js") ||
            fileName.endsWith(".ts") || fileName.endsWith(".rs")) return 3;
        if (fileName.endsWith(".yml") || fileName.endsWith(".yaml") ||
            fileName.endsWith(".json") || fileName.endsWith(".xml") ||
            fileName.endsWith(".toml")) return 4;
        return 10;
    }

    /**
     * Cleanup temp directory
     */
    public void cleanup(Path tempFile) {
        try {
            if (tempFile != null && Files.exists(tempFile)) {
                Path parent = tempFile.getParent();
                Files.deleteIfExists(tempFile);
                if (parent != null) Files.deleteIfExists(parent);
            }
        } catch (IOException e) {
            log.warn("Failed to clean up temp file: {}", e.getMessage());
        }
    }
}
