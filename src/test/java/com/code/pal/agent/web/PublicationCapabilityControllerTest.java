package com.code.pal.agent.web;
import com.code.pal.agent.preview.PublishedSiteService;
import com.code.pal.project.service.ProjectService;
import com.code.pal.shared.security.AuthPrincipal;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicationCapabilityControllerTest {
    @Test void capabilityRequiresManagementPermissionAndReportsTheSameBackendGate() {
        var projects=mock(ProjectService.class);var sites=mock(PublishedSiteService.class);UUID id=UUID.randomUUID();var actor=new AuthPrincipal(7L,UUID.randomUUID(),"fixture@example.invalid","USER");
        var controller=new PublicationCapabilityController(projects,sites);when(sites.capability(id)).thenReturn(new PublishedSiteService.Capability(false,"API publication unavailable"));
        assertFalse(controller.capability(actor,id).available());verify(projects).requireManageable(7L,id);
        reset(sites);doThrow(new IllegalStateException("Forbidden")).when(projects).requireManageable(7L,id);
        assertThrows(IllegalStateException.class,()->controller.capability(actor,id));verifyNoInteractions(sites);
    }
}
