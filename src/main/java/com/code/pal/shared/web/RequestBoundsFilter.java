package com.code.pal.shared.web;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;

/** Bound platform/generated-site ingress before JSON parsing, including chunked input. */
@Component @Order(Ordered.HIGHEST_PRECEDENCE+3)
public final class RequestBoundsFilter extends OncePerRequestFilter {
    private final long max;
    public RequestBoundsFilter(@Value("${app.request.max-bytes:16000000}") long max){if(max<1 || max>100000000)throw new IllegalArgumentException("Invalid request bound");this.max=max;}
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException,ServletException {
        if(request.getContentLengthLong()>max){response.sendError(413,"Request body too large");return;}
        var wrapped=new HttpServletRequestWrapper(request) {
            ServletInputStream bounded;
            public ServletInputStream getInputStream() throws IOException {
                if(bounded!=null)return bounded;var source=super.getInputStream();
                return bounded=new ServletInputStream(){long read;
                    private void count(int n) throws IOException {if(n>0 && (read+=n)>max)throw new BodyTooLarge();}
                    public int read() throws IOException{int value=source.read();if(value>=0)count(1);return value;}
                    public int read(byte[] bytes,int offset,int length) throws IOException{int size=source.read(bytes,offset,(int)Math.min(length,Math.max(1,max-read+1)));count(size);return size;}
                    public boolean isFinished(){return source.isFinished();}public boolean isReady(){return source.isReady();}public void setReadListener(ReadListener listener){source.setReadListener(listener);}public void close() throws IOException{source.close();}
                };
            }
            public BufferedReader getReader() throws IOException{return new BufferedReader(new InputStreamReader(getInputStream(),getCharacterEncoding()==null?"UTF-8":getCharacterEncoding()));}
        };
        try{chain.doFilter(wrapped,response);}catch(BodyTooLarge exceeded){if(!response.isCommitted())response.sendError(413,"Request body too large");}
    }
    private static final class BodyTooLarge extends IOException {}
}
