package com.homeexpress.home_express_api.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class FileStorageService {

    private final Path uploadRoot;
    private final long maxFileSize;
    private final Set<String> allowedMimeTypes;

    public FileStorageService(
            @Value("${file.upload.dir:uploads}") String uploadDir,
            @Value("${file.max-size:10485760}") long maxFileSize,
            @Value("${file.allowed-types:image/jpeg,image/png,image/jpg,image/gif,application/pdf}") String allowedTypes) {
        this.uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
        this.maxFileSize = maxFileSize;
        this.allowedMimeTypes = Arrays.stream(allowedTypes.split(","))
                .map(String::trim)
                .filter(type -> !type.isEmpty())
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
    }

    public StoredFile storeFile(MultipartFile file, String category) throws IOException {
        validateFile(file);

        String safeCategory = (category == null || category.isBlank())
                ? "general"
                : category.trim().toLowerCase();

        Path targetDir = uploadRoot.resolve(safeCategory).normalize();
        if (!targetDir.startsWith(uploadRoot)) {
            throw new IllegalArgumentException("Invalid upload path");
        }

        try {
            Files.createDirectories(targetDir);
        } catch (FileAlreadyExistsException ignored) {
            // Directory already exists - no action needed
        }

        String extension = resolveExtension(file.getOriginalFilename(), file.getContentType());
        String fileName = UUID.randomUUID().toString() + extension;

        Path filePath = targetDir.resolve(fileName);
        Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

        String relativeUrl = "/uploads/" + safeCategory + "/" + fileName;
        String publicUrl = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path(relativeUrl)
                .toUriString();

        return new StoredFile(fileName, relativeUrl, publicUrl, file.getSize(), file.getContentType());
    }

    public StoredFile storeAvatar(MultipartFile file) throws IOException {
        return storeFile(file, "avatars");
    }

    public String saveAvatar(MultipartFile file) throws IOException {
        return storeAvatar(file).fileUrl();
    }

    public void deleteAvatar(String avatarUrl) {
        if (avatarUrl == null || avatarUrl.isBlank()) {
            return;
        }

        try {
            String normalizedUrl = avatarUrl.replace("\\", "/");
            String fileName = normalizedUrl.substring(normalizedUrl.lastIndexOf("/") + 1);
            Path filePath = uploadRoot.resolve("avatars").resolve(fileName).normalize();
            if (filePath.startsWith(uploadRoot)) {
                Files.deleteIfExists(filePath);
            }
        } catch (IOException ignored) {
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }

        if (file.getSize() > maxFileSize) {
            throw new IllegalArgumentException("File exceeds the maximum size of " + maxFileSize + " bytes");
        }

        String contentType = file.getContentType();
        if (!allowedMimeTypes.isEmpty() && contentType != null && !allowedMimeTypes.contains(contentType.toLowerCase())) {
            throw new IllegalArgumentException("File type not allowed: " + contentType);
        }
    }

    private String resolveExtension(String originalFilename, String contentType) {
        if (originalFilename != null && originalFilename.contains(".")) {
            return originalFilename.substring(originalFilename.lastIndexOf("."));
        }

        if (contentType == null) {
            return "";
        }

        return switch (contentType.toLowerCase()) {
            case "image/png" -> ".png";
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "application/pdf" -> ".pdf";
            default -> "";
        };
    }

    public record StoredFile(
            String fileName,
            String fileUrl,
            String publicUrl,
            long fileSizeBytes,
            String mimeType
    ) {}
}
