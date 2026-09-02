package com.opsagent.knowledge;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.LocalDate;
import java.util.*;

/** 基于本地磁盘的文件存储实现，并阻止相对路径逃逸存储根目录。 */
@Service
public class LocalFileStorageService implements FileStorageService {
    private final Path root;

    LocalFileStorageService(KnowledgeProperties p) {
        root = Path.of(p.getStorageRoot()).toAbsolutePath().normalize();
    }

    public StoredFile store(MultipartFile file) throws IOException {
        String name =
                Path.of(Objects.requireNonNullElse(file.getOriginalFilename(), ""))
                        .getFileName()
                        .toString();
        int dot = name.lastIndexOf('.');
        if (dot < 1) throw new IllegalArgumentException("文件缺少扩展名");
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!Set.of("pdf", "docx", "txt", "md", "markdown").contains(ext))
            throw new IllegalArgumentException("仅支持 PDF、DOCX、TXT 和 Markdown");
        String rel =
                LocalDate.now().toString().replace('-', '/') + "/" + UUID.randomUUID() + "." + ext;
        Path target = resolve(rel);
        Files.createDirectories(target.getParent());
        MessageDigest d;
        try {
            d = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (var in = new DigestInputStream(file.getInputStream(), d)) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return new StoredFile(name, rel, ext, file.getSize(), HexFormat.of().formatHex(d.digest()));
    }

    public Path resolve(String relative) {
        Path p = root.resolve(relative).normalize();
        // normalize 后再次校验前缀，防止通过 ../ 访问存储目录之外的文件。
        if (!p.startsWith(root)) throw new IllegalArgumentException("非法存储路径");
        return p;
    }
}
