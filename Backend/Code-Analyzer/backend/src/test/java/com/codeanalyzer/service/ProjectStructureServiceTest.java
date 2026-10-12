package com.codeanalyzer.service;

import com.codeanalyzer.model.FileNode;
import com.codeanalyzer.model.ProjectStructure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ProjectStructureService}: file-tree building, common-root
 * stripping, language grouping, and entry-point / build / config detection.
 */
class ProjectStructureServiceTest {

    private ProjectStructureService service;

    @BeforeEach
    void setUp() {
        service = new ProjectStructureService();
    }

    private Map<String, String> sampleProject() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("proj/src/main/java/com/app/Main.java", "class Main { public static void main(String[] a){} }");
        m.put("proj/src/main/java/com/app/UserService.java", "class UserService {}");
        m.put("proj/src/main/resources/application.properties", "server.port=8080");
        m.put("proj/pom.xml", "<project/>");
        m.put("proj/README.md", "# Title");
        return m;
    }

    @Test
    void buildsTree_stripsCommonRoot_andGroupsByPackageDirectories() {
        ProjectStructure s = service.buildStructure(sampleProject(), "MyProject");

        FileNode root = s.getTree();
        assertThat(root.getName()).isEqualTo("MyProject");
        assertThat(root.getChildren()).extracting(FileNode::getName)
                .contains("src", "pom.xml", "README.md")
                .doesNotContain("proj");
        assertThat(s.getStats().getTopLevelDirs()).contains("src");

        FileNode app = child(child(child(child(child(root, "src"), "main"), "java"), "com"), "app");
        assertThat(app.getChildren()).extracting(FileNode::getName)
                .contains("Main.java", "UserService.java");
    }

    @Test
    void computesLanguageDistribution() {
        ProjectStructure s = service.buildStructure(sampleProject(), "MyProject");
        assertThat(s.getStats().getLanguageDistribution()).containsEntry("Java", 2);
        assertThat(s.getStats().getTotalFiles()).isEqualTo(5);
    }

    @Test
    void detectsEntryPointsBuildAndConfigFiles() {
        ProjectStructure s = service.buildStructure(sampleProject(), "MyProject");
        assertThat(s.getEntryPoints()).anyMatch(p -> p.endsWith("Main.java"));
        assertThat(s.getBuildFiles()).anyMatch(p -> p.endsWith("pom.xml"));
        assertThat(s.getConfigFiles()).anyMatch(p -> p.endsWith("application.properties"));
    }

    @Test
    void doesNotStripRoot_whenPathsHaveDifferentTopFolders() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("moduleA/A.java", "class A {}");
        m.put("moduleB/B.java", "class B {}");

        ProjectStructure s = service.buildStructure(m, "Multi");

        assertThat(s.getTree().getChildren()).extracting(FileNode::getName)
                .contains("moduleA", "moduleB");
    }

    @Test
    void generateTreeText_producesNonEmptyIndentedTree() {
        ProjectStructure s = service.buildStructure(sampleProject(), "MyProject");
        String tree = service.generateTreeText(s.getTree(), 50);
        assertThat(tree).isNotBlank();
        assertThat(tree).contains("Main.java");
        assertThat(tree).contains("src");
    }

    @Test
    void emptyProject_producesEmptyButValidStructure() {
        ProjectStructure s = service.buildStructure(new LinkedHashMap<>(), "Empty");
        assertThat(s.getStats().getTotalFiles()).isZero();
        assertThat(s.getTree().getName()).isEqualTo("Empty");
    }

    private static FileNode child(FileNode parent, String name) {
        return parent.getChildren().stream()
                .filter(n -> name.equals(n.getName()))
                .findFirst().orElseThrow(() -> new AssertionError("missing child: " + name));
    }
}
