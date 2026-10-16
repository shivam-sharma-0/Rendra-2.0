package com.code.pal.agent.lease;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.workspace.WorkspaceStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ProjectMutationLocksTest {
    @TempDir Path directory;
    @Test void aStoppedRunStopsWaitingForABusyLock() throws Exception {
        var p=new AgentProperties();p.setWorkspaceBasePath(directory.resolve("workspace").toString());p.setMutationLockPath(directory.resolve("stop-locks").toString());
        var locks=new ProjectMutationLocks(new WorkspaceStorage(p),p);var project=UUID.randomUUID();
        var held=new java.util.concurrent.CompletableFuture<Void>();var release=new java.util.concurrent.CountDownLatch(1);
        var owner=new Thread(()->{try(var scope=locks.acquire(project)){held.complete(null);release.await();}catch(Exception e){held.completeExceptionally(e);}});
        owner.start();held.get(5,TimeUnit.SECONDS);
        var cancellation=new com.code.pal.agent.runtime.RunCancellation(UUID.randomUUID());
        long started=System.nanoTime();
        var waiter=java.util.concurrent.CompletableFuture.supplyAsync(()->{try(var bound=com.code.pal.agent.runtime.RunCancellation.bind(cancellation)){locks.acquire(project).close();return "acquired";}
            catch(com.code.pal.agent.llm.LlmCancelledException stopped){return "stopped";}});
        Thread.sleep(300);cancellation.cancel();
        try{assertEquals("stopped",waiter.get(5,TimeUnit.SECONDS));assertTrue(System.nanoTime()-started<TimeUnit.SECONDS.toNanos(5),"Stop must not wait out the lock timeout");}
        finally{release.countDown();owner.join(5_000);}
    }
    @Test void configuredLocalLocksAreIndependentOfTheWorkspaceStorage() throws Exception {
        var p=new AgentProperties();p.setWorkspaceBasePath(directory.resolve("workspace").toString());p.setMutationLockPath(directory.resolve("local-locks").toString());var project=UUID.randomUUID();
        try(var held=new ProjectMutationLocks(new WorkspaceStorage(p),p).acquire(project)){assertTrue(Files.isRegularFile(directory.resolve("local-locks").resolve(project+".lock")));assertFalse(Files.exists(directory.resolve("workspace")));}
    }
    @Test void aSeparateJvmCannotTakeTheFilesystemLockUntilItsOwnerReleases() throws Exception {
        var p=new AgentProperties();p.setWorkspaceBasePath(directory.toString());var storage=new WorkspaceStorage(p);
        var locks=new ProjectMutationLocks(storage);var project=UUID.randomUUID();
        String java=Path.of(System.getProperty("java.home"),"bin","java").toString();
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        Path lock=directory.resolve(".project-mutation-locks").resolve(project+".lock");
        try(var held=locks.acquire(project)) {assertEquals(2,probe(java,classpath,lock));}
        assertEquals(0,probe(java,classpath,lock));assertTrue(Files.isRegularFile(lock),"the lock inode must remain stable");
    }
    private int probe(String java,String classpath,Path file) throws Exception {
        var process=new ProcessBuilder(java,"-cp",classpath,Probe.class.getName(),file.toString()).redirectErrorStream(true).start();
        try {assertTrue(process.waitFor(5,TimeUnit.SECONDS));return process.exitValue();}
        finally {if(process.isAlive()) process.destroyForcibly();}
    }
    public static class Probe {
        public static void main(String[] args) throws Exception {
            try(var channel=FileChannel.open(Path.of(args[0]),StandardOpenOption.WRITE);var lock=channel.tryLock()) {
                System.exit(lock==null?2:0);
            }
        }
    }
}
