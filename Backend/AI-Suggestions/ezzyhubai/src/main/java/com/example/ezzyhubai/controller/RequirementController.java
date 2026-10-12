package com.example.ezzyhubai.controller;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.nio.file.Files;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.zip.ZipInputStream;
import com.example.ezzyhubai.exception.GenerationException;
import com.example.ezzyhubai.repository.*;
import com.example.ezzyhubai.security.AiResponseValidator;
import com.example.ezzyhubai.security.JwtVerifier;
import com.example.ezzyhubai.security.PromptSecurityService;
import com.example.ezzyhubai.security.SensitiveDataValidator;
import com.example.ezzyhubai.security.ZipSecurityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestPart;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import com.example.ezzyhubai.dto.MatchReportDto;
import com.example.ezzyhubai.dto.MatchedFileDto;
import com.example.ezzyhubai.dto.RequirementMatchDto;
import com.example.ezzyhubai.entity.*;
import com.example.ezzyhubai.service.OpenAiService;
import com.example.ezzyhubai.service.PdfReportParser;
import com.example.ezzyhubai.service.RequirementService;
import org.springframework.web.bind.annotation.*;
import com.example.ezzyhubai.service.FileStorageService;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import com.example.ezzyhubai.dto.ChatRequest;
import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import com.example.ezzyhubai.service.FileReaderService;

@RestController
@RequestMapping("/api/requirements")
public class RequirementController {

    private static final Logger log =
            LoggerFactory.getLogger(RequirementController.class);

    private final UploadedFileRepository uploadedFileRepository;
    private final FileStorageService fileStorageService;
    private final FileReaderService fileReaderService;
    private final GeneratedCodeRepository generatedCodeRepository;
    private final RequirementService service;
    private final GeneratedFileRepository generatedFileRepository;
    private final OpenAiService openAiService;
    private final GenerationVersionRepository generationVersionRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PromptSecurityService
            promptSecurityService;

    private final ZipSecurityService
            zipSecurityService;

    private final AiResponseValidator
            aiResponseValidator;
    private final SensitiveDataValidator
            sensitiveDataValidator;
    private final JwtVerifier jwtVerifier;
    @Autowired
    private GenerationBatchRepository
            generationBatchRepository;

    // One OpenAI call per requirement in /from-match; bound how many batches
    // run at once so a burst of requests can't pile up unbounded OpenAI
    // spend/latency. Requests beyond this get an immediate 429 instead of
    // queuing. Mirrors Code-Analyzer's /analyze and report-service's PDF
    // endpoint Semaphore guards.
    private final Semaphore generationSemaphore;
    private final PdfReportParser pdfReportParser;

    public RequirementController(
            RequirementService service,
            UploadedFileRepository uploadedFileRepository,
            FileStorageService fileStorageService,
            FileReaderService fileReaderService,
            OpenAiService openAiService,
            GeneratedCodeRepository generatedCodeRepository,
            GeneratedFileRepository generatedFileRepository,
            GenerationVersionRepository generationVersionRepository,PromptSecurityService promptSecurityService
,        ZipSecurityService zipSecurityService,
        AiResponseValidator aiResponseValidator,SensitiveDataValidator
                    sensitiveDataValidator,
        JwtVerifier jwtVerifier,
        @Value("${ai.max-concurrent-generations:3}") int maxConcurrentGenerations,
        PdfReportParser pdfReportParser){
        this.service = service;
        this.uploadedFileRepository = uploadedFileRepository;
        this.fileStorageService = fileStorageService;
        this.fileReaderService = fileReaderService;
        this.openAiService = openAiService;
        this.generatedCodeRepository = generatedCodeRepository;
        this.generatedFileRepository =
                generatedFileRepository;
        this.generationVersionRepository =
                generationVersionRepository;
        this.promptSecurityService =
                promptSecurityService;

        this.zipSecurityService =
                zipSecurityService;

        this.aiResponseValidator =
                aiResponseValidator;
        this.sensitiveDataValidator =
                sensitiveDataValidator;
        this.jwtVerifier = jwtVerifier;
        this.generationSemaphore =
                new Semaphore(maxConcurrentGenerations);
        this.pdfReportParser = pdfReportParser;
    }




