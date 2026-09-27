package com.code.pal.agent.dto;

import java.util.List;

/**
 * One entry in a project's workspace file tree. {@code path} is relative to the workspace root
 * with forward slashes; directories carry their {@code children}, files carry {@code size}.
 */
public record FileNode(String name, String path, boolean dir, long size, List<FileNode> children) {
}
