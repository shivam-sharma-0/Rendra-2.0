package com.code.pal.agent.workspace;

import com.code.pal.agent.lease.ProjectLeaseService;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import java.util.*;
import com.code.pal.agent.contract.UnitWriteScope;

/** Production file boundary: all writes serialize against project ownership takeover. */
@Component @Primary
public class FencedProjectFiles implements ProjectFiles {
    private final HostProjectFiles files;
    private final ProjectLeaseService leases;
    public FencedProjectFiles(HostProjectFiles files,ProjectLeaseService leases) { this.files=files;this.leases=leases; }
    public String readText(UUID p,String r,String f){return files.readText(p,r,f);}
    public byte[] readBytes(UUID p,String r,String f,int n){return files.readBytes(p,r,f,n);}
    public byte[] readHead(UUID p,String r,String f,int n){return files.readHead(p,r,f,n);}
    public List<Entry> list(UUID p,String r,int d,Set<String>s){return files.list(p,r,d,s);}
    public boolean exists(UUID p,String r,String f){return files.exists(p,r,f);}
    public boolean regularFile(UUID p,String r,String f){return files.regularFile(p,r,f);}
    public long size(UUID p,String r,String f){return files.size(p,r,f);}
    public String version(UUID p,String r,String f){return files.version(p,r,f);}
    public void writeText(UUID p,String r,String f,String c){UnitWriteScope.rejectWorkerIo();leases.guarded(p,()->files.writeText(p,r,f,c));}
    public void writeBytes(UUID p,String f,byte[]c){UnitWriteScope.rejectWorkerIo();leases.guarded(p,()->files.writeBytes(p,f,c));}
    public void delete(UUID p,String r,String f){UnitWriteScope.rejectWorkerIo();leases.guarded(p,()->files.delete(p,r,f));}
    public void move(UUID p,String r,String a,String b){UnitWriteScope.rejectWorkerIo();leases.guarded(p,()->files.move(p,r,a,b));}
    public void copyFile(UUID p,String r,String a,String t,String b){UnitWriteScope.rejectWorkerIo();leases.guarded(p,()->files.copyFile(p,r,a,t,b));}
}