    @GetMapping("/{requirementId}/versions/{versionNumber}/download")
    public ResponseEntity<byte[]> download(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long requirementId,
            @PathVariable Integer versionNumber)
            throws IOException {

        jwtVerifier.extractUserId(authorization);

        GenerationVersion version =
                generationVersionRepository
                        .findByRequirementIdAndVersionNumber(
                                requirementId,
                                versionNumber)
                        .orElseThrow(() ->
                                new GenerationException(
                                        "Version not found."));

        List<GeneratedFile> files =
                generatedFileRepository
                        .findByVersionId(
                                version.getId());

        ByteArrayOutputStream baos =
                new ByteArrayOutputStream();

        ZipOutputStream zipOut =
                new ZipOutputStream(baos);


        Set<String> addedFiles =
                new HashSet<>();

        for (GeneratedFile file : files) {

            if (addedFiles.contains(
                    file.getFilePath())) {
                continue;
            }

            addedFiles.add(
                    file.getFilePath());
            zipSecurityService
                    .validateGeneratedFileName(
                            file.getFileName());
            ZipEntry zipEntry =
                    new ZipEntry(
                            file.getFilePath());

            zipOut.putNextEntry(
                    zipEntry);

            zipOut.write(
                    file.getContent()
                            .getBytes());

            zipOut.closeEntry();
        }
        zipOut.close();
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=project.zip")
                .contentType(
                        MediaType.APPLICATION_OCTET_STREAM)
                .body(
                        baos.toByteArray());
    }

    @GetMapping("/{requirementId}/versions")
    public List<GenerationVersion> getVersions(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long requirementId) {

        jwtVerifier.extractUserId(authorization);

        return generationVersionRepository
                .findByRequirementIdOrderByVersionNumberDesc(
                        requirementId);
    }

    @GetMapping("/{requirementId}/versions/by-id/{versionId}")
    public GenerationVersion getVersion(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long requirementId,
            @PathVariable Long versionId) {

        jwtVerifier.extractUserId(authorization);

        GenerationVersion version =
                generationVersionRepository
                        .findById(versionId)
                        .orElseThrow(() ->
                                new GenerationException(
                                        "Version not found."));
        if (!version.getRequirement()
                .getId()
                .equals(requirementId)) {

            throw new GenerationException(
                    "Version does not belong to requirement.");
        }

        return version;
    }

     @GetMapping("/{requirementId}/versions/{versionNumber}/files")
    public List<GeneratedFile> getFiles(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long requirementId,
            @PathVariable Integer versionNumber) {

        jwtVerifier.extractUserId(authorization);

        GenerationVersion version =
                generationVersionRepository
                        .findByRequirementIdAndVersionNumber(
                                requirementId,
                                versionNumber)
                        .orElseThrow(() ->
                                new GenerationException(
                                        "Version not found."));

        return generatedFileRepository
                .findByVersionId(version.getId());
    }


