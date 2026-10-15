package com.code.pal.agent.runtime;

import java.io.IOException;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;

/** Bound headers and response bytes by one deadline; never accumulate an unbounded API body. */
public final class BoundedHttp {
    public record Reply(int status,HttpHeaders headers,byte[] body) {}
    private BoundedHttp() {}
    public static Reply send(HttpClient client,HttpRequest request,int maxBytes,Duration timeout) throws IOException,InterruptedException {
        if(maxBytes<1 || maxBytes==Integer.MAX_VALUE || timeout.isNegative() || timeout.isZero())throw new IllegalArgumentException("Invalid HTTP bounds");
        long deadline=System.nanoTime()+timeout.toNanos();
        var future=client.sendAsync(request,HttpResponse.BodyHandlers.ofInputStream());
        try(var cancellation=RunCancellation.current().onCancel(()->future.cancel(true))) {
            var response=future.get(remaining(deadline),TimeUnit.NANOSECONDS);
            try(var stream=response.body();var readingCancellation=RunCancellation.current().onCancel(()->{try{stream.close();}catch(IOException ignored){}});
                var reader=Executors.newVirtualThreadPerTaskExecutor()) {
                var read=reader.submit(()->stream.readNBytes(maxBytes+1));
                byte[] bytes;
                try{bytes=read.get(remaining(deadline),TimeUnit.NANOSECONDS);}finally{stream.close();read.cancel(true);}
                if(bytes.length>maxBytes)throw new IOException("API response exceeds byte limit");
                return new Reply(response.statusCode(),response.headers(),bytes);
            }
        }catch(TimeoutException|ExecutionException failure){throw new IOException("API response unavailable within bounds",failure);}
        finally{future.cancel(true);}
    }
    private static long remaining(long deadline) throws TimeoutException {long nanos=deadline-System.nanoTime();if(nanos<=0)throw new TimeoutException();return nanos;}
}
