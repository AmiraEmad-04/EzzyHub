"""Tests for ast_chunker.chunk_java_source — method/class extraction with correct names."""

from ast_chunker import chunk_java_source

JAVA = """
package com.example.service;

import java.util.List;

public class UserService {

    private String name;

    public UserService(String name) {
        this.name = name;
    }

    public User createUser(String email) {
        return new User(email);
    }

    public void deleteUser(long id) {
        // remove the user
    }
}

interface Repository {
    List<User> findAll();
}
"""


def _by_type(chunks, t):
    return [c for c in chunks if c["chunk_type"] == t]


def test_extracts_class_chunk_with_correct_name():
    chunks = chunk_java_source(JAVA, "com/example/service/UserService.java")
    classes = _by_type(chunks, "class")
    class_names = {c["class_name"] for c in classes}
    assert "UserService" in class_names
    # file_path is preserved on every chunk
    assert all(c["file_path"] == "com/example/service/UserService.java" for c in chunks)


def test_extracts_methods_with_correct_names():
    chunks = chunk_java_source(JAVA, "UserService.java")
    method_names = {c["method_name"] for c in _by_type(chunks, "method")}
    assert "createUser" in method_names
    assert "deleteUser" in method_names


def test_extracts_constructor():
    chunks = chunk_java_source(JAVA, "UserService.java")
    ctors = _by_type(chunks, "constructor")
    assert ctors, "expected a constructor chunk"
    assert ctors[0]["class_name"] == "UserService"


def test_every_chunk_has_required_fields():
    # The raw chunker emits structural fields; `keywords`/`embedding` are added
    # later by main.py from the NLP pipeline, so they are NOT expected here.
    chunks = chunk_java_source(JAVA, "UserService.java")
    assert chunks
    for c in chunks:
        for field in ("file_path", "class_name", "chunk_type", "chunk_text", "line_start"):
            assert field in c, f"missing {field}"
        assert isinstance(c["chunk_text"], str)


def test_chunk_text_expands_camelcase_for_better_embedding():
    chunks = chunk_java_source(JAVA, "UserService.java")
    create = next(c for c in chunks if c.get("method_name") == "createUser")
    # camelCase createUser -> "create User" so the SBERT tokenizer sees real words
    assert "create User" in create["chunk_text"]


def test_empty_or_non_java_source_returns_list_without_crashing():
    assert isinstance(chunk_java_source("", "Empty.java"), list)
    # Garbage that is not valid Java should fall back to the regex path, not raise.
    assert isinstance(chunk_java_source("%%% not java %%%", "Bad.java"), list)
