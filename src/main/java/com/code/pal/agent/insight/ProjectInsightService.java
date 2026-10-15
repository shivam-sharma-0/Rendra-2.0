package com.code.pal.agent.insight;

import com.code.pal.agent.domain.RunStatus;
import com.code.pal.agent.dto.FileContentResponse;
import com.code.pal.agent.dto.FileNode;
import com.code.pal.agent.dto.ProjectAnalytics;
import com.code.pal.agent.dto.SecurityFinding;
import com.code.pal.agent.dto.SecurityScanResult;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.workspace.WorkspaceBrowser;
import com.code.pal.message.repository.MessageRepository;
import com.code.pal.project.dto.ProjectResponse;
import com.code.pal.project.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Read-only project insights computed from real data: activity aggregates (runs/messages/files/tokens)
 * and a lightweight static security scan over the workspace files. No external services, no fake data.
 */
@Service
@RequiredArgsConstructor
public class ProjectInsightService {

    private static final int MAX_SCAN_FILES = 150;

    /** Extensions worth scanning for secrets/insecure patterns (source + config). */
    private static final List<String> SCANNABLE = List.of(
            ".ts", ".tsx", ".js", ".jsx", ".java", ".py", ".rb", ".go", ".php", ".env", ".yml", ".yaml",
            ".json", ".properties", ".sh", ".txt", ".md", ".html", ".css", ".sql", ".xml");

    private record Rule(Pattern pattern, String severity, String title) {}

    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("-----BEGIN (?:RSA |EC |OPENSSH |DSA |PGP )?PRIVATE KEY-----"), "HIGH", "Private key committed in the workspace"),
            new Rule(Pattern.compile("AKIA[0-9A-Z]{16}"), "HIGH", "AWS access key ID found"),
            new Rule(Pattern.compile("(?i)(?:api[_-]?key|secret|password|passwd|token|access[_-]?key)\\s*[:=]\\s*[\"'][^\"'\\s]{8,}[\"']"), "MEDIUM", "Hard-coded credential"),
            new Rule(Pattern.compile("\\beval\\s*\\("), "MEDIUM", "Use of eval()"),
            new Rule(Pattern.compile("dangerouslySetInnerHTML"), "LOW", "Raw HTML injection (dangerouslySetInnerHTML)"),
            new Rule(Pattern.compile("http://(?!localhost|127\\.0\\.0\\.1)"), "LOW", "Insecure http:// URL")
    );

    private final ProjectService projects;
    private final AgentRunRepository runs;
    private final MessageRepository messages;
    private final WorkspaceBrowser browser;
    private final com.code.pal.agent.usage.DailyTokenBudget budget;

    public ProjectAnalytics analytics(Long userId, UUID projectId) {
        ProjectResponse p = projects.get(userId, projectId); // authorizes (accessible)
        int fileCount = countFiles(browser.tree(projectId));
        var usage=budget.projectUsage(projectId);
        return new ProjectAnalytics(
                runs.countByProjectId(projectId),
                runs.countByProjectIdAndStatus(projectId, RunStatus.DONE),
                runs.countByProjectIdAndStatus(projectId, RunStatus.FAILED),
                messages.countByProjectId(projectId),
                usage.chargedTokens(),
                fileCount,
                p.deploymentStatus().name(),
                p.createdAt(),
                p.lastActivityAt(),usage.reportedTokens(),usage.unknownTokens(),usage.reservedTokens(),usage.unknownCalls(),
                usage.actualAttempts(),usage.logicalCalls(),usage.historicalAttemptRecords());
    }

    public SecurityScanResult securityScan(Long userId, UUID projectId) {
        projects.requireAccessible(userId, projectId);
        List<String> paths = new ArrayList<>();
        collectFiles(browser.tree(projectId), paths);

        List<SecurityFinding> findings = new ArrayList<>();
        int scanned = 0;
        for (String path : paths) {
            if (scanned >= MAX_SCAN_FILES) break;
            if (!isScannable(path)) continue;
            FileContentResponse file;
            try {
                file = browser.read(projectId, path);
            } catch (RuntimeException ex) {
                continue;
            }
            if (file.binary()) continue;
            scanned++;
            scanContent(path, file.content(), findings);
        }

        int high = (int) findings.stream().filter(f -> f.severity().equals("HIGH")).count();
        int medium = (int) findings.stream().filter(f -> f.severity().equals("MEDIUM")).count();
        int low = (int) findings.stream().filter(f -> f.severity().equals("LOW")).count();
        return new SecurityScanResult(scanned, high, medium, low, findings);
    }

    private void scanContent(String path, String content, List<SecurityFinding> out) {
        for (Rule rule : RULES) {
            var matcher = rule.pattern().matcher(content);
            if (matcher.find()) {
                int line = (int) content.substring(0, matcher.start()).chars().filter(c -> c == '\n').count() + 1;
                out.add(new SecurityFinding(rule.severity(), rule.title(), path, "Line " + line));
            }
        }
    }

    private static boolean isScannable(String path) {
        String lower = path.toLowerCase();
        for (String ext : SCANNABLE) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    private static int countFiles(List<FileNode> nodes) {
        int total = 0;
        for (FileNode node : nodes) {
            if (node.dir()) total += node.children() != null ? countFiles(node.children()) : 0;
            else total++;
        }
        return total;
    }

    private static void collectFiles(List<FileNode> nodes, List<String> out) {
        for (FileNode node : nodes) {
            if (node.dir()) {
                if (node.children() != null) collectFiles(node.children(), out);
            } else {
                out.add(node.path());
            }
        }
    }
}
