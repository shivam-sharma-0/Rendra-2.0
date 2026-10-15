package com.code.pal.shared.web;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class CookieMutationOriginFilterTest {
    @Test void cookieMutationRejectsMissingAndForeignOriginsButAcceptsTheConfiguredSpa() throws Exception {
        for(String origin:List.of("missing","https://foreign.example","http://localhost:5173")) {
            var request=new MockHttpServletRequest("POST","/api/auth/refresh");if(!origin.equals("missing"))request.addHeader("Origin",origin);
            var response=new MockHttpServletResponse();var called=new java.util.concurrent.atomic.AtomicBoolean();
            new CookieMutationOriginFilter(List.of("http://localhost:5173")).doFilter(request,response,(req,res)->called.set(true));
            assertEquals(origin.equals("http://localhost:5173"),called.get());if(!called.get())assertEquals(403,response.getStatus());
        }
    }
}
