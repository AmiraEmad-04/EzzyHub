package com.codeanalyzer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class FileNode {

    private String name;
    private String path;
    private String type; // "file" or "directory"
    private Long size;
    private String language;
    private String extension;
    private List<FileNode> children;

    public FileNode() {}

    public FileNode(String name, String path, String type) {
        this.name = name;
        this.path = path;
        this.type = type;
        if ("directory".equals(type)) {
            this.children = new ArrayList<>();
        }
    }

    // Getters and setters
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Long getSize() { return size; }
    public void setSize(Long size) { this.size = size; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public String getExtension() { return extension; }
    public void setExtension(String extension) { this.extension = extension; }

    public List<FileNode> getChildren() { return children; }
    public void setChildren(List<FileNode> children) { this.children = children; }

    public void addChild(FileNode child) {
        if (this.children == null) this.children = new ArrayList<>();
        this.children.add(child);
    }
}
