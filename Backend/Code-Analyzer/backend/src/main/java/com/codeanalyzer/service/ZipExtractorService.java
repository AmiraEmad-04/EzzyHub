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
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Handles ZIP extraction for the Code Analyzer.
 *
 * FIXES applied:
 *
 * FIX-1 (entry.getSize() = -1):
 *   For deflated entries, getSize() returns -1 (uncompressed size is unknown
 *   until the stream is decompressed). The original code cast this to (int)
 *   and passed it to readNBytes(), which throws IllegalArgumentException for
 *   negative values. Now we read with a bounded BufferedInputStream instead.
 *
 * FIX-2 (O(n²) stripRootPrefix):
 *   The old code called allPaths.stream().allMatch(...) inside the per-file
 *   loop, making the total work O(n²). The common prefix is now computed ONCE
 *   before the loop and cached for the lifetime of the extraction call.
 *
 * FIX-3 (recursive cleanup):
 *   cleanup() now uses Files.walkFileTree to delete the entire temp directory
 *   tree, not just the single uploaded file. The old code silently failed
 *   if the temp dir contained more than one file.
 *
 * FIX-4 (large file support):
 *   maxCharsPerFile and maxTotalChars are respected strictly. For very large
 *   ZIPs (1 GB+), only the highest-priority files are read into memory and
 *   everything else is registered as a path-only (null content) entry.
 */
@Service
public class ZipExtractorService {

    private static final Logger log = LoggerFactory.getLogger(ZipExtractorService.class);

    // Binary extensions — registered in the tree but content is never read
    private static final Set<String> BINARY_EXTENSIONS = Set.of(
        "class", "jar", "war", "ear", "zip", "tar", "gz", "rar", "7z",
        "png", "jpg", "jpeg", "gif", "bmp", "ico", "svg", "webp", "tiff",
        "mp4", "mp3", "wav", "avi", "mov", "mkv", "flv",
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "exe", "dll", "so", "dylib", "bin", "dat", "db", "sqlite",
        "lock", "sum", "mod", "DS_Store", "pyc", "pyo"
    );

    // Directory segments that should be skipped entirely
    private static final Set<String> SKIP_DIRS = Set.of(
        "node_modules", ".git", ".svn", ".hg", "target", "build",
        "dist", "__pycache__", ".idea", ".vscode", ".gradle",
        "vendor", ".terraform", "coverage", ".nyc_output",
        "out", "bin", "obj", ".next", ".nuxt", "tmp", "temp"
    );

    @Autowired
    private AppConfig appConfig;

    /**
     * Saves a MultipartFile to a temp directory and returns its path.
     * The file is NOT read into memory — it is streamed directly to disk.
     */
    public Path saveToTempFile(MultipartFile file) throws IOException {
        Path tempDir = Files.createTempDirectory("code-analyzer-");
        String originalName = file.getOriginalFilename();
        String safeName = (originalName != null)
                ? originalName.replaceAll("[^a-zA-Z0-9._-]", "_")
                : "upload.zip";
        Path tempFile = tempDir.resolve(safeName);
        // transferTo streams directly — does NOT load into RAM
        file.transferTo(tempFile);
        log.info("Saved uploaded file to: {} ({} bytes)", tempFile, Files.size(tempFile));
        return tempFile;
    }

