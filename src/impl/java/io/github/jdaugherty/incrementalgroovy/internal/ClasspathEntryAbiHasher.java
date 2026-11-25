/*
 * Copyright the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.jdaugherty.incrementalgroovy.internal;

import io.github.jdaugherty.incrementalgroovy.internal.abi.ApiClassExtractor;
import org.gradle.internal.tools.api.impl.JavaApiMemberWriter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Hashes the ABI of the classes of a classpath entry, with the extractor the incremental compiler compares classes with,
 * so that a compile task runs for a changed dependency exactly when there may be something to recompile.
 * <p>
 * Only classes count. Whatever else is in the entry is not compiled against.
 */
final class ClasspathEntryAbiHasher {
    private static final String CLASS_FILE_EXTENSION = ".class";

    private final ApiClassExtractor apiClassExtractor = ApiClassExtractor.withWriter(JavaApiMemberWriter.adapter()).includePackagePrivateMembers().build();

    String hash(File classpathEntry) {
        MessageDigest digest = newDigest();
        try {
            if (classpathEntry.isDirectory()) {
                hashDirectory(classpathEntry.toPath(), digest);
            } else {
                hashArchive(classpathEntry, digest);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the classes of " + classpathEntry, e);
        }
        return hex(digest.digest());
    }

    private void hashDirectory(Path directory, MessageDigest digest) throws IOException {
        List<Path> classFiles = new ArrayList<>();
        try (Stream<Path> files = Files.walk(directory)) {
            files.filter(file -> file.getFileName().toString().endsWith(CLASS_FILE_EXTENSION) && Files.isRegularFile(file)).forEach(classFiles::add);
        }
        List<String> names = new ArrayList<>();
        for (Path classFile : classFiles) {
            names.add(directory.relativize(classFile).toString().replace(File.separatorChar, '/'));
        }
        Collections.sort(names);
        for (String name : names) {
            hashClass(name, Files.readAllBytes(directory.resolve(name)), digest);
        }
    }

    private void hashArchive(File archive, MessageDigest digest) throws IOException {
        try (ZipFile zip = new ZipFile(archive)) {
            List<String> names = new ArrayList<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getName().endsWith(CLASS_FILE_EXTENSION)) {
                    names.add(entry.getName());
                }
            }
            Collections.sort(names);
            for (String name : names) {
                try (InputStream in = zip.getInputStream(zip.getEntry(name))) {
                    hashClass(name, readAll(in), digest);
                }
            }
        }
    }

    /**
     * Adds the ABI of the class, nothing when the class is not visible to other classes, and the class file itself when
     * its ABI cannot be told.
     */
    private void hashClass(String name, byte[] classBytes, MessageDigest digest) {
        byte[] abi;
        try {
            Optional<byte[]> apiClass = apiClassExtractor.extractApiClassFrom(classBytes);
            if (!apiClass.isPresent()) {
                return;
            }
            abi = apiClass.get();
        } catch (Exception e) {
            abi = classBytes;
        }
        digest.update(name.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(newDigest().digest(abi));
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return hex.toString();
    }
}