    @PostMapping("/{requirementId}/versions/{versionNumber}/chat")
    public GenerationVersion chat(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long requirementId,
            @PathVariable Integer versionNumber,
            @RequestBody ChatRequest request)
            throws IOException {

        jwtVerifier.extractUserId(authorization);

        GenerationVersion oldVersion =
                generationVersionRepository
                        .findByRequirementIdAndVersionNumber(
                                requirementId,
                                versionNumber)
                        .orElseThrow();

        if (!oldVersion.getRequirement()
                .getId()
                .equals(requirementId)) {

            throw new GenerationException(
                    "Version does not belong to requirement");
        }
        List<GeneratedFile> files =
                generatedFileRepository
                        .findByVersionId(
                                oldVersion.getId());

        StringJoiner prompt =
                new StringJoiner("\n");

        prompt.add("""
You are a senior Spring Boot developer.

Modify the existing project according to the user request.

Rules:
- Keep existing code.
- Change only what is necessary.
- Return ONLY modified files.
""");
        promptSecurityService.validate(
                request.getMessage());
        sensitiveDataValidator.validate(
                request.getMessage());
        prompt.add("User Request:");
        prompt.add(request.getMessage());

        for (GeneratedFile file : files) {
            prompt.add("\n--- FILE: "
                    + file.getFileName()
                    + " ---\n");

            prompt.add(file.getContent());
        }
        promptSecurityService.validate(
                prompt.toString());

        String aiResponse;

        try {
            aiResponse =
                    openAiService.generate(
                            prompt.toString());
        }
        catch (Exception e) {
            throw new GenerationException(
                    "Failed to generate code using AI.",
                    e);
        }
        aiResponseValidator.validate(
                aiResponse);
        sensitiveDataValidator.validate(
                aiResponse);
        GenerationVersion newVersion =
                new GenerationVersion();

        newVersion.setRequirement(
                oldVersion.getRequirement());

        newVersion.setParentVersion(
                oldVersion);

        newVersion.setVersionNumber(
                oldVersion.getVersionNumber() + 1);

        newVersion.setUserPrompt(
                request.getMessage());

        newVersion =
                generationVersionRepository
                        .save(newVersion);

        String[] parts =
                aiResponse.split("--- FILE: ");

        for (String part : parts) {

            if (part.isBlank()) {
                continue;
            }

            int end =
                    part.indexOf(" ---");

            if (end == -1) {
                continue;
            }

            String fileName =
                    part.substring(0, end)
                            .trim();

            String content =
                    part.substring(end + 4)
                            .trim();

            GeneratedFile generatedFile =
                    new GeneratedFile();
            zipSecurityService
                    .validateGeneratedFileName(
                            fileName);

            generatedFile.setFileName(
                    fileName);

            generatedFile.setContent(
                    content);

            generatedFile.setVersion(
                    newVersion);

            generatedFileRepository
                    .save(generatedFile);
        }

        return newVersion;
    }