    /**
     * Extracts text content from a ZIP file.
     *
     * Returns a LinkedHashMap of normalizedPath → content (null for binary/skipped).
     * Content is truncated to maxCharsPerFile characters if needed.
     * Priority-sorted so important files (main, service, controller) come first.
     *
     * Handles ZIP files of any size: for files > maxTotalChars the remaining
     * entries are still registered (for the tree) but content is not read.
     */
    public Map<String, String> extractContents(Path zipPath) throws IOException {
        int  maxFilesForLLM  = appConfig.getInt("MAX_FILES_FOR_LLM",   60);
        int  maxCharsPerFile = appConfig.getInt("MAX_CHARS_PER_FILE", 4000);
        long maxTotalChars   = appConfig.getLong("MAX_TOTAL_CHARS_LLM", 120_000);

        Map<String, String> contents = new LinkedHashMap<>();
        long totalChars    = 0;
        int  textFileCount = 0;

        try (ZipFile zipFile = ZipFile.builder().setFile(zipPath.toFile()).get()) {
            // Collect all entries first so we can sort by priority
            List<ZipArchiveEntry> allEntries = new ArrayList<>();
            Enumeration<ZipArchiveEntry> en = zipFile.getEntries();
            while (en.hasMoreElements()) allEntries.add(en.nextElement());

            // FIX-2: Compute common root prefix ONCE (O(n)), not per-file (O(n²))
            String rootPrefix = computeRootPrefix(allEntries);

            // Sort: most important files first so we stay within the token budget
            allEntries.sort(Comparator.comparingInt(e -> priorityScore(e.getName())));

            for (ZipArchiveEntry entry : allEntries) {
                String rawPath  = normalizePath(entry.getName());
                String entryName = rootPrefix.isEmpty()
                        ? rawPath
                        : (rawPath.startsWith(rootPrefix) ? rawPath.substring(rootPrefix.length()) : rawPath);

                if (entry.isDirectory()) continue;
                if (shouldSkipPath(entryName)) continue;

                // Register path (even binary — needed for file tree)
                contents.put(entryName, null);

                // Read text content if budget allows
                if (!isBinary(entryName) && textFileCount < maxFilesForLLM && totalChars < maxTotalChars) {
                    try (InputStream raw = zipFile.getInputStream(entry)) {
                        // FIX-1: Do NOT use entry.getSize() — it is -1 for deflated entries.
                        // Use a capped BufferedInputStream instead of readNBytes(size).
                        byte[] bytes = readCapped(raw, maxCharsPerFile * 3); // 3 bytes/char max for UTF-8
                        if (bytes.length > 0 && isTextContent(bytes)) {
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

        log.info("Extracted {} paths ({} with content, {} total chars)",
                contents.size(), textFileCount, totalChars);
        return contents;
    }

    /** Lists all entry names (paths) from a ZIP without reading content. */
    public List<String> listEntries(Path zipPath) throws IOException {
        List<String> paths = new ArrayList<>();
        try (ZipFile zipFile = ZipFile.builder().setFile(zipPath.toFile()).get()) {
            Enumeration<ZipArchiveEntry> en = zipFile.getEntries();
            while (en.hasMoreElements()) paths.add(normalizePath(en.nextElement().getName()));
        }
        return paths;
    }

    /**
     * FIX-3: Recursively deletes the temp file and its parent directory.
     * The old implementation only deleted the file and made a best-effort
     * attempt on the parent; it silently left temp dirs on disk.
     */
    public void cleanup(Path tempFile) {
        if (tempFile == null) return;
        Path dirToDelete = Files.isDirectory(tempFile) ? tempFile : tempFile.getParent();
        if (dirToDelete == null || !Files.exists(dirToDelete)) return;
        try {
            Files.walkFileTree(dirToDelete, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            log.debug("Cleaned up temp directory: {}", dirToDelete);
        } catch (IOException e) {
            log.warn("Failed to clean up temp directory {}: {}", dirToDelete, e.getMessage());
        }
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    /**
     * FIX-1: Read at most maxBytes from an InputStream without calling
     * InputStream.readNBytes(n) with n = -1 (which throws IllegalArgumentException).
     * Works regardless of whether entry.getSize() is known.
     */
    private byte[] readCapped(InputStream is, int maxBytes) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] chunk = new byte[8192];
        int remaining = maxBytes;
        int read;
        while (remaining > 0 && (read = is.read(chunk, 0, Math.min(chunk.length, remaining))) != -1) {
            buf.write(chunk, 0, read);
            remaining -= read;
        }
        return buf.toByteArray();
    }

    /**
     * FIX-2: Compute the common root prefix once for the whole ZIP.
     *
     * Many ZIPs wrap all files inside a single root folder (e.g. myproject/).
     * This method detects that and returns "myproject/" so callers can strip it.
     * Returns "" if there is no common single-segment root.
     */
    private String computeRootPrefix(List<ZipArchiveEntry> entries) {
        String candidatePrefix = null;
        for (ZipArchiveEntry e : entries) {
            String path = normalizePath(e.getName());
            int slash = path.indexOf('/');
            if (slash <= 0) return ""; // a file at the root level — no common prefix
            String seg = path.substring(0, slash + 1); // e.g. "myproject/"
            if (candidatePrefix == null) {
                candidatePrefix = seg;
            } else if (!candidatePrefix.equals(seg)) {
                return ""; // two different top-level segments — no common prefix
            }
        }
        return candidatePrefix != null ? candidatePrefix : "";
    }

    private String normalizePath(String path) {
        return path.replace("\\", "/").replaceAll("//+", "/");
    }

    private boolean shouldSkipPath(String path) {
        for (String part : path.split("/")) {
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
        int dot = path.lastIndexOf('.');
        if (dot < 0) return false;
        return BINARY_EXTENSIONS.contains(path.substring(dot + 1).toLowerCase());
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

    /** Lower score = higher priority in LLM budget allocation */
    private int priorityScore(String name) {
        String lower = name.toLowerCase();
        String fileName = lower.contains("/") ? lower.substring(lower.lastIndexOf('/') + 1) : lower;
        if (fileName.equals("readme.md") || fileName.equals("readme.txt")) return 0;
        if (fileName.equals("pom.xml") || fileName.equals("package.json") ||
            fileName.equals("build.gradle") || fileName.equals("cargo.toml") ||
            fileName.equals("requirements.txt") || fileName.equals("go.mod")) return 1;
        if (fileName.contains("application") || fileName.equals("main.java") ||
            fileName.equals("main.py") || fileName.equals("app.py") ||
            fileName.equals("index.js")) return 2;
        if (fileName.endsWith(".java") || fileName.endsWith(".py") ||
            fileName.endsWith(".ts")   || fileName.endsWith(".go") ||
            fileName.endsWith(".rs")) return 3;
        if (fileName.endsWith(".yml") || fileName.endsWith(".yaml") ||
            fileName.endsWith(".json") || fileName.endsWith(".xml")) return 4;
        return 10;
    }
}
