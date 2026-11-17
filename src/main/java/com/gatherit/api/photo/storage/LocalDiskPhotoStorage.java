package com.gatherit.api.photo.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

@Slf4j
public class LocalDiskPhotoStorage implements PhotoStorage {

    private final String localDir;

    public LocalDiskPhotoStorage(String localDir) {
        this.localDir = localDir;
    }

    @Override
    public void put(String key, MultipartFile file) throws IOException {
        Path abs = resolve(key);
        Files.createDirectories(abs.getParent());
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, abs, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public InputStream open(String key) throws IOException {
        return Files.newInputStream(resolve(key));
    }

    @Override
    public long size(String key) {
        try {
            return Files.size(resolve(key));
        } catch (IOException e) {
            return -1;
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.exists(resolve(key));
    }

    @Override
    public void deleteSafe(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            log.error("Local disk delete failed key={}", key, e);
        }
    }

    private Path resolve(String key) {
        return Paths.get(localDir, key).toAbsolutePath().normalize();
    }
}
