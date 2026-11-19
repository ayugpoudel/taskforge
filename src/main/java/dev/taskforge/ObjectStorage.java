package dev.taskforge;

import io.minio.*;
import io.minio.errors.ErrorResponseException;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.file.Path;

/** Private bucket; callers never accept arbitrary keys from clients. */
@Component
public class ObjectStorage {
    private final MinioClient client;
    private final String bucket;
    public ObjectStorage(Settings settings) throws Exception {
        bucket = settings.storageBucket();
        client = MinioClient.builder().endpoint(settings.storageEndpoint())
                .credentials(settings.storageAccessKey(), settings.storageSecretKey()).build();
        client.setTimeout(5_000, 30_000, 30_000);
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            try { client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build()); }
            catch (ErrorResponseException e) {
                if (!e.errorResponse().code().equals("BucketAlreadyOwnedByYou")) throw e;
            }
        }
    }
    public void putFile(String key, Path file) throws Exception {
        client.uploadObject(UploadObjectArgs.builder().bucket(bucket).object(key)
                .filename(file.toString()).contentType("text/csv").build());
    }
    public void putReport(String key, byte[] report) throws Exception {
        try (var input = new ByteArrayInputStream(report)) {
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                    .stream(input, report.length, -1).contentType("application/json").build());
        }
    }
    public InputStream read(String key) throws Exception {
        return client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
    }
    public void delete(String key) throws Exception {
        client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
    }
}
