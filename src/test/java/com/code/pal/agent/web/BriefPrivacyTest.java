package com.code.pal.agent.web;

import com.code.pal.agent.preview.*;
import com.code.pal.agent.workspace.LocalProject;
import com.code.pal.identity.security.JwtService;
import com.code.pal.project.service.ProjectService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BriefPrivacyTest {
    @ParameterizedTest @ValueSource(strings={"RENDRA.md","nested/RENDRA.md","rendra.md","./RENDRA.md","RENDRA.md.","RENDRA.md.bak"})
    void allStaticAndPathControllersDenyPrivateBrief(String path) {
        var project=LocalProject.create();project.write("index.html","site").write(path,"private");
        var sites=mock(SiteBuildService.class);var published=mock(PublishedSiteService.class);
        when(published.publishedSite(project.projectId)).thenReturn(".codepal/published/test");
        var preview=new PreviewService(project.files,sites,published);
        assertEquals(404,assertThrows(ResponseStatusException.class,()->preview.serve(project.projectId,path)).getStatusCode().value());
        assertThrows(ResponseStatusException.class,()->preview.serveSite(project.projectId,".codepal/site/test",path));
        assertThrows(ResponseStatusException.class,()->preview.servePublished(project.projectId,path));
        var rights=mock(ProjectService.class);when(rights.resolvePublishedProject("test")).thenReturn(project.projectId);
        assertThrows(ResponseStatusException.class,()->new PublicSiteController(rights,preview).serve("test",new MockHttpServletRequest("GET","/p/test/"+path)));
        var jwt=mock(JwtService.class);var claims=mock(io.jsonwebtoken.Claims.class);
        when(jwt.parse("token")).thenReturn(claims);when(jwt.previewProjectId(claims)).thenReturn(project.projectId);
        var grants=mock(com.code.pal.agent.preview.PreviewGrants.class);UUID grantId=UUID.randomUUID();when(jwt.previewGrantId(claims)).thenReturn(grantId);
        when(grants.resolve(grantId)).thenReturn(new com.code.pal.agent.preview.PreviewGrants.Grant(grantId,project.projectId,7L,UUID.randomUUID(),null,java.time.Instant.now().plusSeconds(60)));
        assertThrows(ResponseStatusException.class,()->new PreviewSiteController(jwt,preview,mock(DraftPreviewService.class),grants).serve("token",new MockHttpServletRequest("GET","/preview/token/"+path)));
        assertEquals("site",new String(preview.serve(project.projectId,"index.html").content(),java.nio.charset.StandardCharsets.UTF_8));
    }
}