    @PostMapping(
            value = "/from-match",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<?> generateFromMatch(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestPart("report") MultipartFile reportFile,
            @RequestPart("projectZip") MultipartFile projectZip)
            throws Exception {

        jwtVerifier.extractUserId(authorization);

        if (!generationSemaphore.tryAcquire()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of(
                            "error",
                            "Too many concurrent generation requests. Please retry shortly."));
        }

        try {

        MatchReportDto report;

        String contentType = reportFile.getContentType();
        String originalFilename = reportFile.getOriginalFilename();
        boolean isPdf = (contentType != null && contentType.equalsIgnoreCase("application/pdf"))
                || (originalFilename != null && originalFilename.toLowerCase().endsWith(".pdf"));

        if (isPdf) {
            report = pdfReportParser.parse(reportFile.getInputStream());

            for (RequirementMatchDto match : report.getRequirements()) {
                promptSecurityService.validate(match.getRequirementText());
                sensitiveDataValidator.validate(match.getRequirementText());
            }
        } else {
            String reportJson =
                    new String(
                            reportFile.getBytes(),
                            StandardCharsets.UTF_8);
            promptSecurityService.validate(
                    reportJson);
            sensitiveDataValidator.validate(
                    reportJson);
            report =
                    objectMapper.readValue(
                            reportJson,
                            MatchReportDto.class);
        }
        if (report.getRequirements() == null
                || report.getRequirements().isEmpty()) {

            throw new GenerationException(
                    "No requirements found in report.");
        }
        if (projectZip.isEmpty()) {
            throw new GenerationException(
                    "Uploaded ZIP is empty.");
        }
        zipSecurityService.validateZipFileName(
                projectZip.getOriginalFilename());
        Path tmp =
                saveToTempFile(projectZip);
        Map<String, String> contents =
                extractContents(tmp);



        List<GenerationVersion> versions =
                new ArrayList<>();
        GenerationBatch batch =
                new GenerationBatch();

        batch =
                generationBatchRepository
                        .save(batch);
        try {

            for (RequirementMatchDto match :
                    report.getRequirements()) {

                if (!"PARTIAL".equals(match.getStatus())
                        &&
                        !"NOT_IMPLEMENTED".equals(
                                match.getStatus()) &&
                !"MISSING".equals(match.getStatus())){
                    continue;
                }

                /*
                 * Create Requirement
                 */
                Requirement requirement =
                        new Requirement();

                requirement.setName(
                        match.getReqId());

                requirement.setDescription(
                        match.getRequirementText());

                requirement.setBusinessLogic(
                        match.getRequirementText());
                String status =
                        match.getStatus();

                if ("MISSING".equals(status)) {
                    status = "NOT_IMPLEMENTED";
                }

                requirement.setType(
                        RequirementType.valueOf(status));


                Optional<Requirement> existing =
                        service.findByName(
                                match.getReqId());

                if (existing.isPresent()) {
                    requirement =
                            existing.get();
                }
                else {
                    requirement =
                            service.create(
                                    requirement);
                }

                /*
                 * Create Version
                 */
                GenerationVersion version =
                        new GenerationVersion();

                version.setRequirement(
                        requirement);

                version.setRequirement(
                        requirement);
                version.setBatch(
                        batch);
                int nextVersion =
                        generationVersionRepository
                                .findTopByRequirementIdOrderByVersionNumberDesc(
                                        requirement.getId())
                                .map(v -> v.getVersionNumber() + 1)
                                .orElse(1);

                version.setVersionNumber(
                        nextVersion);

                version.setUserPrompt(
                        "Generated from Match Report");
                version.setRequirementStatus(
                        status);

                version.setSimilarityScore(
                        match.getScore());

                version =
                        generationVersionRepository
                                .save(version);

                /*
                 * Build Prompt
                 */
                StringJoiner prompt =
                        new StringJoiner("\n");
                promptSecurityService.validate(
                        match.getRequirementText());

                prompt.add("""
You are a senior Spring Boot developer working on an EXISTING Spring Boot project.

====================================================
IMPORTANT
====================================================

This is NOT a new project.

The files provided below are REAL files that already exist in the project.

Your task is to implement ONLY ONE requirement.

You MUST analyze the existing files before generating anything.

====================================================
FORBIDDEN
====================================================

You are NOT allowed to:

- generate an entire new project
- recreate the application from scratch
- generate a new package structure
- regenerate files that do not need changes
- generate boilerplate
- duplicate existing classes
- generate an authentication system unless required
- generate entities, repositories, services, controllers or security classes unless absolutely necessary.

====================================================
GOAL
====================================================

Implement the requirement with the MINIMUM possible changes.

Modify existing files whenever possible.

Create a new file ONLY if the requirement cannot be implemented without it.

====================================================
OUTPUT RULES
====================================================

Return ONLY files whose contents changed because of this requirement.

Do NOT return explanations.
Do NOT return markdown.
Do NOT return ```java blocks.
Do NOT return an entire project.
Do NOT return unchanged files.
Do NOT return duplicate files.

If only one file changed,
return only one file.

If two files changed,
return only two files.

If no changes are required, return exactly:

NO_CHANGES_REQUIRED

If there is not enough information to implement the requirement, return exactly:

INSUFFICIENT_CONTEXT

====================================================
COMMENTS
====================================================

Above every NEW or MODIFIED code block add comments like this:

// =====================================================
// Added for requirement: <REQ_ID>
// Requirement: <Requirement Text>
//
// Changes:
// - Explain exactly what was added or modified.
// =====================================================

====================================================
OUTPUT FORMAT
====================================================

OUTPUT RULES
====================================================

Return ONLY the code that must be added or modified.

DO NOT regenerate entire files.
DO NOT regenerate entire classes.
DO NOT regenerate the entire project.

For every change return EXACTLY:

=== FILE ===
Path: <full file path>
Action: MODIFY or CREATE
Requirement: <Requirement ID>

=== CODE ===
<only the new or modified code>

=== END ===

Examples:

=== FILE ===
Path: src/main/java/com/ezzyhub/controller/UserController.java
Action: MODIFY
Requirement: FR-1.1

=== CODE ===

// =====================================================
// Added for requirement: FR-1.1
// Requirement: User login
// =====================================================

@PostMapping("/login")
public ResponseEntity<?> login() {
    ...
}

=== END ===

If a new file is needed:

=== FILE ===
Path: src/main/java/com/ezzyhub/service/LoginService.java
Action: CREATE
Requirement: FR-1.1

=== CODE ===

@Service
public class LoginService {
   ...
}

=== END ===

If no changes are required return exactly:

NO_CHANGES_REQUIRED

If there is not enough context return exactly:

INSUFFICIENT_CONTEXT
""
Return ONLY changed files.
""");

                prompt.add(
                        "\nRequirement ID:\n"
                                + match.getReqId());

                prompt.add(
                        "\nRequirement Status:\n"
                                + match.getStatus());

                prompt.add(
                        "\nRequirement:\n"
                                + match.getRequirementText());



                prompt.add("""
==============================
RELATED PROJECT FILES
==============================
""");

                /*
                 * Add actual file contents
                 */
                if (match.getMatchedFiles() != null
                        &&
                        !match.getMatchedFiles()
                                .isEmpty()) {
                    prompt.add("""
====================================================
EXISTING PROJECT FILES
====================================================

The following files already exist.

Modify them only if necessary.

Do NOT regenerate them from scratch.
""");
                    for (MatchedFileDto file :
                            match.getMatchedFiles()) {

                        String code = null;

                        for (Map.Entry<String, String> entry :
                                contents.entrySet()) {

                            if (entry.getKey()
                                    .endsWith(
                                            file.getFilePath())) {

                                code =
                                        entry.getValue();

                                break;
                            }
                        }

                        if (code == null) {
                            continue;
                        }

                        prompt.add(
                                "\n--- EXISTING FILE: "
                                        + file.getFilePath()
                                        + " ---\n");

                        prompt.add(code);
                    }
                }
                else {

                    prompt.add("""
No related files were found.

Return exactly:

INSUFFICIENT_CONTEXT

Do not generate code.
""");
                }

                prompt.add("""
====================================================
FINAL CHECK
====================================================

Before answering:

1. Did I regenerate the whole project?
If yes, start over.

2. Did I return a file that did not change?
If yes, remove it.

3. Did I create unnecessary files?
If yes, remove them.

4. Did I return more than five files?
If yes, I am probably rebuilding the project.
Reduce the answer to the minimum required files.

5. Return ONLY changed files.
""");

                log.debug(
                        "Generation prompt for requirement {}: {}",
                        match.getReqId(),
                        prompt);

                /*
                 * Call OpenAI
                 */
                promptSecurityService.validate(
                        prompt.toString());
                String aiResponse;

                try {
                    aiResponse =
                            openAiService.generate(
                                    prompt.toString());
                }
                catch (Exception e) {
                    throw new GenerationException(
                            "Failed to generate code using AI.",
                            e);
                }

                /*
                 * Save raw response
                 */
                GeneratedCode generatedCode =
                        new GeneratedCode();

                generatedCode.setContent(
                        aiResponse);

                generatedCode.setRequirement(
                        requirement);
                generatedCodeRepository.save(
                        generatedCode);
                aiResponseValidator.validate(
                        aiResponse);

                sensitiveDataValidator.validate(
                        aiResponse);
                /*
                 * Nothing to change
                 */
                if (aiResponse.contains(
                        "INSUFFICIENT_CONTEXT")) {

                    version.setUserPrompt(
                            "Insufficient context");

                    generationVersionRepository
                            .save(version);

                    versions.add(version);
                    continue;
                }

                if (aiResponse.contains(
                        "NO_CHANGES_REQUIRED")) {

                    versions.add(version);
                    continue;
                }

                /*
                 * Save generated files
                 */
                Pattern pattern =
                        Pattern.compile(
                                "=== FILE ===\\s*" +
                                        "Path:\\s*(.*?)\\s*" +
                                        "Action:\\s*(.*?)\\s*" +
                                        "Requirement:\\s*(.*?)\\s*" +
                                        "=== CODE ===\\s*" +
                                        "(.*?)\\s*" +
                                        "=== END ===",
                                Pattern.DOTALL);

                Matcher matcher =
                        pattern.matcher(aiResponse);

                boolean found = false;

                while (matcher.find()) {

                    found = true;

                    String filePath =
                            matcher.group(1).trim();

                    String action =
                            matcher.group(2).trim();

                    String requirementId =
                            matcher.group(3).trim();

                    String code =
                            matcher.group(4).trim();

                    zipSecurityService.validateGeneratedFileName(filePath);

                    String fileName =
                            java.nio.file.Paths.get(filePath)
                                    .getFileName()
                                    .toString();

                    zipSecurityService.validateGeneratedFileName(fileName);

                    GeneratedFile generatedFile =
                            new GeneratedFile();

                    generatedFile.setFilePath(
                            filePath);

                    generatedFile.setFileName(
                            fileName);

                    generatedFile.setContent(
                            code);

                    generatedFile.setVersion(
                            version);

                    generatedFileRepository.save(
                            generatedFile);
                }

                if (!found) {
                    throw new GenerationException(
                            "No files returned by AI.");
                }

                versions.add(version);
            }
        }
        finally {
            cleanup(tmp);
        }

        Map<String, Object> response =
                new HashMap<>();

        response.put(
                "batchId",
                batch.getId());

        response.put(
                "versions",
                versions);

        return ResponseEntity.ok(response);

        } finally {
            generationSemaphore.release();
        }
    }


