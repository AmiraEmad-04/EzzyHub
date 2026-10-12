package com.example.ezzyhubai.security;

import com.example.ezzyhubai.exception.GenerationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.zip.ZipEntry;

@Service
public class ZipSecurityService {

    @Value("${zip.max-entries:20000}")
    private int maxZipEntries;

    public void validateZipFileName(String fileName) {

        if (fileName == null ||
                !fileName.toLowerCase().endsWith(".zip")) {

            throw new GenerationException(
                    "Only ZIP files are allowed.");
        }
    }

    /**
     * Bails out on archives crafted with an excessive entry count (e.g.
     * zip-bomb style archives) before the read loop finishes processing them
     * all. Mirrors Code-Analyzer's MAX_ZIP_ENTRIES guard.
     */
    public void validateEntryCount(int entryCount) {

        if (entryCount > maxZipEntries) {

            throw new GenerationException(
                    "ZIP contains more than " + maxZipEntries + " entries.");
        }
    }

    public void validateEntry(ZipEntry entry) {

        String name = entry.getName();

        if (name.contains("..")
                || name.startsWith("/")
                || name.startsWith("\\")
                || name.contains(":")) {

            throw new GenerationException(
                    "Invalid ZIP entry detected.");
        }
    }

    public void validateGeneratedFileName(
            String fileName) {

        if (fileName == null
                || fileName.contains("..")
                || fileName.startsWith("/")
                || fileName.startsWith("\\")
                || fileName.contains(":")) {

            throw new GenerationException(
                    "Invalid file name.");
        }
    }
}