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
        if (relativePath == null || relativePath.isBlank() || relativePath.length() > MAX_PATH_LENGTH
                || relativePath.indexOf('\0') >= 0 || relativePath.startsWith("/") || relativePath.startsWith("\\")) {
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
}
