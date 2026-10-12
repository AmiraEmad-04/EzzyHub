package com.codeanalyzer.service;

import com.codeanalyzer.model.FileNode;
import com.codeanalyzer.model.ProjectStructure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ProjectStructureService {

    private static final Logger log = LoggerFactory.getLogger(ProjectStructureService.class);

    private static final Map<String, String> EXTENSION_TO_LANGUAGE = new HashMap<>();
    static {
        // JVM
        EXTENSION_TO_LANGUAGE.put("java", "Java");
        EXTENSION_TO_LANGUAGE.put("kt", "Kotlin");
        EXTENSION_TO_LANGUAGE.put("scala", "Scala");
        EXTENSION_TO_LANGUAGE.put("groovy", "Groovy");
        // Web
        EXTENSION_TO_LANGUAGE.put("js", "JavaScript");
        EXTENSION_TO_LANGUAGE.put("mjs", "JavaScript");
        EXTENSION_TO_LANGUAGE.put("cjs", "JavaScript");
        EXTENSION_TO_LANGUAGE.put("ts", "TypeScript");
        EXTENSION_TO_LANGUAGE.put("tsx", "TypeScript");
        EXTENSION_TO_LANGUAGE.put("jsx", "JavaScript");
        EXTENSION_TO_LANGUAGE.put("html", "HTML");
        EXTENSION_TO_LANGUAGE.put("htm", "HTML");
        EXTENSION_TO_LANGUAGE.put("css", "CSS");
        EXTENSION_TO_LANGUAGE.put("scss", "SCSS");
        EXTENSION_TO_LANGUAGE.put("sass", "SASS");
        EXTENSION_TO_LANGUAGE.put("less", "LESS");
        // Python
        EXTENSION_TO_LANGUAGE.put("py", "Python");
        EXTENSION_TO_LANGUAGE.put("pyx", "Cython");
        // Systems
        EXTENSION_TO_LANGUAGE.put("c", "C");
        EXTENSION_TO_LANGUAGE.put("h", "C");
        EXTENSION_TO_LANGUAGE.put("cpp", "C++");
        EXTENSION_TO_LANGUAGE.put("cc", "C++");
        EXTENSION_TO_LANGUAGE.put("cxx", "C++");
        EXTENSION_TO_LANGUAGE.put("hpp", "C++");
        EXTENSION_TO_LANGUAGE.put("rs", "Rust");
        EXTENSION_TO_LANGUAGE.put("go", "Go");
        // .NET
        EXTENSION_TO_LANGUAGE.put("cs", "C#");
        EXTENSION_TO_LANGUAGE.put("fs", "F#");
        EXTENSION_TO_LANGUAGE.put("vb", "VB.NET");
        // Mobile
        EXTENSION_TO_LANGUAGE.put("swift", "Swift");
        EXTENSION_TO_LANGUAGE.put("m", "Objective-C");
        EXTENSION_TO_LANGUAGE.put("dart", "Dart");
        // Scripting
        EXTENSION_TO_LANGUAGE.put("rb", "Ruby");
        EXTENSION_TO_LANGUAGE.put("php", "PHP");
        EXTENSION_TO_LANGUAGE.put("sh", "Shell");
        EXTENSION_TO_LANGUAGE.put("bash", "Shell");
        EXTENSION_TO_LANGUAGE.put("zsh", "Shell");
        EXTENSION_TO_LANGUAGE.put("ps1", "PowerShell");
        EXTENSION_TO_LANGUAGE.put("lua", "Lua");
        EXTENSION_TO_LANGUAGE.put("r", "R");
        EXTENSION_TO_LANGUAGE.put("jl", "Julia");
        EXTENSION_TO_LANGUAGE.put("ex", "Elixir");
        EXTENSION_TO_LANGUAGE.put("exs", "Elixir");
        EXTENSION_TO_LANGUAGE.put("erl", "Erlang");
        EXTENSION_TO_LANGUAGE.put("clj", "Clojure");
        EXTENSION_TO_LANGUAGE.put("hs", "Haskell");
        // Config / Data
        EXTENSION_TO_LANGUAGE.put("json", "JSON");
        EXTENSION_TO_LANGUAGE.put("yml", "YAML");
        EXTENSION_TO_LANGUAGE.put("yaml", "YAML");
        EXTENSION_TO_LANGUAGE.put("xml", "XML");
        EXTENSION_TO_LANGUAGE.put("toml", "TOML");
        EXTENSION_TO_LANGUAGE.put("ini", "INI");
        EXTENSION_TO_LANGUAGE.put("env", "Config");
        EXTENSION_TO_LANGUAGE.put("properties", "Config");
        EXTENSION_TO_LANGUAGE.put("conf", "Config");
        EXTENSION_TO_LANGUAGE.put("cfg", "Config");
        // Docs
        EXTENSION_TO_LANGUAGE.put("md", "Markdown");
        EXTENSION_TO_LANGUAGE.put("mdx", "Markdown");
        EXTENSION_TO_LANGUAGE.put("rst", "reStructuredText");
        EXTENSION_TO_LANGUAGE.put("txt", "Text");
        // Build
        EXTENSION_TO_LANGUAGE.put("gradle", "Gradle");
        EXTENSION_TO_LANGUAGE.put("cmake", "CMake");
        EXTENSION_TO_LANGUAGE.put("makefile", "Makefile");
        // Database
        EXTENSION_TO_LANGUAGE.put("sql", "SQL");
        EXTENSION_TO_LANGUAGE.put("graphql", "GraphQL");
        EXTENSION_TO_LANGUAGE.put("gql", "GraphQL");
        // Infrastructure
        EXTENSION_TO_LANGUAGE.put("tf", "Terraform");
        EXTENSION_TO_LANGUAGE.put("hcl", "HCL");
        EXTENSION_TO_LANGUAGE.put("dockerfile", "Docker");
    }

    private static final Set<String> ENTRY_POINT_NAMES = Set.of(
        "main.java", "app.java", "application.java", "main.go", "main.py", "app.py",
        "server.py", "index.js", "server.js", "app.js", "main.ts", "index.ts",
        "main.rs", "main.cpp", "main.c", "program.cs", "main.kt", "main.rb",
        "index.html", "index.php"
    );

    private static final Set<String> BUILD_FILE_NAMES = Set.of(
        "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle",
        "package.json", "package-lock.json", "yarn.lock", "pnpm-lock.yaml",
        "cargo.toml", "cargo.lock", "go.mod", "go.sum",
        "requirements.txt", "setup.py", "setup.cfg", "pyproject.toml", "pipfile",
        "gemfile", "gemfile.lock", "composer.json", "makefile", "cmake",
        "dockerfile", "docker-compose.yml", "docker-compose.yaml",
        ".github", "build.xml", "build.sbt"
    );

    private static final Set<String> CONFIG_FILE_NAMES = Set.of(
            "application.properties", "application.yml", "application.yaml",
        ".env", ".env.example", ".env.local",
        "webpack.config.js", "vite.config.js", "rollup.config.js",
        "tsconfig.json", "jsconfig.json", ".eslintrc", ".prettierrc",
        "jest.config.js", "vitest.config.js", "babel.config.js",
        "nginx.conf", "apache.conf", "web.xml", "context.xml",
        "appsettings.json", "settings.py", "config.py", "config.rb",
        ".gitignore", ".gitattributes", "sonar-project.properties"
    );

    /**
     * Build a complete ProjectStructure from a map of file paths -> content
     */
    public ProjectStructure buildStructure(Map<String, String> fileContents, String projectName) {
        ProjectStructure structure = new ProjectStructure();

        // Build tree
        FileNode root = new FileNode(projectName, "/", "directory");
        Map<String, Integer> langDist = new LinkedHashMap<>();
        Map<String, Integer> extDist = new LinkedHashMap<>();
        int[] totalFiles = {0};
        int[] totalDirs = {0};
        long[] totalSize = {0};
        int[] maxDepth = {0};
        List<String> entryPoints = new ArrayList<>();
        List<String> configFiles = new ArrayList<>();
        List<String> buildFiles = new ArrayList<>();

        for (Map.Entry<String, String> entry : fileContents.entrySet()) {
            String path = entry.getKey();
            // Remove common top-level prefix
            String normalizedPath = stripRootPrefix(path, fileContents.keySet());

            String[] parts = normalizedPath.split("/");
            if (parts.length == 0) continue;

            insertIntoTree(root, parts, 0, path, entry.getValue());

            // Stats
            String fileName = parts[parts.length - 1].toLowerCase();
            String ext = getExtension(fileName);
            totalFiles[0]++;
            maxDepth[0] = Math.max(maxDepth[0], parts.length);

            if (!ext.isEmpty()) {
                extDist.merge(ext, 1, Integer::sum);
                String lang = EXTENSION_TO_LANGUAGE.getOrDefault(ext, null);
                if (lang != null) langDist.merge(lang, 1, Integer::sum);
            } else {
                // No extension - check by filename
                String lang = EXTENSION_TO_LANGUAGE.getOrDefault(fileName, null);
                if (lang != null) langDist.merge(lang, 1, Integer::sum);
            }

            if (ENTRY_POINT_NAMES.contains(fileName)) entryPoints.add(path);
            if (CONFIG_FILE_NAMES.contains(fileName)) configFiles.add(path);
            if (BUILD_FILE_NAMES.contains(fileName)) buildFiles.add(path);
        }

        // Sort language distribution by count
        Map<String, Integer> sortedLang = langDist.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                (e1, e2) -> e1, LinkedHashMap::new));

        Map<String, Integer> sortedExt = extDist.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                (e1, e2) -> e1, LinkedHashMap::new));

        // Top-level dirs
        List<String> topLevelDirs = new ArrayList<>();
        if (root.getChildren() != null) {
            root.getChildren().stream()
                .filter(n -> "directory".equals(n.getType()))
                .map(FileNode::getName)
                .forEach(topLevelDirs::add);
        }

        ProjectStructure.ProjectStats stats = new ProjectStructure.ProjectStats();
        stats.setTotalFiles(totalFiles[0]);
        stats.setTotalDirectories(totalDirs[0]);
        stats.setTotalSizeBytes(totalSize[0]);
        stats.setLanguageDistribution(sortedLang);
        stats.setExtensionDistribution(sortedExt);
        stats.setMaxDepth(maxDepth[0]);
        stats.setTopLevelDirs(topLevelDirs);

        structure.setTree(root);
        structure.setStats(stats);
        structure.setEntryPoints(entryPoints);
        structure.setConfigFiles(configFiles);
        structure.setBuildFiles(buildFiles);

        log.info("Built project structure: {} files, {} languages, depth {}", 
            totalFiles[0], sortedLang.size(), maxDepth[0]);
        return structure;
    }

    private void insertIntoTree(FileNode node, String[] parts, int depth, String fullPath, String content) {
        if (depth >= parts.length) return;

        String part = parts[depth];
        if (part.isBlank()) return;

        if (depth == parts.length - 1) {
            // It's a file
            FileNode fileNode = new FileNode(part, fullPath, "file");
            fileNode.setExtension(getExtension(part.toLowerCase()));
            String lang = EXTENSION_TO_LANGUAGE.getOrDefault(fileNode.getExtension(), null);
            if (lang == null) lang = EXTENSION_TO_LANGUAGE.getOrDefault(part.toLowerCase(), null);
            fileNode.setLanguage(lang);
            if (content != null) fileNode.setSize((long) content.length());
            node.addChild(fileNode);
        } else {
            // It's a directory - find or create
            FileNode dirNode = null;
            if (node.getChildren() != null) {
                for (FileNode child : node.getChildren()) {
                    if ("directory".equals(child.getType()) && part.equals(child.getName())) {
                        dirNode = child;
                        break;
                    }
                }
            }
            if (dirNode == null) {
                dirNode = new FileNode(part, buildPath(parts, depth), "directory");
                node.addChild(dirNode);
            }
            insertIntoTree(dirNode, parts, depth + 1, fullPath, content);
        }
    }

    private String buildPath(String[] parts, int upTo) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= upTo; i++) {
            if (i > 0) sb.append('/');
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    private String getExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return (dot >= 0 && dot < fileName.length() - 1) ? fileName.substring(dot + 1) : "";
    }

    /**
     * Strip common root prefix from paths for cleaner tree
     */
    private String stripRootPrefix(String path, Set<String> allPaths) {
        // Find if all paths start with the same prefix (e.g., "myproject/")
        String[] parts = path.split("/");
        if (parts.length > 1) {
            // Remove first segment if it's the same for all (common ZIP root folder)
            String prefix = parts[0] + "/";
            boolean allHavePrefix = allPaths.stream().allMatch(p -> p.startsWith(prefix));
            if (allHavePrefix) {
                return path.substring(prefix.length());
            }
        }
        return path;
    }

    /**
     * Generate a compact text representation of the tree for LLM prompt
     */
    public String generateTreeText(FileNode root, int maxLines) {
        StringBuilder sb = new StringBuilder();
        printTree(root, "", sb, new int[]{0}, maxLines);
        return sb.toString();
    }

    private void printTree(FileNode node, String indent, StringBuilder sb, int[] lineCount, int maxLines) {
        if (lineCount[0] >= maxLines) return;
        if (!"directory".equals(node.getType()) || node.getChildren() == null) return;

        List<FileNode> children = node.getChildren();
        for (int i = 0; i < children.size() && lineCount[0] < maxLines; i++) {
            FileNode child = children.get(i);
            boolean isLast = (i == children.size() - 1);
            String connector = isLast ? "└── " : "├── ";
            sb.append(indent).append(connector).append(child.getName());
            if ("directory".equals(child.getType())) sb.append("/");
            sb.append("\n");
            lineCount[0]++;
            if ("directory".equals(child.getType())) {
                String childIndent = indent + (isLast ? "    " : "│   ");
                printTree(child, childIndent, sb, lineCount, maxLines);
            }
        }
    }
}
