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
