package com.xiaoxu.desensitize.pseudonym;

import com.xiaoxu.desensitize.annotation.SensitiveType;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 可选的加密文件保险库。每次操作在文件锁下重新读取，写入则加密后原子替换。
 * 调用方负责从仓库外提供 AES 密钥；此类不保存密钥，也不自动降级为明文。
 * 多进程使用时必须协调密钥轮换；旧密钥进程会解密失败，不会返回错误的明文。
 */
public final class EncryptedFileTokenVault implements ScopedTokenVault {

    private static final int MAGIC = 0x54564c54; // TVLT
    private static final int VERSION = 1;
    private static final int IV_BYTES = 12;
    private static final int MAX_FILE_BYTES = 64 * 1024 * 1024;
    private static final int MAX_STRING_BYTES = 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<Path, Object> JVM_LOCKS = new ConcurrentHashMap<>();

    private final Path file;
    private final Path lockFile;
    private final Object jvmLock;
    private final Duration ttl;
    private final Clock clock;
    private final int maxFileBytes;
    private volatile SecretKey key;
    private volatile RestoreAudit audit = (scope, type) -> { };

    public EncryptedFileTokenVault(Path file, SecretKey key, Duration ttl) {
        this(file, key, ttl, Clock.systemUTC());
    }

    EncryptedFileTokenVault(Path file, SecretKey key, Duration ttl, Clock clock) {
        this(file, key, ttl, clock, MAX_FILE_BYTES);
    }

