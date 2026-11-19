package dev.taskforge;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.context.annotation.Configuration;
import java.nio.file.*;
import java.io.*;
import java.security.*;
import java.util.*;

@RestController
@ConditionalOnProperty(name="taskforge.role",havingValue="api",matchIfMissing=true)
@RequestMapping("/api")
class Api {
    record Submit(@NotNull UUID datasetId) {}
    private final JobStore jobs;
    private final ObjectStorage storage;
    Api(JobStore jobs,ObjectStorage storage) { this.jobs=jobs;this.storage=storage; }
    static String owner(HttpServletRequest r) { return (String)r.getAttribute("owner"); }
    @PostMapping(value="/datasets",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<Map<String,Object>> upload(@RequestParam MultipartFile file,HttpServletRequest request) throws Exception {
        if(file.isEmpty() || file.getSize()>52_428_800 || file.getOriginalFilename()==null
                || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".csv"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        Path temp=Files.createTempFile("taskforge-upload-",".csv");
        UUID id=UUID.randomUUID();
        String key="inputs/"+id+".csv";
        boolean stored=false;
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            try(var in=new DigestInputStream(file.getInputStream(),digest)) { Files.copy(in,temp,StandardCopyOption.REPLACE_EXISTING); }
            storage.putFile(key,temp);stored=true;
            jobs.dataset(id,owner(request),key,HexFormat.of().formatHex(digest.digest()),Files.size(temp));
            return ResponseEntity.status(201).body(Map.of("datasetId",id,"sizeBytes",Files.size(temp)));
        } catch(Exception e) {
            if(stored)try { storage.delete(key); } catch(Exception ignored) { /* later orphan cleanup */ }
            throw e;
        } finally { Files.deleteIfExists(temp); }
    }
    @PostMapping("/jobs")
    ResponseEntity<Map<String,Object>> submit(@Valid @RequestBody Submit body,@RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest r) {
        var j=jobs.create(owner(r),body.datasetId(),key);
        return ResponseEntity.accepted().header("Location","/api/jobs/"+j.id()).body(view(j));
    }
    @GetMapping("/jobs/{id}") Map<String,Object> status(@PathVariable UUID id,HttpServletRequest r) { return view(jobs.get(owner(r),id)); }
    @GetMapping("/jobs/{id}/attempts") List<Map<String,Object>> attempts(@PathVariable UUID id,HttpServletRequest r) { return jobs.attempts(owner(r),id); }
    @GetMapping("/jobs/{id}/result") ResponseEntity<StreamingResponseBody> result(@PathVariable UUID id,HttpServletRequest r) {
        var j=jobs.get(owner(r),id);
        if(!j.status().equals("SUCCEEDED"))throw new ResponseStatusException(HttpStatus.CONFLICT);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .header("Content-Disposition","attachment; filename=report-"+id+".json")
                .body(out -> { try(var input=storage.read(j.outputKey())) { input.transferTo(out); }
                    catch(Exception e) { throw new IOException("Report storage unavailable"); } });
    }
    @PostMapping("/admin/jobs/{id}/replay") Map<String,Object> replay(@PathVariable UUID id,HttpServletRequest r) {
        if(!owner(r).equals("admin"))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return view(jobs.replay(id));
    }
    static Map<String,Object> view(JobStore.Job j) {
        var m=new LinkedHashMap<String,Object>();
        m.put("jobId",j.id());m.put("status",j.status());m.put("attemptCount",j.attemptCount());
        m.put("createdAt",j.createdAt());m.put("startedAt",j.startedAt());m.put("finishedAt",j.finishedAt());
        m.put("errorCode",j.errorCode());m.put("statusUrl","/api/jobs/"+j.id());
        return m;
    }
}
@Configuration
class ApiAuthentication implements WebMvcConfigurer {
    private final Map<String,String> identities=new HashMap<>();
    ApiAuthentication(Settings settings) {
        for(String entry:settings.authTokens().split(",")) {
            var pair=entry.split("=",2);
            if(pair.length!=2 || pair[0].length()<24 || !pair[1].matches("[a-zA-Z0-9_-]{1,120}"))
                throw new IllegalArgumentException("Configure random bearer tokens of at least 24 characters mapped to owner IDs");
            identities.put(pair[0],pair[1]);
        }
    }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
                String header=request.getHeader("Authorization");
                String owner=header!=null&&header.startsWith("Bearer ")?identities.get(header.substring(7)):null;
                if(owner==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
                request.setAttribute("owner",owner);return true;
            }
        }).addPathPatterns("/api/**");
    }
}
@RestControllerAdvice
class SafeErrors {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String,Object>> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("error",e.getStatusCode().value()));
    }
    @ExceptionHandler({org.springframework.web.bind.MethodArgumentNotValidException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String,Object>> invalid(Exception e) { return ResponseEntity.badRequest().body(Map.of("error","invalid_request")); }
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String,Object>> large(Exception e) { return ResponseEntity.status(413).body(Map.of("error","upload_too_large")); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String,Object>> unavailable(Exception e) { return ResponseEntity.status(503).body(Map.of("error","service_unavailable")); }
}
