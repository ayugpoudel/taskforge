package dev.taskforge;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.*;

/** Authenticate before Spring's multipart resolver allocates temporary upload files. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class ApiAuthentication extends OncePerRequestFilter {
    private final Map<String,String> identities=new HashMap<>();
    ApiAuthentication(Settings settings) {
        for(String entry:settings.authTokens().split(",")) {
            var pair=entry.split("=",2);
            if(pair.length!=2||pair[0].length()<24||!pair[1].matches("[a-zA-Z0-9_-]{1,120}")||identities.putIfAbsent(pair[0],pair[1])!=null)
                throw new IllegalArgumentException("Configure unique random bearer tokens (24+ characters) mapped to owner IDs");
        }
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api"); }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        response.setHeader("Cache-Control","no-store");
        String authorization=request.getHeader("Authorization");
        String owner=authorization!=null&&authorization.startsWith("Bearer ")?identities.get(authorization.substring(7)):null;
        if(owner==null) {
            response.setStatus(401);response.setContentType("application/json");response.getWriter().write("{\"error\":\"unauthorized\"}");return;
        }
        request.setAttribute("owner",owner);chain.doFilter(request,response);
    }
}