    EncryptedFileTokenVault(Path file, SecretKey key, Duration ttl, Clock clock, int maxFileBytes) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        this.lockFile = this.file.resolveSibling(this.file.getFileName() + ".lock");
        this.jvmLock = JVM_LOCKS.computeIfAbsent(this.lockFile, ignored -> new Object());
        this.key = Objects.requireNonNull(key, "key");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxFileBytes <= 8 + IV_BYTES + 16) {
            throw new IllegalArgumentException("保险库文件上限过小");
        }
        this.maxFileBytes = maxFileBytes;
        if (ttl != null && (ttl.isZero() || ttl.isNegative())) {
            throw new IllegalArgumentException("ttl 必须为正数");
        }
        this.ttl = ttl;
        if (this.file.getParent() == null || !Files.isDirectory(this.file.getParent())) {
            throw new IllegalArgumentException("保险库父目录必须已存在");
        }
        locked(this::load); // 启动时验证密钥、认证标签和文件结构；损坏时立即失败
    }

    public void auditWith(RestoreAudit audit) {
        this.audit = audit == null ? (scope, type) -> { } : audit;
    }

    @Override
    public void remember(VaultScope scope, String token, SensitiveType type, String raw) {
        if (scope == null || token == null || raw == null) return;
        locked(() -> {
            Map<EntryKey, Entry> entries = load();
            EntryKey id = new EntryKey(scope.namespace(), token);
            Entry previous = entries.get(id);
            if (previous != null && !previous.expired(clock) && !previous.raw().equals(raw)) {
                throw new TokenCollisionException(type, token);
            }
            if (previous == null || previous.expired(clock)) {
                entries.put(id, new Entry(type, raw, ttl == null ? null : clock.instant().plus(ttl)));
                save(entries, key);
            }
            return null;
        });
    }

    @Override
    public Optional<String> original(VaultScope scope, String token) {
        if (scope == null || token == null) return Optional.empty();
        Entry found = locked(() -> load().get(new EntryKey(scope.namespace(), token)));
        if (found == null || found.expired(clock)) return Optional.empty();
        audit.restored(scope, found.type());
        return Optional.of(found.raw());
    }

    @Override
    public int size(VaultScope scope) {
        if (scope == null) return 0;
        return locked(() -> (int) load().entrySet().stream()
                .filter(entry -> entry.getKey().scope().equals(scope.namespace()))
                .filter(entry -> !entry.getValue().expired(clock)).count());
    }

    @Override
    public void forget(VaultScope scope) {
        if (scope == null) return;
        locked(() -> {
            Map<EntryKey, Entry> entries = load();
            if (entries.keySet().removeIf(id -> id.scope().equals(scope.namespace()))) {
                save(entries, key);
            }
            return null;
        });
    }

    /** 原子地用新密钥重加密；成功后旧密钥不再能打开文件。 */
    public void rotateKey(SecretKey replacement) {
        Objects.requireNonNull(replacement, "replacement");
        locked(() -> {
            Map<EntryKey, Entry> entries = load();
            save(entries, replacement);
            key = replacement;
            return null;
        });
    }

    @Override
    public void remember(String token, SensitiveType type, String raw) {
        remember(VaultScope.GLOBAL, token, type, raw);
    }

    @Override
    public Optional<String> original(String token) {
        return original(VaultScope.GLOBAL, token);
    }

    @Override
    public int size() {
        return size(VaultScope.GLOBAL);
    }

    private <T> T locked(IOAction<T> action) {
        synchronized (jvmLock) {
            try (FileChannel channel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var ignored = channel.lock()) {
                return action.run();
            } catch (IOException e) {
                throw new IllegalStateException("保险库不可用；已拒绝还原或写入", e);
            }
        }
    }

    private Map<EntryKey, Entry> load() throws IOException {
        if (!Files.exists(file)) return new HashMap<>();
        if (Files.size(file) > maxFileBytes) throw new IOException("保险库文件超过大小上限");
        try (DataInputStream input = new DataInputStream(Files.newInputStream(file))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                throw new IOException("保险库文件头无效");
            }
            byte[] iv = input.readNBytes(IV_BYTES);
            if (iv.length != IV_BYTES) throw new IOException("保险库 IV 不完整");
            byte[] ciphertext = input.readAllBytes();
            if (ciphertext.length < 16) throw new IOException("保险库密文不完整");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] plain = cipher.doFinal(ciphertext);
            try (DataInputStream rows = new DataInputStream(new ByteArrayInputStream(plain))) {
                int count = rows.readInt();
                if (count < 0 || count > 1_000_000) throw new IOException("保险库条目数无效");
                Map<EntryKey, Entry> entries = new HashMap<>();
                for (int index = 0; index < count; index++) {
                    EntryKey id = new EntryKey(readString(rows), readString(rows));
                    String typeName = readString(rows);
                    SensitiveType type = typeName.isEmpty() ? null : SensitiveType.valueOf(typeName);
                    String raw = readString(rows);
                    long expires = rows.readLong();
                    Entry entry = new Entry(type, raw, expires < 0 ? null : Instant.ofEpochMilli(expires));
                    if (entries.putIfAbsent(id, entry) != null) throw new IOException("保险库存在重复令牌");
                }
                if (rows.available() != 0) throw new IOException("保险库存在多余数据");
                return entries;
            }
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IOException("保险库解密或结构校验失败", e);
        }
    }

    private void save(Map<EntryKey, Entry> entries, SecretKey encryptionKey) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream rows = new DataOutputStream(bytes)) {
            rows.writeInt(entries.size());
            for (Map.Entry<EntryKey, Entry> row : entries.entrySet()) {
                writeString(rows, row.getKey().scope());
                writeString(rows, row.getKey().token());
                writeString(rows, row.getValue().type() == null ? "" : row.getValue().type().name());
                writeString(rows, row.getValue().raw());
                rows.writeLong(row.getValue().expiresAt() == null ? -1 : row.getValue().expiresAt().toEpochMilli());
            }
        }
        // 文件头 8 字节、IV 12 字节、GCM 标签 16 字节也计入读取上限。
        // 写出超限文件会让下一次 load 拒绝整座保险库，必须在替换旧文件前阻止。
        if (bytes.size() > maxFileBytes - 8 - IV_BYTES - 16) {
            throw new IOException("保险库写入后将超过文件大小上限");
        }
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        byte[] ciphertext;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(128, iv));
            ciphertext = cipher.doFinal(bytes.toByteArray());
        } catch (GeneralSecurityException e) {
            throw new IOException("保险库加密失败", e);
        }
        Path temp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        try {
            try (FileChannel output = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.allocate(8 + IV_BYTES + ciphertext.length);
                buffer.putInt(MAGIC).putInt(VERSION).put(iv).put(ciphertext).flip();
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) throw new IOException("保险库字段超过大小上限");
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) throw new IOException("保险库字段长度无效");
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new IOException("保险库字段不完整");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private record EntryKey(String scope, String token) { }

    private record Entry(SensitiveType type, String raw, Instant expiresAt) {
        boolean expired(Clock clock) {
            return expiresAt != null && !clock.instant().isBefore(expiresAt);
        }
    }

    private interface IOAction<T> {
        T run() throws IOException;
    }
}
