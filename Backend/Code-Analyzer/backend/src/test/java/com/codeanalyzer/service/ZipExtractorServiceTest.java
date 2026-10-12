package com.codeanalyzer.service;

import com.codeanalyzer.config.AppConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ZipExtractorService}: extraction, nested paths, skipping ignored
 * dirs/binaries, per-file char limit, entry-count guard, zip-slip protection, and
 * rejection of corrupted archives.
 */
class ZipExtractorServiceTest {

    @TempDir
    Path tmp;

    private AppConfig appConfig;
    private ZipExtractorService service;

    @BeforeEach
    void setUp() {
        appConfig = mock(AppConfig.class);
        lenient().when(appConfig.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appConfig.getLong(anyString(), anyLong())).thenAnswer(i -> i.getArgument(1));
        service = new ZipExtractorService();
        ReflectionTestUtils.setField(service, "appConfig", appConfig);
    }

    private Path writeZip(Map<String, byte[]> entries) throws IOException {
        Path zip = tmp.resolve("project-" + System.nanoTime() + ".zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                if (e.getValue() != null) zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return zip;
    }

    @Test
    void extractsJavaFiles_withContent_andPreservesNestedPaths() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("proj/src/main/java/com/app/Main.java", "class Main {}".getBytes());
        entries.put("proj/src/main/java/com/app/UserService.java", "class UserService {}".getBytes());
        entries.put("proj/README.md", "# Title".getBytes());

        Map<String, String> contents = service.extractContents(writeZip(entries));

        assertThat(contents).containsKeys(
                "proj/src/main/java/com/app/Main.java",
                "proj/src/main/java/com/app/UserService.java");
        assertThat(contents.get("proj/src/main/java/com/app/Main.java")).isEqualTo("class Main {}");
    }

    @Test
    void skipsIgnoredDirectories_likeNodeModulesAndGit() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("proj/App.java", "class App {}".getBytes());
        entries.put("proj/node_modules/lib/index.js", "module.exports={}".getBytes());
        entries.put("proj/.git/config", "[core]".getBytes());

        Map<String, String> contents = service.extractContents(writeZip(entries));

        assertThat(contents).containsKey("proj/App.java");
        assertThat(contents.keySet()).noneMatch(k -> k.contains("node_modules"));
        assertThat(contents.keySet()).noneMatch(k -> k.contains(".git"));
    }

    @Test
    void registersBinaryFile_butDoesNotReadItsContent() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("proj/logo.png", new byte[]{(byte) 0x89, 'P', 'N', 'G', 0, 1, 2, 3});
        entries.put("proj/App.java", "class App {}".getBytes());

        Map<String, String> contents = service.extractContents(writeZip(entries));

        assertThat(contents).containsKey("proj/logo.png");
        assertThat(contents.get("proj/logo.png")).isNull();
        assertThat(contents.get("proj/App.java")).isEqualTo("class App {}");
    }

    @Test
    void enforcesPerFileCharLimit_byTruncating() throws IOException {
        when(appConfig.getInt(eq("MAX_CHARS_PER_FILE"), anyInt())).thenReturn(10);

        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("proj/Big.java", "0123456789ABCDEFGHIJ".getBytes());

        Map<String, String> contents = service.extractContents(writeZip(entries));

        assertThat(contents.get("proj/Big.java")).startsWith("0123456789").endsWith("[truncated]");
    }

    @Test
    void nullByteContent_isTreatedAsBinary_notReadAsText() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("proj/data.txt", new byte[]{'a', 'b', 0, 'c', 'd'});

        Map<String, String> contents = service.extractContents(writeZip(entries));

        assertThat(contents).containsKey("proj/data.txt");
        assertThat(contents.get("proj/data.txt")).isNull();
    }

    @Test
    void rejectsArchiveWithTooManyEntries() throws IOException {
        when(appConfig.getInt(eq("MAX_ZIP_ENTRIES"), anyInt())).thenReturn(3);

        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < 6; i++) entries.put("proj/File" + i + ".java", ("class F" + i + " {}").getBytes());

        assertThatThrownBy(() -> service.extractContents(writeZip(entries)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than 3 entries");
    }

    @Test
    void skipsZipSlipEntries_pathTraversalAndAbsolutePaths() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("proj/App.java", "class App {}".getBytes());
        entries.put("proj/../../evil.java", "class Evil {}".getBytes());   // traversal
        Map<String, String> contents = service.extractContents(writeZip(entries));

        assertThat(contents).containsKey("proj/App.java");
        // The traversal entry must not appear as a usable extracted path.
        assertThat(contents.keySet()).noneMatch(k -> k.contains(".."));
    }

    @Test
    void corruptedOrNonZip_throwsIOException() throws IOException {
        Path notZip = tmp.resolve("broken.zip");
        Files.write(notZip, "this is definitely not a zip archive".getBytes());

        assertThatThrownBy(() -> service.extractContents(notZip))
                .isInstanceOf(IOException.class);
    }

    @Test
    void listEntries_returnsAllNormalizedPaths() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("proj/A.java", "class A {}".getBytes());
        entries.put("proj/sub/B.java", "class B {}".getBytes());

        assertThat(service.listEntries(writeZip(entries)))
                .contains("proj/A.java", "proj/sub/B.java");
    }
}
