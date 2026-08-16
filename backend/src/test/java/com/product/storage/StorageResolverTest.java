package com.product.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageResolverTest {

    @Test
    void neutralizesPathTraversalAttemptsInUploadedFilenames(@TempDir Path root) throws IOException {
        var resolver = new StorageResolver(root);

        var key = resolver.allocateKey("assets/originals", "../../etc/passwd");

        assertThat(key).doesNotContain("..");
        assertThat(key).startsWith("assets/originals/");

        var bytes = "stl-bytes".getBytes(StandardCharsets.UTF_8);
        var source = Files.createTempFile(root, "upload", ".tmp");
        Files.write(source, bytes);
        resolver.publish(source, key);

        assertThat(resolver.readBytes(key)).isEqualTo(bytes);
        assertThat(root.resolve(key).normalize().startsWith(root)).isTrue();
    }

    @Test
    void rejectsRawTraversalSegmentsInAStorageKey(@TempDir Path root) {
        var resolver = new StorageResolver(root);

        assertThatThrownBy(() -> resolver.readBytes("../outside.txt")).isInstanceOf(StorageAccessException.class);
        assertThatThrownBy(() -> resolver.readBytes("assets/../../outside.txt")).isInstanceOf(StorageAccessException.class);
    }

    @Test
    void rejectsReadsThatEscapeTheRootThroughASymlink(@TempDir Path root, @TempDir Path outside) throws IOException {
        var resolver = new StorageResolver(root);
        var secret = outside.resolve("secret.txt");
        Files.writeString(secret, "top secret");
        var assetDir = Files.createDirectory(root.resolve("assets"));
        Files.createSymbolicLink(assetDir.resolve("evil.txt"), secret);

        assertThatThrownBy(() -> resolver.readBytes("assets/evil.txt")).isInstanceOf(StorageAccessException.class);
    }

    @Test
    void onlyOnePublishWinsWhenTheSameKeyIsPublishedTwiceSequentially(@TempDir Path root) throws IOException {
        var resolver = new StorageResolver(root);
        var key = "assets/preview.glb";
        var sourceA = Files.createTempFile(root, "a", ".tmp");
        Files.writeString(sourceA, "AAAA");
        var sourceB = Files.createTempFile(root, "b", ".tmp");
        Files.writeString(sourceB, "BBBBB");

        resolver.publish(sourceA, key);
        resolver.publish(sourceB, key);

        assertThat(resolver.readBytes(key)).isEqualTo("AAAA".getBytes(StandardCharsets.UTF_8));
        assertThat(Files.exists(sourceA)).isFalse();
        assertThat(Files.exists(sourceB)).isFalse();
    }
}
