package com.code.pal.agent.workspace;

import com.code.pal.agent.lease.ProjectLeaseService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import java.util.*;
import com.code.pal.agent.contract.UnitWriteScope;

/** Trusted tree/command boundary; raw adapters are implementation beans only. */
@Component
@Primary
public class FencedSandboxPort implements SandboxPort {
    private final SandboxPort sandbox;
    private final ProjectLeaseService leases;

    public FencedSandboxPort(ObjectProvider<DockerProjectEnvironment> docker,
            ObjectProvider<LocalProjectEnvironment> local, ProjectLeaseService leases) {
        SandboxPort adapter = docker.getIfAvailable();
        this.sandbox = adapter == null ? local.getObject() : adapter;
        this.leases = leases;
    }

    public void ensure(UUID p) {
        UnitWriteScope.rejectWorkerIo();
        leases.guarded(p, () -> sandbox.ensure(p));
    }

    public void verifyEnvironment() {
        sandbox.verifyEnvironment();
    }

    public boolean reconcilePendingOperations(UUID p) {
        leases.requireRecovery(p);
        return sandbox.reconcilePendingOperations(p) && !leases.hasPendingIo(p);
    }

    public void enforceQuota(UUID p) {
        leases.checkCurrent(p);
        sandbox.enforceQuota(p);
    }

    public CommandResult exec(UUID p, String r, List<String> c) {
        if (ProjectPaths.normalize(r, true).equals("."))
            throw new IllegalArgumentException("Generated commands require an isolated candidate root");
        UnitWriteScope.rejectWorkerIo();
        leases.checkCurrent(p);
        var result = sandbox.exec(p, r, c);
        leases.checkCurrent(p);
        return result;
    }

    public CommandResult install(UUID p, String r, List<String> c) {
        if (ProjectPaths.normalize(r, true).equals("."))
            throw new IllegalArgumentException("Installs require an isolated dependency root");
        UnitWriteScope.rejectWorkerIo();
        leases.checkCurrent(p);
        var result = sandbox.install(p, r, c);
        leases.checkCurrent(p);
        return result;
    }

    public void link(UUID p, String l, String t) {
        UnitWriteScope.rejectWorkerIo();
        leases.guarded(p, () -> sandbox.link(p, l, t));
    }

    public CommandResult browse(UUID p, String r) {
        UnitWriteScope.rejectWorkerIo();
        leases.checkCurrent(p);
        var result = sandbox.browse(p, r);
        leases.checkCurrent(p);
        return result;
    }

    public CommandResult browse(UUID p, String r, String acceptance) {
        UnitWriteScope.rejectWorkerIo();
        leases.checkCurrent(p);
        var result = sandbox.browse(p, r, acceptance);
        leases.checkCurrent(p);
        return result;
    }

    public CommandResult browseWithApi(UUID p, String r, String acceptance, ServerEndpoint api) {
        UnitWriteScope.rejectWorkerIo();
        leases.checkCurrent(p);
        var result = sandbox.browseWithApi(p, r, acceptance, api);
        leases.checkCurrent(p);
        return result;
    }

    public String commandPath(UUID p, String r) {
        return sandbox.commandPath(p, r);
    }

    public void copyTree(UUID p, String s, String t) {
        UnitWriteScope.rejectWorkerIo();
        leases.guarded(p, () -> sandbox.copyTree(p, s, t));
    }

    public void removeTree(UUID p, String r) {
        UnitWriteScope.rejectWorkerIo();
        leases.guarded(p, () -> sandbox.removeTree(p, r));
    }

    public void restoreTree(UUID p, String r) {
        UnitWriteScope.rejectWorkerIo();
        leases.guarded(p, () -> sandbox.restoreTree(p, r));
    }

    public void destroy(UUID p) {
        UnitWriteScope.rejectWorkerIo();
        leases.guarded(p, () -> sandbox.destroy(p));
    }

    public String databaseAddress() {
        return sandbox.databaseAddress();
    }

    public String databaseAdminUri() {
        return sandbox.databaseAdminUri();
    }

    public ServerEndpoint startServer(UUID p, String n, String r, List<String> c, Map<String, String> e, int port) {
        UnitWriteScope.rejectWorkerIo();
        return leases.guarded(p, () -> sandbox.startServer(p, n, r, c, e, port));
    }

    public ServerEndpoint server(UUID p, String n) {
        return sandbox.server(p, n);
    }

    public void stopServer(UUID p, String n) {
        UnitWriteScope.rejectWorkerIo();
        leases.guarded(p, () -> sandbox.stopServer(p, n));
    }

    public String serverLogs(UUID p, String n, int l) {
        return sandbox.serverLogs(p, n, l);
    }
}
