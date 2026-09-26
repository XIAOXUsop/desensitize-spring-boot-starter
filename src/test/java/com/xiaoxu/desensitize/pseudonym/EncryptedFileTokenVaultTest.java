package com.xiaoxu.desensitize.pseudonym;

import com.xiaoxu.desensitize.annotation.SensitiveType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EncryptedFileTokenVaultTest {

    private static SecretKeySpec key(byte value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, value);
        return new SecretKeySpec(bytes, "AES");
    }

    @Test
    void restartAndForgetKeepScopesSeparate(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("vault.bin");
        SecretKeySpec secret = key((byte) 0);
        VaultScope alice = VaultScope.of("alice");
        VaultScope bob = VaultScope.of("bob");
        EncryptedFileTokenVault first = new EncryptedFileTokenVault(file, secret, null);
        first.remember(alice, "TOKEN", SensitiveType.NAME, "张三");
        first.remember(bob, "TOKEN", SensitiveType.NAME, "李四");
        assertFalse(new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8).contains("张三"));

        EncryptedFileTokenVault restarted = new EncryptedFileTokenVault(file, secret, null);
        assertEquals("张三", restarted.original(alice, "TOKEN").orElseThrow());
        restarted.forget(alice);
        EncryptedFileTokenVault afterForget = new EncryptedFileTokenVault(file, secret, null);
        assertTrue(afterForget.original(alice, "TOKEN").isEmpty());
        assertEquals("李四", afterForget.original(bob, "TOKEN").orElseThrow());
    }

    @Test
    void collisionAndDamagedCiphertextFailClosed(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("vault.bin");
        SecretKeySpec secret = key((byte) 0);
        EncryptedFileTokenVault vault = new EncryptedFileTokenVault(file, secret, null);
        vault.remember("T", SensitiveType.NAME, "Alice");
        assertThrows(TokenCollisionException.class,
                () -> vault.remember("T", SensitiveType.NAME, "Bob"));
        assertEquals("Alice", vault.original("T").orElseThrow());
        assertThrows(IllegalStateException.class,
                () -> new EncryptedFileTokenVault(file, key((byte) 1), null));
        byte[] damaged = Files.readAllBytes(file);
        damaged[damaged.length - 1] ^= 1;
        Files.write(file, damaged);
        assertThrows(IllegalStateException.class, () -> vault.original("T"));
    }

    @Test
    void expirationAndKeyRotationSurviveRestart(@TempDir Path dir) {
        Path file = dir.resolve("vault.bin");
        SecretKeySpec oldKey = key((byte) 0);
        SecretKeySpec newKey = key((byte) 7);
        Clock start = Clock.fixed(Instant.parse("2026-09-26T00:00:00Z"), ZoneOffset.UTC);
        EncryptedFileTokenVault vault = new EncryptedFileTokenVault(file, oldKey, Duration.ofSeconds(10), start);
        vault.remember("T", SensitiveType.NAME, "Alice");
        vault.rotateKey(newKey);
        assertThrows(IllegalStateException.class, () -> new EncryptedFileTokenVault(file, oldKey, null));
        assertEquals("Alice", new EncryptedFileTokenVault(file, newKey, null, start).original("T").orElseThrow());
        Clock later = Clock.offset(start, Duration.ofSeconds(11));
        assertTrue(new EncryptedFileTokenVault(file, newKey, null, later).original("T").isEmpty());
    }

    @Test
    void concurrentInstancesDoNotLoseMappings(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("vault.bin");
        SecretKeySpec secret = key((byte) 0);
        EncryptedFileTokenVault left = new EncryptedFileTokenVault(file, secret, null);
        EncryptedFileTokenVault right = new EncryptedFileTokenVault(file, secret, null);
        try (var pool = Executors.newFixedThreadPool(4)) {
            List<Future<?>> tasks = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                int id = index;
                tasks.add(pool.submit(() -> (id % 2 == 0 ? left : right)
                        .remember("T" + id, SensitiveType.NAME, "person-" + id)));
            }
            for (Future<?> task : tasks) task.get();
        }
        assertEquals(20, new EncryptedFileTokenVault(file, secret, null).size());
    }

    @Test
    void oversizedWriteKeepsPreviousVaultReadable(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("vault.bin");
        SecretKeySpec secret = key((byte) 3);
        EncryptedFileTokenVault vault = new EncryptedFileTokenVault(file, secret, null, Clock.systemUTC(), 160);
        vault.remember("keep", SensitiveType.NAME, "Alice");
        long previousSize = Files.size(file);

        assertThrows(IllegalStateException.class,
                () -> vault.remember("too-large", SensitiveType.NAME, "X".repeat(200)));
        assertEquals(previousSize, Files.size(file));
        assertEquals("Alice", vault.original("keep").orElseThrow());
    }
}
