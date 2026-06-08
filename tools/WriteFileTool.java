package com.carinae.ai.agent.carinae.tools.impl.Filesystem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;

import com.carinae.ai.agent.carinae.tools.types.ToolDefinitionInterface;
import com.carinae.ai.agent.carinae.tools.types.ToolExecutionInterface;
import com.carinae.ai.agent.carinae.tools.types.toolTransport.ErrorKind;
import com.carinae.ai.agent.carinae.tools.types.toolTransport.ToolResult;
import com.carinae.ai.agent.carinae.tools.types.toolTransport.ToolResults;

import jakarta.validation.constraints.NotBlank;

@ToolDefinitionInterface(
    name = "WriteFile",
    description = "Creates a new file or overwrites an existing file with the provided content. "
        + "Parent directories are created automatically if they don't exist. "
        + "Use EditFile for surgical edits to existing files — this tool replaces the entire file.",
    workspace = "filesystem"
)
public class WriteFileTool implements ToolExecutionInterface<WriteFileTool.WriteFileArgs, String> {

    public static record WriteFileArgs(
        @NotBlank String path,
        @NotBlank String baseDir,
        @NotBlank String content
    ) {}

    @Override
    public CompletableFuture<ToolResult<String>> execute(WriteFileArgs args) {
        try {
            // --- validate baseDir ---
            if (args.baseDir() == null || args.baseDir().isBlank()) {
                return ToolResults.failAsync(ToolResults.error("baseDir is required", ErrorKind.VALIDATION));
            }
            Path basePath = Paths.get(args.baseDir()).toAbsolutePath().normalize();
            if (!Files.isDirectory(basePath)) {
                return ToolResults.failAsync(ToolResults.error("Base directory does not exist", ErrorKind.DIRECTORY_NOT_FOUND));
            }

            // --- resolve & guard target path ---
            Path filePath = basePath.resolve(args.path()).normalize();
            if (!filePath.startsWith(basePath)) {
                return ToolResults.failAsync(ToolResults.error("Path traversal detected: " + args.path(), ErrorKind.PATH_TRAVERSAL));
            }

            String content = args.content() != null ? args.content() : "";

            // --- create parent dirs if needed ---
            Path parent = filePath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            boolean existed = Files.exists(filePath);

            // --- write ---
            Files.writeString(filePath, content);

            long lineCount = content.chars().filter(c -> c == '\n').count() + (content.isEmpty() ? 0 : 1);
            String verb = existed ? "Overwrote" : "Created";
            return ToolResults.okAsync(verb + " " + args.path() + " (" + lineCount + " lines, " + content.length() + " bytes)");

        } catch (IOException e) {
            return ToolResults.failAsync(ToolResults.error("IO error: " + e.getMessage(), ErrorKind.INTERNAL));
        }
    }
}
