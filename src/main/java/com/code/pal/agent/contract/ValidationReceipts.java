package com.code.pal.agent.contract;

import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.validation.ValidationCheck;
import com.code.pal.agent.workspace.ProjectFiles;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** Internal durable evidence only; an interrupted/corrupt write can never prove a successful command. */
public final class ValidationReceipts implements ValidationBundles.Receipts {
    public record Stored(String identity, ValidationBundle.CommandReceipt receipt, String hash) {
    }

    private final ProjectFiles files;
    private final ObjectMapper mapper;
    private final ProjectLeaseService leases;
    private final Consumer<ValidationRequest> requireCurrent;

    public ValidationReceipts(ProjectFiles files, ObjectMapper mapper, ProjectLeaseService leases, Consumer<ValidationRequest> requireCurrent) {
        this.files = files;
        this.mapper = mapper;
        this.leases = leases;
        this.requireCurrent = requireCurrent;
    }

    @Override
    public ValidationBundle.CommandReceipt find(ValidationRequest request, String key) {
        requireKey(key);
        requireCurrent.accept(request);
        String root = root(request);
        if (!files.regularFile(request.projectId(), root, key + ".json")) {
            return null;
        }
        try {
            var value = mapper.readValue(new String(files.readBytes(request.projectId(), root, key + ".json", 16_000),
                    StandardCharsets.UTF_8), Stored.class);
            if (!key.equals(value.identity()) || !key.equals(value.receipt().inputHash())
                    || !ContractStore.hash(mapper.writeValueAsString(value.receipt())).equals(value.hash())
                    || value.receipt().status() != ValidationCheck.Outcome.PASS) {
                throw new IllegalStateException("Validation receipt integrity failed");
            }
            return value.receipt();
        } catch (RuntimeException corrupt) {
            throw new IllegalStateException("Validation receipt is not proved", corrupt);
        }
    }

    @Override
    public void save(ValidationRequest request, String key, ValidationBundle.CommandReceipt receipt) {
        requireKey(key);
        if (!key.equals(receipt.inputHash()) || receipt.status() != ValidationCheck.Outcome.PASS) {
            throw new IllegalArgumentException("Only successful bound command evidence can be saved");
        }
        leases.guarded(request.projectId(), () -> {
            requireCurrent.accept(request);
            String hash = ContractStore.hash(mapper.writeValueAsString(receipt));
            String json = mapper.writeValueAsString(new Stored(key, receipt, hash));
            if (json.length() > 16_000) {
                throw new IllegalArgumentException("Compiler evidence exceeds receipt capacity");
            }
            files.writeText(request.projectId(), root(request), key + ".json", json);
        });
    }

    private static String root(ValidationRequest request) {
        return ".codepal/validation-receipts/" + request.runId();
    }

    private static void requireKey(String key) {
        if (key == null || !key.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Invalid compiler evidence key");
        }
    }
}