    private Path saveToTempFile(MultipartFile file)
            throws IOException {

        Path temp =
                Files.createTempFile(
                        "project-",
                        ".zip");

        file.transferTo(temp);

        return temp;
    }
    private Map<String, String> extractContents(
            Path zipFile)
            throws IOException {

        Map<String, String> contents =
                new HashMap<>();

        int entryCount = 0;

        try (ZipInputStream zis =
                     new ZipInputStream(
                             Files.newInputStream(zipFile))) {

            ZipEntry entry;

            while ((entry = zis.getNextEntry()) != null) {

                entryCount++;
                zipSecurityService.validateEntryCount(
                        entryCount);

                zipSecurityService.validateEntry(
                        entry);

                if (entry.isDirectory()) {
                    continue;
                }

                String content =
                        new String(
                                zis.readAllBytes());

                contents.put(
                        entry.getName(),
                        content);
            }
        }

        return contents;
    }
    private void cleanup(Path path) {
        try {
            Files.deleteIfExists(path);
        }
        catch (Exception ignored) {
        }
    }
    @GetMapping("/from-match/{batchId}")
    public ResponseEntity<byte[]> downloadBatch(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long batchId)
            throws IOException {

        jwtVerifier.extractUserId(authorization);

        List<GenerationVersion> versions =
                generationVersionRepository
                        .findByBatchId(batchId);

        if (versions.isEmpty()) {
            throw new GenerationException(
                    "Batch not found.");
        }

        ByteArrayOutputStream baos =
                new ByteArrayOutputStream();

        ZipOutputStream zipOut =
                new ZipOutputStream(baos);

        int folderNumber = 1;

        for (GenerationVersion version : versions) {

            String folder =
                    "requirement-" + folderNumber + "/";

            List<GeneratedFile> files =
                    generatedFileRepository
                            .findByVersionId(
                                    version.getId());

            for (GeneratedFile file : files) {

                String path =
                        folder + file.getFileName();

                ZipEntry entry =
                        new ZipEntry(path);

                zipOut.putNextEntry(entry);

                zipOut.write(
                        file.getContent().getBytes());

                zipOut.closeEntry();
            }

            folderNumber++;
        }

        zipOut.close();

        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=generated-project.zip")
                .contentType(
                        MediaType.APPLICATION_OCTET_STREAM)
                .body(baos.toByteArray());
    }
}