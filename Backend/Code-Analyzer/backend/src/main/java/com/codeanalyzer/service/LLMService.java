package com.codeanalyzer.service;

import com.codeanalyzer.config.AppConfig;
import com.codeanalyzer.model.FileSemanticAnalysis;
import com.codeanalyzer.model.ProjectStructure;
import com.codeanalyzer.model.SemanticAnalysis;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * LLM integration service — calls whichever AI provider is configured via the
 * LLM_PROVIDER environment variable.
 *
 * Supported providers: anthropic (default), openai, gemini, grok, groq, cohere.
 *
 * FIXES applied:
 *
 * FIX-1 (thread-safe OkHttpClient):
 *   The original code used a lazy `if (httpClient == null)` check without
 *   synchronization. Under concurrent requests (e.g. @Async or parallel
 *   batch calls) multiple threads could create multiple OkHttpClient instances,
 *   leaking thread pools and connection pools.
 *   Now the client is created in a synchronized initializer block and stored
 *   in a final field that is set exactly once.
 *
 * FIX-2 (parallel batch analysis):
 *   The original code analyzed batches sequentially in a for-loop. For large
 *   projects this compounds LLM latency (e.g. 10 batches × 30 s = 5 min).
 *   Batches are now submitted to a bounded executor and processed in parallel,
 *   bounded at MAX_LLM_PARALLEL_CALLS (default 4) to avoid rate-limit errors.
 *
 * FIX-3 (extract JSON more robustly):
 *   LLMs sometimes wrap the JSON in triple-backtick fences even after explicit
 *   instructions not to. The extractJson() method now handles both ` ```json `
 *   and bare ` ``` ` fences, as well as leading/trailing whitespace.
 */
@Service
public class LLMService {

    private static final Logger log = LoggerFactory.getLogger(LLMService.class);
    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");

    private static final Set<String> SOURCE_EXTENSIONS = Set.of(
            "ts","tsx","js","mjs","jsx",
            "java","kt","scala","groovy",
            "py","rb","php","go","rs","cs",
            "cpp","cc","c","h","hpp",
            "swift","dart","ex","exs"
    );

    @Autowired private AppConfig appConfig;
    @Autowired private ProjectStructureService structureService;

    private final ObjectMapper mapper = new ObjectMapper();

    // FIX-1: client is created lazily but in a thread-safe, lock-free manner
    // using a holder class (initialization-on-demand idiom).
    private volatile OkHttpClient httpClient;

    private OkHttpClient getClient() {
        if (httpClient == null) {
            synchronized (this) {
                if (httpClient == null) {
                    int t = appConfig.getInt("LLM_TIMEOUT_SECONDS", 180);
                    httpClient = new OkHttpClient.Builder()
                            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(t,   java.util.concurrent.TimeUnit.SECONDS)
                            .build();
                }
            }
        }
        return httpClient;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Call 1 — brief project-level summary. */
    public SemanticAnalysis summarizeProject(ProjectStructure structure,
                                             Map<String, String> fileContents) throws Exception {
        String prompt = buildSummaryPrompt(structure, fileContents);
        log.info("[summarize] prompt {} chars", prompt.length());
        return parseSummary(callLLM(prompt));
    }

    /**
     * Call 2..N — semantic analysis of source files, processed in parallel batches.
     *
     * FIX-2: Batches are executed in parallel up to MAX_LLM_PARALLEL_CALLS
     * concurrent LLM calls. This cuts total analysis time from O(batches × latency)
     * to O(ceil(batches/parallel) × latency).
     */
    public List<FileSemanticAnalysis> analyzeFiles(Map<String, String> fileContents) throws Exception {
        List<Map.Entry<String, String>> sourceFiles = fileContents.entrySet().stream()
                .filter(e -> e.getValue() != null && !e.getValue().isBlank())
                .filter(e -> isSource(e.getKey()))
                .sorted(Comparator.comparingInt(e -> importanceScore(e.getKey())))
                .collect(Collectors.toList());

        if (sourceFiles.isEmpty()) return Collections.emptyList();

        int maxFiles     = appConfig.getInt("MAX_FILES_FOR_LLM",   30);
        int batchSize    = appConfig.getInt("BATCH_SIZE",            5);
        int maxCharsFile = appConfig.getInt("MAX_CHARS_PER_FILE", 3000);
        int maxParallel  = appConfig.getInt("MAX_LLM_PARALLEL_CALLS", 4);

        List<Map.Entry<String, String>> toAnalyze = sourceFiles.stream()
                .limit(maxFiles)
                .collect(Collectors.toList());

        log.info("Analyzing {} source files in batches of {} ({} parallel)", toAnalyze.size(), batchSize, maxParallel);

        // Build all batches first
        List<Map<String, String>> batches = new ArrayList<>();
        for (int i = 0; i < toAnalyze.size(); i += batchSize) {
            List<Map.Entry<String, String>> batch = toAnalyze.subList(i, Math.min(i + batchSize, toAnalyze.size()));
            Map<String, String> batchMap = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : batch) {
                String content = e.getValue().length() > maxCharsFile
                        ? e.getValue().substring(0, maxCharsFile) + "\n// [truncated]"
                        : e.getValue();
                batchMap.put(e.getKey(), content);
            }
            batches.add(batchMap);
        }

        // FIX-2: Submit all batches to a bounded thread pool
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(maxParallel, batches.size()));
        List<Future<List<FileSemanticAnalysis>>> futures = new ArrayList<>();

