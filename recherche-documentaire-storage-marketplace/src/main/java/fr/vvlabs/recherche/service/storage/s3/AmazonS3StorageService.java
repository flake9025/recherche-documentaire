package fr.vvlabs.recherche.service.storage.s3;

import fr.vvlabs.recherche.service.storage.StorageService;
import fr.vvlabs.recherche.service.storage.StorageType;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Store documentaire S3 avec cache local.
 * Compatible AWS S3 et MinIO (via endpoint override + path-style).
 */
@Service
@ConditionalOnProperty(name = "app.storage.s3.enabled", havingValue = "true")
@Slf4j
public class AmazonS3StorageService implements StorageService {

    private final S3Client s3;
    private final String bucket;
    private final Path cacheDir;
    private final boolean autoCreateBucket;

    private final ConcurrentHashMap<String, ReentrantLock> keyLocks = new ConcurrentHashMap<>();

    public AmazonS3StorageService(
            @Value("${app.storage.s3.endpoint:}") String endpoint,
            @Value("${app.storage.s3.region:us-east-1}") String region,
            @Value("${app.storage.s3.access-key:minioadmin}") String accessKey,
            @Value("${app.storage.s3.secret-key:minioadmin}") String secretKey,
            @Value("${app.storage.s3.bucket:documents}") String bucket,
            @Value("${app.storage.s3.cache-path:./storage/s3-cache}") String cachePath,
            @Value("${app.storage.s3.auto-create-bucket:true}") boolean autoCreateBucket
    ) {
        var builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)
                ));
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint))
                    .serviceConfiguration(S3Configuration.builder()
                            .pathStyleAccessEnabled(true)
                            .build());
        }
        this.s3 = builder.build();
        this.bucket = bucket;
        this.cacheDir = Path.of(cachePath);
        this.autoCreateBucket = autoCreateBucket;
    }

    @PostConstruct
    void init() throws IOException {
        Files.createDirectories(cacheDir);
        ensureBucketExists();
        log.info("S3 storage initialise : bucket={}, cache={}", bucket, cacheDir);
    }

    @Override
    public String getType() {
        return StorageType.AMAZON_S3;
    }

    @Override
    public Path storeFile(MultipartFile file, String titre) throws IOException {
        String key = generateKey(file.getOriginalFilename(), titre);

        try (InputStream input = file.getInputStream()) {
            s3.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(file.getContentType())
                            .build(),
                    RequestBody.fromInputStream(input, file.getSize())
            );
        }
        log.info("Uploaded to S3: s3://{}/{}", bucket, key);

        Path cached = cacheLocally(key);
        return cached;
    }

    @Override
    public Path getPath(String nomFichier) {
        if (nomFichier == null || nomFichier.isEmpty()) {
            return cacheDir;
        }

        Path cached = cacheDir.resolve(nomFichier);
        if (Files.exists(cached)) {
            return cached;
        }

        ReentrantLock lock = keyLocks.computeIfAbsent(nomFichier, k -> new ReentrantLock());
        lock.lock();
        try {
            if (Files.exists(cached)) {
                return cached;
            }
            downloadToCache(nomFichier);
            return cached;
        } catch (IOException e) {
            log.error("Impossible de telecharger s3://{}/{} dans le cache", bucket, nomFichier, e);
            return cached;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean deleteFile(Path file) {
        String key = file.getFileName().toString();
        try {
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
            Files.deleteIfExists(cacheDir.resolve(key));
            keyLocks.remove(key);
            log.info("Deleted from S3: s3://{}/{}", bucket, key);
            return true;
        } catch (Exception e) {
            log.error("Error deleting s3://{}/{}", bucket, key, e);
            return false;
        }
    }

    @Override
    public Path moveFile(Path source, Path target) throws IOException {
        String sourceKey = source.getFileName().toString();
        String targetKey = target.getFileName().toString();

        s3.copyObject(CopyObjectRequest.builder()
                .sourceBucket(bucket)
                .sourceKey(sourceKey)
                .destinationBucket(bucket)
                .destinationKey(targetKey)
                .build());
        s3.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(sourceKey)
                .build());

        Path cachedSource = cacheDir.resolve(sourceKey);
        Path cachedTarget = cacheDir.resolve(targetKey);
        if (Files.exists(cachedSource)) {
            Files.move(cachedSource, cachedTarget, StandardCopyOption.REPLACE_EXISTING);
        }

        keyLocks.remove(sourceKey);
        log.info("Moved in S3: {} -> {}", sourceKey, targetKey);
        return cachedTarget;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String generateKey(String originalFilename, String titre) {
        String extension = "";
        if (originalFilename != null && !originalFilename.isEmpty()) {
            int lastDot = originalFilename.lastIndexOf('.');
            extension = lastDot > 0 ? originalFilename.substring(lastDot) : "";
        }
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        return String.format("%s_%s_%s%s",
                titre.replaceAll("[^a-zA-Z0-9.-]", "_"),
                timestamp,
                uniqueId,
                extension);
    }

    private Path cacheLocally(String key) throws IOException {
        Path cached = cacheDir.resolve(key);
        Path temp = cacheDir.resolve(key + ".tmp." + Thread.currentThread().threadId());
        try {
            s3.getObject(
                    GetObjectRequest.builder().bucket(bucket).key(key).build(),
                    temp
            );
            Files.move(temp, cached, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
        return cached;
    }

    private void downloadToCache(String key) throws IOException {
        cacheLocally(key);
    }

    private void ensureBucketExists() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            log.info("S3 bucket '{}' exists", bucket);
        } catch (NoSuchBucketException e) {
            if (autoCreateBucket) {
                s3.createBucket(b -> b.bucket(bucket));
                log.info("S3 bucket '{}' created", bucket);
            } else {
                throw new IllegalStateException("S3 bucket '" + bucket + "' does not exist and auto-create is disabled");
            }
        }
    }
}
