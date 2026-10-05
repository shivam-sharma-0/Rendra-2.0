package com.code.pal.agent.workspace;

/**
 * Path rules for every access to a project's files, whether on the host ({@link HostProjectFiles}) or
 * inside the sandbox ({@link DockerProjectEnvironment}). Paths are project-relative and '/'-separated.
 */
public final class ProjectPaths {

    public static final int MAX_PATH_LENGTH = 512;

    private ProjectPaths() {
    }

    /**
     * Normalizes a project-relative path. Rejects blank, over-long, absolute and NUL-containing paths,
     * {@code ..} anywhere, and the protected names {@code .git}, {@code .secrets} and {@code .ssh}.
     *
     * @param allowRoot whether {@code "."} (the project root itself) is acceptable
     */
    public static String normalize(String relativePath, boolean allowRoot) {
        // ':' never appears in a portable project path; on the Windows backend it would mean a drive
        // ("C:\x") or an NTFS alternate data stream ("file:stream").
        if (relativePath == null || relativePath.isBlank() || relativePath.length() > MAX_PATH_LENGTH
                || relativePath.indexOf('\0') >= 0 || relativePath.indexOf(':') >= 0
                || relativePath.startsWith("/") || relativePath.startsWith("\\")) {
            throw new IllegalArgumentException("Invalid sandbox path");
        }
        String normalized = relativePath.replace('\\', '/');
        for (String part : normalized.split("/")) {
            if (part.equals("..") || part.equalsIgnoreCase(".git") || part.equalsIgnoreCase(".secrets")
                    || part.equalsIgnoreCase(".ssh")) {
                throw new IllegalArgumentException("Protected sandbox path");
            }
        }
        if (!allowRoot && (normalized.equals(".") || normalized.isBlank())) {
            throw new IllegalArgumentException("File path must not be a directory root");
        }
        return normalized.replaceAll("/{2,}", "/");
    }

    /** {@code relativePath} inside {@code relativeRoot}, both normalized; {@code "."} is the project root. */
    public static String combine(String relativeRoot, String relativePath) {
        String root = normalize(relativeRoot, true);
        String path = normalize(relativePath, false);
        return root.equals(".") ? path : root + "/" + path;
    }

    /** Folder for the backend's own state (change sets, history, manifests); never part of the user's project. */
    public static final String INTERNAL = ".codepal";

    /** Whether {@code relativePath} is {@link #INTERNAL} or inside it. */
    public static boolean isInternal(String relativePath) {
        String first = normalize(relativePath, true).replaceFirst("^\\./", "").split("/", 2)[0];
        return first.equalsIgnoreCase(INTERNAL);
    }

    /**
     * A path an agent tool may use: {@link #normalize} rules plus no {@link #INTERNAL} segment, so the
     * model can never read or rewrite staging areas or history. {@code "."} is allowed when {@code allowRoot}.
     */
    public static String agentPath(String relativePath, boolean allowRoot) {
        String normalized = normalize(relativePath, allowRoot);
        for (String part : normalized.split("/")) {
            if (part.equalsIgnoreCase(INTERNAL)) throw new IllegalArgumentException("Protected project path is not available to agent tools");
        }
        String cleaned = normalized.replaceAll("(^|/)\\.(?=/|$)", "$1").replaceAll("/{2,}", "/")
                .replaceAll("^/|/$", "");
        if (cleaned.isEmpty()) {
            if (!allowRoot) throw new IllegalArgumentException("File path must not be a directory root");
            return ".";
        }
        return cleaned;
    }
}