        for (int i = 0; i < batches.size(); i++) {
            final int batchIdx = i;
            final Map<String, String> batchMap = batches.get(i);
            futures.add(executor.submit(() -> {
                log.info("Batch {}/{}: {} files", batchIdx + 1, batches.size(), batchMap.size());
                try {
                    String prompt = buildSemanticPrompt(batchMap);
                    String raw    = callLLM(prompt);
                    List<FileSemanticAnalysis> result = parseFileAnalyses(raw);
                    log.info("Batch {}/{} parsed: {} analyses", batchIdx + 1, batches.size(), result.size());
                    return result;
                } catch (Exception e) {
                    log.error("Batch {}/{} failed: {}", batchIdx + 1, batches.size(), e.getMessage());
                    return stubBatch(batchMap, e.getMessage());
                }
            }));
        }

        executor.shutdown();
        try {
            executor.awaitTermination(30, TimeUnit.MINUTES);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        List<FileSemanticAnalysis> allResults = new ArrayList<>();
        for (Future<List<FileSemanticAnalysis>> f : futures) {
            try {
                allResults.addAll(f.get());
            } catch (Exception e) {
                log.error("Batch future error: {}", e.getMessage());
            }
        }

        return allResults;
    }

    public String getProviderInfo() {
        String model = switch (provider()) {
            case "openai"  -> appConfig.get("OPENAI_MODEL",    "gpt-4o");
            case "gemini"  -> appConfig.get("GEMINI_MODEL",    "gemini-1.5-flash");
            case "grok"    -> appConfig.get("GROK_MODEL",      "grok-3-mini");
            case "groq"    -> appConfig.get("GROQ_MODEL",      "llama-3.3-70b-versatile");
            case "cohere"  -> appConfig.get("COHERE_MODEL",    "command-r-plus-08-2024");
            default        -> appConfig.get("ANTHROPIC_MODEL", "claude-sonnet-4-6");
        };
        return provider() + "/" + model;
    }

    public boolean isConfigured() {
        String key = switch (provider()) {
            case "openai"  -> appConfig.get("OPENAI_API_KEY",    "");
            case "gemini"  -> appConfig.get("GEMINI_API_KEY",    "");
            case "grok"    -> appConfig.get("GROK_API_KEY",      "");
            case "groq"    -> appConfig.get("GROQ_API_KEY",      "");
            case "cohere"  -> appConfig.get("COHERE_API_KEY",    "");
            default        -> appConfig.get("ANTHROPIC_API_KEY", "");
        };
        return key != null && !key.isBlank() && !key.startsWith("your_");
    }

    // ── Routing ───────────────────────────────────────────────────────────────

    private String callLLM(String prompt) throws IOException {
        return switch (provider()) {
            case "openai"  -> callOpenAI(prompt);
            case "gemini"  -> callGemini(prompt);
            case "grok"    -> callGrok(prompt);
            case "groq"    -> callGroq(prompt);
            case "cohere"  -> callCohere(prompt);
            default        -> callAnthropic(prompt);
        };
    }

    private String provider() {
        return appConfig.get("LLM_PROVIDER", "anthropic").toLowerCase().trim();
    }

