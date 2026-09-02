package com.opsagent.knowledge;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;

/** 文档文件存储抽象，业务层只持久化相对路径。 */
public interface FileStorageService {
    StoredFile store(MultipartFile file) throws IOException;

    Path resolve(String relativePath);

    record StoredFile(
            String originalName, String relativePath, String extension, long size, String sha256) {}
}
