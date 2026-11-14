package com.meetingintelligence.infrastructure.storage;

import io.minio.*;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class S3StorageService {

    private final MinioClient minioClient;
    private final String bucketName;

    public S3StorageService(
            @Value("${app.s3.endpoint:http://localhost:9000}") String endpoint,
            @Value("${app.s3.access-key:minio_admin}") String accessKey,
            @Value("${app.s3.secret-key:minio_secure_password}") String secretKey,
            @Value("${app.s3.bucket-name:meet-recordings}") String bucketName) {
        this.bucketName = bucketName;
        this.minioClient = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        ensureBucketExists();
    }

    private void ensureBucketExists() {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
                log.info("Bucket '{}' criado com sucesso no MinIO/S3.", bucketName);
            }
        } catch (Exception e) {
            log.warn("Não foi possível verificar/criar bucket '{}' no MinIO: {}", bucketName, e.getMessage());
        }
    }

    public String uploadFile(UUID meetingId, MultipartFile file) {
        String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "audio.webm";
        String objectKey = String.format("meetings/%s/%s_%s", meetingId, UUID.randomUUID(), originalFilename);

        try (InputStream inputStream = file.getInputStream()) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectKey)
                            .stream(inputStream, file.getSize(), -1)
                            .contentType(file.getContentType())
                            .build()
            );
            log.info("Arquivo gravado no S3: {}", objectKey);
            return objectKey;
        } catch (Exception e) {
            log.error("Erro ao fazer upload para o S3: {}", e.getMessage(), e);
            throw new RuntimeException("Falha no upload para o storage: " + e.getMessage());
        }
    }

    public String uploadInputStream(UUID meetingId, String filename, InputStream inputStream, long size, String contentType) {
        String objectKey = String.format("meetings/%s/%s_%s", meetingId, UUID.randomUUID(), filename);
        try {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectKey)
                            .stream(inputStream, size, -1)
                            .contentType(contentType)
                            .build()
            );
            return objectKey;
        } catch (Exception e) {
            log.error("Erro ao fazer upload de stream para o S3: {}", e.getMessage(), e);
            throw new RuntimeException("Falha no upload de stream para o storage: " + e.getMessage());
        }
    }

    public String generatePresignedUrl(String objectKey) {
        try {
            return minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(bucketName)
                            .object(objectKey)
                            .expiry(2, TimeUnit.HOURS)
                            .build()
            );
        } catch (Exception e) {
            log.error("Erro ao gerar presigned URL para {}: {}", objectKey, e.getMessage());
            return "";
        }
    }
}

