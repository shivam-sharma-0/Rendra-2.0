package com.code.pal.agent.lease;

import com.code.pal.agent.workspace.WorkspaceStorage;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.contract.ContractStore;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;

/** An OS file lock survives a DB connection loss during filesystem I/O. Every claimant uses the same
 * trusted shared workspace lock directory, which is never mounted in generated containers. Do not
 * delete lock files: unlinking an in-use inode would create two independent locks for one project.
 */
@Component
public class ProjectMutationLocks {
    private static final ConcurrentMap<Path,ReentrantLock> LOCAL=new ConcurrentHashMap<>();
    /** A run must outwait its own short background copies (draft preview on a slow share) instead of failing. */
    private static final long WAIT_SECONDS=30;
    private final Path directory;
    public ProjectMutationLocks(WorkspaceStorage storage) { this(storage,new AgentProperties()); }
    @Autowired
    public ProjectMutationLocks(WorkspaceStorage storage,AgentProperties props) {
        String configured=props.getMutationLockPath();
        if(configured!=null && !configured.isBlank())directory=Path.of(configured).toAbsolutePath().normalize();
        else if(storage.base().toString().startsWith("\\\\")) {
            String namespace=ContractStore.hash(storage.base().toString().toLowerCase(Locale.ROOT));
            directory=Path.of(System.getProperty("java.io.tmpdir"),"codepal-mutation-locks",namespace).toAbsolutePath().normalize();
        }else directory=storage.base().resolve(".project-mutation-locks");
        if(directory.toString().startsWith("\\\\"))throw new IllegalArgumentException("Mutation lock directory must be host-local; UNC file locks are unsupported");
    }
    public Scope acquire(UUID project) {
        Path path=directory.resolve(project+".lock").toAbsolutePath().normalize();
        ReentrantLock local=LOCAL.computeIfAbsent(path,key->new ReentrantLock(true));
        FileChannel channel=null;
        try {
            if(!local.tryLock(WAIT_SECONDS,TimeUnit.SECONDS)) throw new IllegalStateException("Project mutation is busy");
            try {
                Files.createDirectories(directory);
                if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unsafe mutation lock directory");
                channel=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
                FileLock lock=null;
                while(lock==null && System.nanoTime()<end) {lock=channel.tryLock();if(lock==null) Thread.sleep(10);}
                if(lock==null) throw new IOException("Shared project mutation lock is busy");
                FileChannel owned=channel;FileLock held=lock;
                return ()->{try{held.release();}catch(IOException ignored){}finally{try{owned.close();}catch(IOException ignored){}local.unlock();}};
            } catch(IOException | RuntimeException | InterruptedException failure) {
                if(channel!=null) try{channel.close();}catch(IOException ignored){}local.unlock();throw failure;
            }
        } catch(InterruptedException stopped) {Thread.currentThread().interrupt();throw new LeaseLostException();}
        catch(IOException failed) {throw new IllegalStateException("Shared mutation fencing is unavailable",failed);}
    }
    public interface Scope extends AutoCloseable { @Override void close(); }
}
