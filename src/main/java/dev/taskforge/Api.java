package dev.taskforge;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.config.annotation.*;

@RestController
@ConditionalOnProperty(name = "taskforge.role", havingValue = "api", matchIfMissing = true)
@RequestMapping("/api")
class Api {
  record Submit(@NotNull UUID datasetId) {}

  private final JobStore jobs;
  private final ObjectStorage storage;

  Api(JobStore jobs, ObjectStorage storage) {
    this.jobs = jobs;
    this.storage = storage;
  }

  static String owner(HttpServletRequest r) {
    return (String) r.getAttribute("owner");
  }

  @PostMapping(value = "/datasets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  ResponseEntity<Map<String, Object>> upload(
      @RequestParam MultipartFile file, HttpServletRequest request) throws Exception {
    if (file.isEmpty()
        || file.getSize() > 52_428_800
        || file.getOriginalFilename() == null
        || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".csv"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    Path temp = Files.createTempFile("taskforge-upload-", ".csv");
    UUID id = UUID.randomUUID();
    String key = "inputs/" + id + ".csv";
    boolean reserved = false;
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      try (var in = new DigestInputStream(file.getInputStream(), digest)) {
        Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
      }
      jobs.reserveDataset(
          id, owner(request), key, HexFormat.of().formatHex(digest.digest()), Files.size(temp));
      reserved = true;
      storage.putFile(key, temp);
      if (!jobs.datasetReady(id)) throw new ResponseStatusException(HttpStatus.GONE);
      return ResponseEntity.status(201)
          .body(Map.of("datasetId", id, "sizeBytes", Files.size(temp)));
    } catch (Exception e) {
      if (reserved) {
        try {
          jobs.abandonDataset(id);
        } catch (Exception ignored) {
          /* unready reservations expire after grace */
        }
        try {
          storage.delete(key);
        } catch (Exception ignored) {
          /* persistent reservation is cleanup evidence */
        }
      }
      throw e;
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  @PostMapping("/jobs")
  ResponseEntity<Map<String, Object>> submit(
      @Valid @RequestBody Submit body,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      HttpServletRequest r) {
    var j = jobs.create(owner(r), body.datasetId(), key);
    return ResponseEntity.accepted().header("Location", "/api/jobs/" + j.id()).body(view(j));
  }

  @GetMapping("/jobs/{id}")
  Map<String, Object> status(@PathVariable UUID id, HttpServletRequest r) {
    return view(jobs.get(owner(r), id));
  }

  @GetMapping("/jobs/{id}/attempts")
  List<Map<String, Object>> attempts(@PathVariable UUID id, HttpServletRequest r) {
    return jobs.attempts(owner(r), id);
  }

  @GetMapping("/jobs/{id}/result")
  ResponseEntity<InputStreamResource> result(@PathVariable UUID id, HttpServletRequest r)
      throws Exception {
    var j = jobs.get(owner(r), id);
    if (jobs.reportExpired(id)) throw new ResponseStatusException(HttpStatus.GONE);
    if (!j.status().equals("SUCCEEDED")) throw new ResponseStatusException(HttpStatus.CONFLICT);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .header("Content-Disposition", "attachment; filename=report-" + id + ".json")
        // Open storage before committing HTTP success headers. Spring closes the resource
        // after streaming; failures to open return the normal sanitized 503 response.
        .body(new InputStreamResource(storage.read(j.outputKey())));
  }

  @PostMapping("/jobs/{id}/cancel")
  Map<String, Object> cancel(@PathVariable UUID id, HttpServletRequest r) {
    return view(jobs.cancel(owner(r), id));
  }

  @PostMapping("/admin/jobs/{id}/replay")
  Map<String, Object> replay(@PathVariable UUID id, HttpServletRequest r) {
    if (!owner(r).equals("admin")) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    return view(jobs.replay(id));
  }

  static Map<String, Object> view(JobStore.Job j) {
    var m = new LinkedHashMap<String, Object>();
    m.put("jobId", j.id());
    m.put("status", j.status());
    m.put("attemptCount", j.attemptCount());
    m.put("createdAt", j.createdAt());
    m.put("startedAt", j.startedAt());
    m.put("finishedAt", j.finishedAt());
    m.put("deadlineAt", j.deadlineAt());
    m.put("errorCode", j.errorCode());
    m.put("statusUrl", "/api/jobs/" + j.id());
    return m;
  }
}

@RestControllerAdvice
class SafeErrors {
  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<Map<String, Object>> status(ResponseStatusException e) {
    return ResponseEntity.status(e.getStatusCode())
        .header("Retry-After", e.getStatusCode().value() == 429 ? "5" : "0")
        .body(Map.of("error", e.getStatusCode().value()));
  }

  @ExceptionHandler({
    org.springframework.web.bind.MethodArgumentNotValidException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<Map<String, Object>> invalid(Exception e) {
    return ResponseEntity.badRequest().body(Map.of("error", "invalid_request"));
  }

  @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
  ResponseEntity<Map<String, Object>> large(Exception e) {
    return ResponseEntity.status(413).body(Map.of("error", "upload_too_large"));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String, Object>> unavailable(Exception e) {
    org.slf4j.LoggerFactory.getLogger(getClass())
        .warn("event=api_unavailable error={}", e.getClass().getSimpleName());
    return ResponseEntity.status(503).body(Map.of("error", "service_unavailable"));
  }
}
