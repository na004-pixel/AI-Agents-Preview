package com.carinae.ai.agent.carinae.tools.impl.Filesystem;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.carinae.ai.agent.carinae.tools.types.ToolDefinitionInterface;
import com.carinae.ai.agent.carinae.tools.types.ToolExecutionInterface;
import com.carinae.ai.agent.carinae.tools.types.toolTransport.ErrorKind;
import com.carinae.ai.agent.carinae.tools.types.toolTransport.ToolResult;
import com.carinae.ai.agent.carinae.tools.types.toolTransport.ToolResults;

import jakarta.validation.constraints.NotBlank;

@ToolDefinitionInterface(
    name = "ReadFiles",
    description = "Reads the contents of multiple files from the provided base directory. "
        + "Returns lines in 'cat -n' format (line numbers prefixed). "
        + "Optional offset (1-based line to start from) and limit (number of lines to read). "
        + "Defaults to reading up to 3000 lines from the start. Lines longer than 2000 characters are truncated.",
    workspace = "filesystem"
)
public class ReadFileTool implements ToolExecutionInterface<ReadFileTool.ReadFileToolArgs, List<String>> {

    private static final int MAX_LINES = 3000;
    private static final int MAX_LINE_LENGTH = 2000;

    public static record ReadFileToolArgs(
        @NotBlank List<String> paths,
        @NotBlank String baseDir,
        Integer offset,
        Integer limit
    ) {}

    @Override
    public CompletableFuture<ToolResult<List<String>>> execute(ReadFileToolArgs args) {
        try {
            List<String> paths = args.paths();
            String baseDir = args.baseDir();
            int offset = (args.offset() != null && args.offset() >= 1) ? args.offset() : 1;
            int limit = (args.limit() != null && args.limit() >= 1) ? Math.min(args.limit(), MAX_LINES) : MAX_LINES;

            if (baseDir == null || baseDir.isBlank()) {
                return ToolResults.failAsync(ToolResults.error("baseDir is required", ErrorKind.VALIDATION));
            }

            Path basePath = Paths.get(baseDir).toAbsolutePath().normalize();
            if (!basePath.toFile().exists()) {
                return ToolResults.failAsync(ToolResults.error("Base directory does not exist", ErrorKind.DIRECTORY_NOT_FOUND));
            }
            if (!basePath.toFile().isDirectory()) {
                return ToolResults.failAsync(ToolResults.error("Base path is not a directory", ErrorKind.NOT_A_DIRECTORY));
            }

            List<String> results = new ArrayList<>();

            for (String relativePath : paths) {
                Path filePath = basePath.resolve(relativePath).normalize();

                if (!filePath.startsWith(basePath)) {
                    return ToolResults.failAsync(ToolResults.error(
                        "Path traversal detected: " + relativePath,
                        ErrorKind.PATH_TRAVERSAL
                    ));
                }

                if (!filePath.toFile().exists()) {
                    return ToolResults.failAsync(ToolResults.error(
                        "File not found: " + relativePath,
                        ErrorKind.FILE_NOT_FOUND
                    ));
                }

                if (!filePath.toFile().isFile()) {
                    return ToolResults.failAsync(ToolResults.error(
                        "Not a file: " + relativePath,
                        ErrorKind.NOT_A_FILE
                    ));
                }

                String content = readFileWithLineNumbers(filePath, offset, limit);
                results.add(content);
            }

            return ToolResults.okAsync(results);

        } catch (Exception e) {
            return ToolResults.failAsync(ToolResults.error("Failed to read files: " + e.getMessage(), ErrorKind.INTERNAL));
        }
    }

    private String readFileWithLineNumbers(Path filePath, int offset, int limit) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = Files.newBufferedReader(filePath)) {
            String line;
            int lineNumber = 0;
            int linesEmitted = 0;

            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (lineNumber < offset) continue;
                if (linesEmitted >= limit) break;

                if (line.length() > MAX_LINE_LENGTH) {
                    line = line.substring(0, MAX_LINE_LENGTH) + "... [truncated]";
                }

                sb.append(String.format("%6d\t%s%n", lineNumber, line));
                linesEmitted++;
            }

            if (linesEmitted >= limit && reader.readLine() != null) {
                sb.append(String.format("... [output truncated at %d lines, use offset to read more]%n", limit));
            }
        }
        return sb.toString();
    }
}
