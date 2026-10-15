package com.code.pal.agent.change;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class FailedDraftArchiveTest {
    @Test void emptyArchiveIsValidForADeleteOnlyDraft() {
        assertTrue(FailedDraftArchive.unpack(FailedDraftArchive.pack(Map.of()),List.of()).isEmpty());
    }
    @Test void roundTripsSourceAndBinaryWithContentProof() {
        var source=Map.of("src/App.tsx","export default () => 'Todo'".getBytes(StandardCharsets.UTF_8),"public/icon.png",new byte[]{0,1,2});
        byte[] archive=FailedDraftArchive.pack(source);
        var restored=FailedDraftArchive.unpack(archive,proof(source));
        source.forEach((path,bytes)->assertArrayEquals(bytes,restored.get(path)));
    }
    @Test void rejectsSecretsPrivateBriefInternalPathsAndPortableCollisions() {
        for(String path:List.of("../secret","C:/file",".codepal/manifest.json",".env","server/.env.local",".npmrc",".aws/key",
                "node_modules/pkg/index.js","dist/app.js","src/RENDRA.md.bak",".git/config","private.key"))
            assertThrows(IllegalArgumentException.class,()->FailedDraftArchive.pack(Map.of(path,new byte[]{1})),path);
        assertThrows(IllegalArgumentException.class,()->FailedDraftArchive.pack(Map.of("App.tsx",new byte[]{1},"app.tsx",new byte[]{2})));
    }
    @Test void rejectsOversizeAndMissingOrChangedContent() {
        assertThrows(IllegalArgumentException.class,()->FailedDraftArchive.pack(Map.of("large.ts",new byte[10_000_001])));
        var source=Map.of("App.tsx",new byte[]{1});var archive=FailedDraftArchive.pack(source);
        assertThrows(IllegalStateException.class,()->FailedDraftArchive.unpack(archive,List.of()));
        assertThrows(IllegalStateException.class,()->FailedDraftArchive.unpack(archive,proof(Map.of("App.tsx",new byte[]{2}))));
    }
    @Test void rejectsTraversalEntriesAndInflationBeyondPerFileLimit() throws Exception {
        var out=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(out)){zip.putNextEntry(new ZipEntry("../App.tsx"));zip.write(1);zip.closeEntry();}
        assertThrows(IllegalStateException.class,()->FailedDraftArchive.unpack(out.toByteArray(),proof(Map.of("App.tsx",new byte[]{1}))));
        out.reset();
        try(var zip=new ZipOutputStream(out)){zip.putNextEntry(new ZipEntry("App.tsx"));zip.write(new byte[10_000_001]);zip.closeEntry();}
        assertThrows(IllegalStateException.class,()->FailedDraftArchive.unpack(out.toByteArray(),proof(Map.of("App.tsx",new byte[]{0}))));
    }
    static List<FailedDraftArchive.Source> proof(Map<String,byte[]> source) {
        return source.entrySet().stream().map(e->new FailedDraftArchive.Source(e.getKey(),e.getValue().length,"sha256:"+FailedDraftArchive.hash(e.getValue()))).toList();
    }
}
