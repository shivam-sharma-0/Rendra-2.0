package com.code.pal.agent.workspace;
import com.code.pal.agent.lease.ProjectLeaseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FencedCommandLockTest {
    @Test void installAndBuildKeepLeaseChecksOutsideTheLongCommandWithoutTakingMutationLock() throws Exception {
        var raw=mock(DockerProjectEnvironment.class);var leases=mock(ProjectLeaseService.class);
        @SuppressWarnings("unchecked") ObjectProvider<DockerProjectEnvironment> docker=mock(ObjectProvider.class);
        @SuppressWarnings("unchecked") ObjectProvider<LocalProjectEnvironment> local=mock(ObjectProvider.class);
        when(docker.getIfAvailable()).thenReturn(raw);var fenced=new FencedSandboxPort(docker,local,leases);var project=UUID.randomUUID();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(raw.install(eq(project),anyString(),any())).thenAnswer(call->{entered.countDown();assertTrue(release.await(2,TimeUnit.SECONDS));return new SandboxPort.CommandResult(true,0,"","");});
        try(var pool=Executors.newSingleThreadExecutor()){var work=pool.submit(()->fenced.install(project,".codepal/deps/fixture",List.of("npm","ci")));assertTrue(entered.await(1,TimeUnit.SECONDS));verify(leases).checkCurrent(project);verify(leases,never()).guarded(any(),any(java.util.function.Supplier.class));release.countDown();assertTrue(work.get(1,TimeUnit.SECONDS).success());}
        verify(leases,times(2)).checkCurrent(project);reset(leases);when(raw.exec(eq(project),anyString(),any())).thenReturn(new SandboxPort.CommandResult(true,0,"",""));fenced.exec(project,".codepal/draft/fixture",List.of("npm","run","build"));verify(leases,times(2)).checkCurrent(project);verify(leases,never()).guarded(any(),any(java.util.function.Supplier.class));
    }
}
