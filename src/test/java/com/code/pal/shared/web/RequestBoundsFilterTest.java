package com.code.pal.shared.web;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import jakarta.servlet.http.*;
import static org.junit.jupiter.api.Assertions.*;
class RequestBoundsFilterTest {
    @Test void declaredAndChunkedBodiesCannotExceedTheSameLimit() throws Exception {
        var request=new MockHttpServletRequest("POST","/api/auth/login");request.setContent(new byte[65]);
        var response=new MockHttpServletResponse();new RequestBoundsFilter(64).doFilter(request,response,(req,res)->fail("Declared oversized body must not be parsed"));assertEquals(413,response.getStatus());
        var chunked=new HttpServletRequestWrapper(request){public long getContentLengthLong(){return -1;}};
        var streamResponse=new MockHttpServletResponse();new RequestBoundsFilter(64).doFilter(chunked,streamResponse,(req,res)->req.getInputStream().readAllBytes());assertEquals(413,streamResponse.getStatus());
    }
}
