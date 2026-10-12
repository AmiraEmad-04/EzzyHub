package com.codeanalyzer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Semantic meaning of a single source file.
 * This is the primary output of the analysis — consumed by downstream systems
 * to compare code semantics against requirements documents.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FileSemanticAnalysis {

    private String filePath;
    private String fileName;
    private String language;

    /** What this file's responsibility is in the system */
    private String semanticRole;

    /** External modules/packages this file depends on */
    private List<String> dependencies;

    /** All classes, interfaces, enums defined in this file */
    private List<ClassSemantic> classes;

    /** Functions defined at module level (outside any class) */
    private List<MethodSemantic> moduleFunctions;

    // ── Inner: Class / Interface / Enum ─────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ClassSemantic {
        private String name;
        private String kind;          // class | interface | enum | abstract class | type
        private String extendsFrom;
        private List<String> implements_;
        /** What this class represents and its role in the system */
        private String semanticMeaning;
        private List<FieldSemantic>  fields;
        private List<MethodSemantic> methods;

        public String getName()          { return name; }
        public void setName(String v)    { this.name = v; }
        public String getKind()          { return kind; }
        public void setKind(String v)    { this.kind = v; }
        public String getExtendsFrom()   { return extendsFrom; }
        public void setExtendsFrom(String v) { this.extendsFrom = v; }
        public List<String> getImplements_() { return implements_; }
        public void setImplements_(List<String> v) { this.implements_ = v; }
        public String getSemanticMeaning()   { return semanticMeaning; }
        public void setSemanticMeaning(String v) { this.semanticMeaning = v; }
        public List<FieldSemantic> getFields()   { return fields; }
        public void setFields(List<FieldSemantic> v) { this.fields = v; }
        public List<MethodSemantic> getMethods() { return methods; }
        public void setMethods(List<MethodSemantic> v) { this.methods = v; }
    }

    // ── Inner: Method / Function ─────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class MethodSemantic {
        private String name;
        private String signature;
        private String visibility;    // public | private | protected | export
        private boolean isAsync;
        private boolean isStatic;
        private String returnType;
        /** Detailed description of WHAT this method does semantically */
        private String semanticMeaning;

        public String getName()          { return name; }
        public void setName(String v)    { this.name = v; }
        public String getSignature()     { return signature; }
        public void setSignature(String v) { this.signature = v; }
        public String getVisibility()    { return visibility; }
        public void setVisibility(String v){ this.visibility = v; }
        public boolean isAsync()         { return isAsync; }
        public void setAsync(boolean v)  { this.isAsync = v; }
        public boolean isStatic()        { return isStatic; }
        public void setStatic(boolean v) { this.isStatic = v; }
        public String getReturnType()    { return returnType; }
        public void setReturnType(String v){ this.returnType = v; }
        public String getSemanticMeaning()  { return semanticMeaning; }
        public void setSemanticMeaning(String v) { this.semanticMeaning = v; }
    }

    // ── Inner: Field / Property ──────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FieldSemantic {
        private String name;
        private String type;
        private String visibility;
        /** What this field stores and why */
        private String semanticMeaning;

        public String getName()          { return name; }
        public void setName(String v)    { this.name = v; }
        public String getType()          { return type; }
        public void setType(String v)    { this.type = v; }
        public String getVisibility()    { return visibility; }
        public void setVisibility(String v){ this.visibility = v; }
        public String getSemanticMeaning()  { return semanticMeaning; }
        public void setSemanticMeaning(String v) { this.semanticMeaning = v; }
    }

    // ── Getters / Setters ────────────────────────────────────────────────

    public String getFilePath()      { return filePath; }
    public void setFilePath(String v){ this.filePath = v; }
    public String getFileName()      { return fileName; }
    public void setFileName(String v){ this.fileName = v; }
    public String getLanguage()      { return language; }
    public void setLanguage(String v){ this.language = v; }
    public String getSemanticRole()  { return semanticRole; }
    public void setSemanticRole(String v){ this.semanticRole = v; }
    public List<String> getDependencies() { return dependencies; }
    public void setDependencies(List<String> v){ this.dependencies = v; }
    public List<ClassSemantic> getClasses(){ return classes; }
    public void setClasses(List<ClassSemantic> v){ this.classes = v; }
    public List<MethodSemantic> getModuleFunctions(){ return moduleFunctions; }
    public void setModuleFunctions(List<MethodSemantic> v){ this.moduleFunctions = v; }
}