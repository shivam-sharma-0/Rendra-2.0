package com.code.pal.agent.change;

import com.code.pal.agent.workspace.ProjectPaths;
import com.code.pal.project.brief.ProjectBriefFile;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import java.security.NoSuchAlgorithmException;

/** Bounded source archive, never a runnable or certified release. Names and bytes are reverified on read. */
public final class FailedDraftArchive {
    public static final int MAX_FILES=256, MAX_FILE_BYTES=10_000_000, MAX_BYTES=10_000_000;
    public static final Set<String> SKIP=Set.of(".git",".ssh",".secrets",".codepal","node_modules","dist","build",".aws",".kube");
    public record Source(String path,long bytes,String version) {}
    public record Change(String path,String before,String after) {}
    public record Manifest(int schemaVersion,UUID runId,UUID projectId,String approvalKey,
                           Map<String,String> liveVersions,List<Source> sources,List<Change> changes) {}
    private FailedDraftArchive() {}
    public static boolean exportable(String path) {
        try {
            String clean=ProjectPaths.agentPath(path,false);
            if(!clean.equals(path) || ProjectBriefFile.privatePath(clean)) return false;
            for(String part:clean.split("/")) {
                String name=part.toLowerCase(Locale.ROOT);
                if(SKIP.contains(name) || name.startsWith(".env") || Set.of(".npmrc",".netrc",".pypirc").contains(name)
                        || name.endsWith(".pem") || name.endsWith(".key") || name.endsWith(".p12")) return false;
            }
            return true;
        } catch(IllegalArgumentException invalid) { return false; }
    }
    public static String hash(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    public static byte[] pack(Map<String,byte[]> files) {
        check(files);
        try {
            var out=new ByteArrayOutputStream();
            try(var zip=new ZipOutputStream(out)) {
                for(var entry:new TreeMap<>(files).entrySet()) {
                    var item=new ZipEntry(entry.getKey());item.setTime(0);zip.putNextEntry(item);zip.write(entry.getValue());zip.closeEntry();
                }
            }
            if(out.size()>11_000_000) throw new IllegalArgumentException("Draft archive exceeds retention limit");
            return out.toByteArray();
        }catch(IOException error){throw new IllegalStateException("Could not retain failed source",error);}
    }
    public static Map<String,byte[]> unpack(byte[] archive,List<Source> sources) {
        if(archive==null || archive.length>11_000_000 || sources==null || sources.size()>MAX_FILES)
            throw new IllegalStateException("Draft archive proof unavailable");
        Map<String,Source> expected=new HashMap<>();
        for(var source:sources) {
            if(source==null || !exportable(source.path()) || source.bytes()<0 || source.bytes()>MAX_FILE_BYTES
                    || expected.put(source.path(),source)!=null) throw new IllegalStateException("Invalid draft manifest");
        }
        Map<String,byte[]> result=new TreeMap<>();long total=0;
        try(var zip=new ZipInputStream(new ByteArrayInputStream(archive))) {
            for(ZipEntry entry;(entry=zip.getNextEntry())!=null;) {
                var proof=expected.get(entry.getName());
                if(entry.isDirectory() || proof==null || result.containsKey(entry.getName())) throw new IllegalStateException("Invalid draft archive entry");
                byte[] bytes=zip.readNBytes(MAX_FILE_BYTES+1);total+=bytes.length;
                if(bytes.length!=proof.bytes() || total>MAX_BYTES || !proof.version().equals("sha256:"+hash(bytes)))
                    throw new IllegalStateException("Draft source proof failed");
                result.put(entry.getName(),bytes);
            }
        }catch(IOException error){throw new IllegalStateException("Draft archive cannot be read",error);}
        if(result.size()!=expected.size()) throw new IllegalStateException("Draft source missing");
        return result;
    }
    private static void check(Map<String,byte[]> files) {
        // A failed delete-only draft may remove every source file; its manifest still retains the deltas.
        if(files.size()>MAX_FILES) throw new IllegalArgumentException("Draft file count exceeds retention limit");
        long total=0;var portable=new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        for(var entry:files.entrySet()) {
            if(!exportable(entry.getKey()) || !portable.add(entry.getKey()) || entry.getValue()==null
                    || entry.getValue().length>MAX_FILE_BYTES || (total+=entry.getValue().length)>MAX_BYTES)
                throw new IllegalArgumentException("Draft exceeds safe source retention limits");
        }
    }
}
