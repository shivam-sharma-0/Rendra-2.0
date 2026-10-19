package com.code.pal.testsupport;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Starts tiny probe JVMs without inheriting the larger test fork's memory reservation. */
public final class IsolatedTestJvm {
    private IsolatedTestJvm() {}

    public static ProcessBuilder command(String... arguments) {
        var command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xms16m", "-Xmx96m", "-XX:MaxMetaspaceSize=96m",
                "-XX:ReservedCodeCacheSize=32m", "-XX:CompressedClassSpaceSize=32m"));
        command.addAll(List.of(arguments));
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("JDK_JAVA_OPTIONS");
        builder.environment().remove("_JAVA_OPTIONS");
        return builder;
    }
}