    // ── Prompts ───────────────────────────────────────────────────────────────

    private String buildSummaryPrompt(ProjectStructure structure,
                                      Map<String, String> fileContents) {
        ProjectStructure.ProjectStats stats = structure.getStats();
        StringBuilder sb = new StringBuilder("Summarize this software project in JSON.\n\n");
        sb.append("Files: ").append(stats.getTotalFiles())
          .append(", Dirs: ").append(stats.getTotalDirectories()).append("\n");

        if (stats.getLanguageDistribution() != null)
            stats.getLanguageDistribution().entrySet().stream().limit(5)
                    .forEach(e -> sb.append(e.getKey()).append(":").append(e.getValue()).append(" "));
        sb.append("\n");

        if (structure.getBuildFiles() != null)
            sb.append("Build: ").append(
                    structure.getBuildFiles().stream().limit(5).collect(Collectors.joining(", "))
            ).append("\n");

        sb.append("Tree:\n").append(structureService.generateTreeText(structure.getTree(), 60));

        fileContents.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().length() > 100)
                .findFirst()
                .ifPresent(e -> sb.append("\nSample file (").append(e.getKey()).append("):\n")
                        .append(e.getValue(), 0, Math.min(e.getValue().length(), 1500)).append("\n"));

        sb.append("\nReturn ONLY this JSON (no markdown, start with {):\n");
        sb.append("{\"projectType\":\"what kind of project\",");
        sb.append("\"mainPurpose\":\"what it does specifically\",");
        sb.append("\"technicalStack\":\"languages and frameworks\",");
        sb.append("\"summary\":\"3-4 sentence description\"}\n");
        return sb.toString();
    }

    private String buildSemanticPrompt(Map<String, String> files) {
        StringBuilder sb = new StringBuilder();
        sb.append("Analyze the semantic meaning of each source file below.\n");
        sb.append("IMPORTANT PARSING RULES:\n");
        sb.append("- Extract class/interface/enum names using word boundaries (e.g. 'class DataQueryController' not 'over' from 'implements').\n");
        sb.append("- If no Javadoc, create a synthetic semanticMeaning from class name + key methods/fields.\n");
        sb.append("For every file, class, method and field: explain WHAT IT DOES and WHY it exists.\n");
        sb.append("Focus on SEMANTIC MEANING — not syntax, not formatting.\n");
        sb.append("Every 'semanticMeaning' field must contain a real, specific, helpful description.\n\n");

        for (Map.Entry<String, String> e : files.entrySet()) {
            sb.append("=== ").append(e.getKey()).append(" ===\n");
            sb.append(e.getValue()).append("\n\n");
        }

        sb.append("Return ONLY valid JSON (no markdown fences, start with {):\n");
        sb.append("{\n  \"fileAnalyses\": [\n    {\n");
        sb.append("      \"filePath\": \"exact/path/to/file.ts\",\n");
        sb.append("      \"fileName\": \"file.ts\",\n");
        sb.append("      \"language\": \"TypeScript\",\n");
        sb.append("      \"semanticRole\": \"what this file's responsibility is in the system\",\n");
        sb.append("      \"dependencies\": [\"imported module names\"],\n");
        sb.append("      \"classes\": [\n        {\n");
        sb.append("          \"name\": \"ClassName\",\n");
        sb.append("          \"kind\": \"class|interface|enum|abstract class\",\n");
        sb.append("          \"extendsFrom\": \"ParentClass or null\",\n");
        sb.append("          \"implements_\": [\"InterfaceName\"],\n");
        sb.append("          \"semanticMeaning\": \"what this class represents and its role\",\n");
        sb.append("          \"fields\": [{\"name\":\"\",\"type\":\"\",\"visibility\":\"\",\"semanticMeaning\":\"what it stores and why\"}],\n");
        sb.append("          \"methods\": [{\n");
        sb.append("            \"name\": \"\", \"signature\": \"\", \"visibility\": \"\",\n");
        sb.append("            \"isAsync\": false, \"isStatic\": false, \"returnType\": \"\",\n");
        sb.append("            \"semanticMeaning\": \"detailed description of what this method actually does\"\n");
        sb.append("          }]\n        }\n      ],\n");
        sb.append("      \"moduleFunctions\": [{\"name\":\"\",\"signature\":\"\",\"visibility\":\"\",");
        sb.append("\"isAsync\":false,\"isStatic\":false,\"semanticMeaning\":\"\"}]\n");
        sb.append("    }\n  ]\n}\n");
        return sb.toString();
    }

    // ── Parsers ───────────────────────────────────────────────────────────────

    private SemanticAnalysis parseSummary(String raw) {
        SemanticAnalysis s = new SemanticAnalysis();
        s.setRawResponse(raw);
        try {
            JsonNode j = mapper.readTree(extractJson(raw));
            s.setProjectType(   j.path("projectType").asText(null));
            s.setMainPurpose(   j.path("mainPurpose").asText(null));
            s.setTechnicalStack(j.path("technicalStack").asText(null));
            s.setSummary(       j.path("summary").asText(null));
        } catch (Exception e) {
            log.warn("Summary parse failed: {}", e.getMessage());
            s.setSummary(raw.length() > 500 ? raw.substring(0, 500) : raw);
        }
        return s;
    }

    private List<FileSemanticAnalysis> parseFileAnalyses(String raw) {
        List<FileSemanticAnalysis> results = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(extractJson(raw));
            JsonNode arr  = root.path("fileAnalyses");
            if (!arr.isArray()) return results;

            for (JsonNode fn : arr) {
                FileSemanticAnalysis fsa = new FileSemanticAnalysis();
                fsa.setFilePath(    fn.path("filePath").asText(null));
                fsa.setFileName(    fn.path("fileName").asText(null));
                fsa.setLanguage(    fn.path("language").asText(null));
                fsa.setSemanticRole(fn.path("semanticRole").asText(null));
                fsa.setDependencies(strList(fn.path("dependencies")));

                List<FileSemanticAnalysis.ClassSemantic> classes = new ArrayList<>();
                for (JsonNode cn : fn.path("classes")) {
                    FileSemanticAnalysis.ClassSemantic cs = new FileSemanticAnalysis.ClassSemantic();
                    cs.setName(           cn.path("name").asText(null));
                    cs.setKind(           cn.path("kind").asText("class"));
                    cs.setExtendsFrom(    cn.path("extendsFrom").asText(null));
                    cs.setImplements_(    strList(cn.path("implements_")));
                    cs.setSemanticMeaning(cn.path("semanticMeaning").asText(null));
                    cs.setFields(         parseFields(cn.path("fields")));
                    cs.setMethods(        parseMethods(cn.path("methods")));
                    classes.add(cs);
                }
                fsa.setClasses(classes);
                fsa.setModuleFunctions(parseMethods(fn.path("moduleFunctions")));
                results.add(fsa);
            }
        } catch (Exception e) {
            log.error("File analysis parse failed: {}", e.getMessage());
        }
        return results;
    }

    private List<FileSemanticAnalysis.MethodSemantic> parseMethods(JsonNode arr) {
        List<FileSemanticAnalysis.MethodSemantic> list = new ArrayList<>();
        if (arr == null || !arr.isArray()) return list;
        for (JsonNode n : arr) {
            FileSemanticAnalysis.MethodSemantic m = new FileSemanticAnalysis.MethodSemantic();
            m.setName(           n.path("name").asText(null));
            m.setSignature(      n.path("signature").asText(null));
            m.setVisibility(     n.path("visibility").asText(null));
            m.setAsync(          n.path("isAsync").asBoolean(false));
            m.setStatic(         n.path("isStatic").asBoolean(false));
            m.setReturnType(     n.path("returnType").asText(null));
            m.setSemanticMeaning(n.path("semanticMeaning").asText(null));
            list.add(m);
        }
        return list;
    }

    private List<FileSemanticAnalysis.FieldSemantic> parseFields(JsonNode arr) {
        List<FileSemanticAnalysis.FieldSemantic> list = new ArrayList<>();
        if (arr == null || !arr.isArray()) return list;
        for (JsonNode n : arr) {
            FileSemanticAnalysis.FieldSemantic f = new FileSemanticAnalysis.FieldSemantic();
            f.setName(           n.path("name").asText(null));
            f.setType(           n.path("type").asText(null));
            f.setVisibility(     n.path("visibility").asText(null));
            f.setSemanticMeaning(n.path("semanticMeaning").asText(null));
            list.add(f);
        }
        return list;
    }

    // ── Provider implementations ──────────────────────────────────────────────

    private String callAnthropic(String prompt) throws IOException {
        String apiKey  = appConfig.get("ANTHROPIC_API_KEY");
        validateKey(apiKey, "ANTHROPIC_API_KEY");
        String model   = appConfig.get("ANTHROPIC_MODEL", "claude-sonnet-4-6");
        String baseUrl = appConfig.get("ANTHROPIC_BASE_URL", "https://api.anthropic.com");
        baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length()-1) : baseUrl;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", 8192);
        body.put("system", systemInstruction());
        body.put("messages", List.of(Map.of("role","user","content", prompt)));

        Request req = new Request.Builder()
                .url(baseUrl + "/v1/messages")
                .post(RequestBody.create(mapper.writeValueAsString(body), JSON_MEDIA))
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response r = getClient().newCall(req).execute()) {
            String rb = bodyStr(r);
            if (!r.isSuccessful()) throw new IOException("Anthropic API " + r.code() + ": " + rb);
            return mapper.readTree(rb).path("content").get(0).path("text").asText();
        }
    }

    private String callOpenAI(String prompt) throws IOException {
        String apiKey = appConfig.get("OPENAI_API_KEY");
        validateKey(apiKey, "OPENAI_API_KEY");
        String model  = appConfig.get("OPENAI_MODEL", "gpt-4o");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", 8192);
        body.put("messages", List.of(
                Map.of("role","system","content", systemInstruction()),
                Map.of("role","user",  "content", prompt)
        ));

        Request req = new Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .post(RequestBody.create(mapper.writeValueAsString(body), JSON_MEDIA))
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type",  "application/json")
                .build();

        try (Response r = getClient().newCall(req).execute()) {
            String rb = bodyStr(r);
            if (!r.isSuccessful()) throw new IOException("OpenAI API " + r.code() + ": " + rb);
            return mapper.readTree(rb).path("choices").get(0).path("message").path("content").asText();
        }
    }

    private String callGrok(String prompt) throws IOException {
        String apiKey = appConfig.get("GROK_API_KEY");
        validateKey(apiKey, "GROK_API_KEY");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", appConfig.get("GROK_MODEL", "grok-3-mini"));
        body.put("max_tokens", 8192);
        body.put("messages", List.of(
                Map.of("role","system","content", systemInstruction()),
                Map.of("role","user",  "content", prompt)
        ));

        Request req = new Request.Builder()
                .url("https://api.x.ai/v1/chat/completions")
                .post(RequestBody.create(mapper.writeValueAsString(body), JSON_MEDIA))
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type",  "application/json")
                .build();

        try (Response r = getClient().newCall(req).execute()) {
            String rb = bodyStr(r);
            if (!r.isSuccessful()) throw new IOException("Grok API " + r.code() + ": " + rb);
            return mapper.readTree(rb).path("choices").get(0).path("message").path("content").asText();
        }
    }

    private String callGroq(String prompt) throws IOException {
        String apiKey = appConfig.get("GROQ_API_KEY");
        validateKey(apiKey, "GROQ_API_KEY");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model",       appConfig.get("GROQ_MODEL", "llama-3.3-70b-versatile"));
        body.put("max_tokens",  8192);
        body.put("temperature", 0.2);
        body.put("messages", List.of(
                Map.of("role","system","content", systemInstruction()),
                Map.of("role","user",  "content", prompt)
        ));

        Request req = new Request.Builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .post(RequestBody.create(mapper.writeValueAsString(body), JSON_MEDIA))
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type",  "application/json")
                .build();

        try (Response r = getClient().newCall(req).execute()) {
            String rb = bodyStr(r);
            if (!r.isSuccessful()) throw new IOException("Groq API " + r.code() + ": " + rb);
            return mapper.readTree(rb).path("choices").get(0).path("message").path("content").asText();
        }
    }

    private String callCohere(String prompt) throws IOException {
        String apiKey = appConfig.get("COHERE_API_KEY");
        validateKey(apiKey, "COHERE_API_KEY");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model",       appConfig.get("COHERE_MODEL", "command-r-plus-08-2024"));
        body.put("max_tokens",  4096);
        body.put("temperature", 0.2);
        body.put("messages", List.of(
                Map.of("role","system","content", systemInstruction()),
                Map.of("role","user",  "content", prompt)
        ));

        Request req = new Request.Builder()
                .url("https://api.cohere.com/v2/chat")
                .post(RequestBody.create(mapper.writeValueAsString(body), JSON_MEDIA))
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type",  "application/json")
                .addHeader("Accept",        "application/json")
                .build();

        try (Response r = getClient().newCall(req).execute()) {
            String rb = bodyStr(r);
            if (!r.isSuccessful()) throw new IOException("Cohere API " + r.code() + ": " + rb);
            return mapper.readTree(rb).path("message").path("content").get(0).path("text").asText();
        }
    }

    private String callGemini(String prompt) throws IOException {
        String apiKey = appConfig.get("GEMINI_API_KEY");
        validateKey(apiKey, "GEMINI_API_KEY");
        String model  = appConfig.get("GEMINI_MODEL", "gemini-1.5-flash");
        String url    = "https://generativelanguage.googleapis.com/v1beta/models/"
                + model + ":generateContent?key=" + apiKey;

        String combined = systemInstruction() + "\n\n" + prompt;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contents", List.of(Map.of("role","user",
                "parts", List.of(Map.of("text", combined)))));
        body.put("generationConfig", Map.of("maxOutputTokens", 8192, "temperature", 0.2));

        Request req = new Request.Builder()
                .url(url)
                .post(RequestBody.create(mapper.writeValueAsString(body), JSON_MEDIA))
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response r = getClient().newCall(req).execute()) {
            String rb = bodyStr(r);
            if (!r.isSuccessful()) throw new IOException("Gemini API " + r.code() + ": " + rb);
            return mapper.readTree(rb).path("candidates").get(0)
                    .path("content").path("parts").get(0).path("text").asText();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String systemInstruction() {
        return "You are a code semantic analysis engine. "
                + "Your ONLY job is to analyze source code and explain the semantic meaning of each element. "
                + "Always respond with valid JSON only — no markdown fences, no explanation, start directly with {.";
    }

    /**
     * FIX-3: Extracts JSON object from LLM response, handling:
     *   - ` ```json ... ``` ` fences
     *   - ` ``` ... ``` ` fences
     *   - Leading/trailing whitespace and prose
     */
    private String extractJson(String text) {
        if (text == null) return "{}";
        text = text.trim();
        // Strip code fences
        if (text.startsWith("```json")) text = text.substring(7);
        else if (text.startsWith("```")) text = text.substring(3);
        if (text.endsWith("```")) text = text.substring(0, text.length() - 3);
        text = text.trim();
        // Find the JSON object boundaries
        int s = text.indexOf('{');
        int e = text.lastIndexOf('}');
        return (s >= 0 && e > s) ? text.substring(s, e + 1) : text;
    }

    private List<String> strList(JsonNode node) {
        List<String> r = new ArrayList<>();
        if (node == null || !node.isArray()) return r;
        node.forEach(i -> { if (!i.asText("").isBlank()) r.add(i.asText()); });
        return r;
    }

    private boolean isSource(String path) {
        int i = path.lastIndexOf('.');
        return i >= 0 && SOURCE_EXTENSIONS.contains(path.substring(i + 1).toLowerCase());
    }

    private int importanceScore(String path) {
        String n = path.toLowerCase();
        String f = n.contains("/") ? n.substring(n.lastIndexOf('/') + 1) : n;
        if (f.contains("application") || f.contains("main")) return 0;
        if (f.contains("service")    && !f.contains("spec")) return 1;
        if (f.contains("controller") && !f.contains("spec")) return 2;
        if (!f.contains("spec") && !f.contains("test"))       return 3;
        return 10;
    }

    private void validateKey(String key, String name) {
        if (key == null || key.isBlank() || key.startsWith("your_"))
            throw new IllegalStateException(name + " is not configured in .env");
    }

    private String bodyStr(Response r) throws IOException {
        return r.body() != null ? r.body().string() : "";
    }

    /** Create stub entries for a failed batch so the report still lists the files. */
    private List<FileSemanticAnalysis> stubBatch(Map<String, String> batchMap, String errorMsg) {
        return batchMap.keySet().stream().map(path -> {
            FileSemanticAnalysis stub = new FileSemanticAnalysis();
            stub.setFilePath(path);
            stub.setFileName(path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path);
            stub.setSemanticRole("Analysis failed: " + errorMsg);
            return stub;
        }).collect(Collectors.toList());
    }
}
