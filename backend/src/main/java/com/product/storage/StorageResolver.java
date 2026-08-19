package com.product.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves and publishes storage keys under a single contained root. Keys are always generated
 * server-side (D6): callers never incorporate a caller-supplied filename beyond a validated
 * extension, so path-traversal payloads in an original filename never reach the filesystem.
 *
 * <p>D6 names {@code SecureDirectoryStream}+{@code NOFOLLOW_LINKS} as the resolution primitive.
 * That interface is POSIX-only (verified unsupported by this project's Windows dev/CI shell), so
 * this resolver walks each segment and rejects it via {@link LinkOption#NOFOLLOW_LINKS} attribute
 * checks instead — the same fail-closed guarantee, exercised with real symlinks in tests on every
 * platform this project targets.
 */
@Component
public final class StorageResolver {
    private static final Pattern ENCODED_TRAVERSAL = Pattern.compile("(?i)%2e|%2f|%5c");
    private static final Pattern SAFE_EXTENSION = Pattern.compile("\\.[a-zA-Z0-9]{1,8}");

    private final Path root;

    @Autowired
    public StorageResolver(@Value("${SCENE_DATA_ROOT:/data}") String root) {
        this(Path.of(root));
    }

    public StorageResolver(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /** Generates a fully server-controlled key; the caller-supplied filename is never used beyond a validated extension. */
    public String allocateKey(String namespace, String originalFilename) {
        return namespace + "/" + UUID.randomUUID() + safeExtension(originalFilename);
    }

    private static String safeExtension(String originalFilename) {
        if (originalFilename == null) return "";
        var dot = originalFilename.lastIndexOf('.');
        if (dot < 0) return "";
        var candidate = originalFilename.substring(dot);
        return SAFE_EXTENSION.matcher(candidate).matches() ? candidate.toLowerCase(Locale.ROOT) : "";
    }

    public byte[] readBytes(String storageKey) {
        var resolved = walk(storageKey);
        try {
            return Files.readAllBytes(resolved);
        } catch (IOException exception) {
            throw new StorageAccessException(storageKey);
        }
    }

    /**
     * Allocates a fresh temp file inside the storage root (under {@code root/tmp}) so that
     * {@link #publish(Path, String)}'s hard link never crosses a filesystem boundary (EXDEV).
     * Ownership transfers to {@code publish()}, which deletes it on every path; on any other
     * outcome (e.g. intake aborts before publishing) the CALLER is responsible for deleting it.
     */
    public Path createTempFile() {
        try {
            var tempDir = root.resolve("tmp");
            Files.createDirectories(tempDir);
            return Files.createTempFile(tempDir, "asset-upload-", ".tmp");
        } catch (IOException exception) {
            throw new StorageAccessException("tmp");
        }
    }

    /** Opens a read stream for {@code storageKey}. The CALLER (or a framework converter) closes it. */
    public InputStream openInputStream(String storageKey) {
        var resolved = walk(storageKey);
        try {
            return Files.newInputStream(resolved);
        } catch (IOException exception) {
            throw new StorageAccessException(storageKey);
        }
    }

    /**
     * Publishes {@code source} to {@code storageKey} without ever overwriting an existing artifact (ADR-0002).
     *
     * <p><b>Ownership handoff</b>: {@code publish()} takes ownership of {@code source} unconditionally — it
     * deletes it in a {@code finally} block on every path (success, a losing collision, or a link failure).
     * Callers MUST NOT reuse or re-delete {@code source} after calling this method. {@code source} MUST have
     * been allocated via {@link #createTempFile()} (or otherwise reside on the same filesystem as this
     * resolver's root), or the hard link below fails with {@code EXDEV}.
     */
    public void publish(Path source, String storageKey) {
        var target = walk(storageKey);
        try {
            Files.createDirectories(target.getParent());
            Files.createLink(target, source);
        } catch (FileAlreadyExistsException collision) {
            // Another attempt already published this key first; that artifact wins and is left untouched.
        } catch (IOException exception) {
            throw new StorageAccessException(storageKey);
        } finally {
            try {
                Files.deleteIfExists(source);
            } catch (IOException ignored) {
                // best-effort temp cleanup; a leaked temp file is not a correctness issue
            }
        }
    }

    private Path walk(String storageKey) {
        var segments = splitSegments(storageKey);
        var current = root;
        for (var segment : segments) {
            current = current.resolve(segment);
            if (!current.normalize().startsWith(root)) throw new StorageAccessException(storageKey);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new StorageAccessException(storageKey);
            }
        }
        return current;
    }

    private static List<String> splitSegments(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) throw new StorageAccessException(String.valueOf(storageKey));
        if (ENCODED_TRAVERSAL.matcher(storageKey).find()) throw new StorageAccessException(storageKey);
        var segments = new ArrayList<String>();
        for (var segment : storageKey.replace('\\', '/').split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) throw new StorageAccessException(storageKey);
            segments.add(segment);
        }
        if (segments.isEmpty()) throw new StorageAccessException(storageKey);
        return segments;
    }
}
