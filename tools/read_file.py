import os
from concurrent.futures import Future
from typing import List, Optional

class ErrorKind:
    VALIDATION = "VALIDATION"
    DIRECTORY_NOT_FOUND = "DIRECTORY_NOT_FOUND"
    NOT_A_DIRECTORY = "NOT_A_DIRECTORY"
    PATH_TRAVERSAL = "PATH_TRAVERSAL"
    FILE_NOT_FOUND = "FILE_NOT_FOUND"
    NOT_A_FILE = "NOT_A_FILE"
    INTERNAL = "INTERNAL"

class ToolResults:
    @staticmethod
    def error(message, kind):
        return {"message": message, "kind": kind}

    @staticmethod
    def okAsync(data):
        future = Future()
        future.set_result({"success": True, "data": data})
        return future

    @staticmethod
    def failAsync(error):
        future = Future()
        future.set_result({"success": False, "error": error})
        return future

class ReadFileToolArgs:
    def __init__(self, paths: List[str], baseDir: str, offset: Optional[int] = None, limit: Optional[int] = None):
        self._paths = paths
        self._baseDir = baseDir
        self._offset = offset
        self._limit = limit

    def paths(self): return self._paths
    def baseDir(self): return self._baseDir
    def offset(self): return self._offset
    def limit(self): return self._limit

class ReadFileTool:
    MAX_LINES = 3000
    MAX_LINE_LENGTH = 2000
    
    name = "ReadFiles"
    description = "Reads the contents of multiple files from the provided base directory. " \
        "Returns lines in 'cat -n' format (line numbers prefixed). " \
        "Optional offset (1-based line to start from) and limit (number of lines to read). " \
        "Defaults to reading up to 3000 lines from the start. Lines longer than 2000 characters are truncated."
    workspace = "filesystem"

    def execute(self, args: ReadFileToolArgs) -> Future:
        try:
            paths = args.paths()
            baseDir = args.baseDir()
            offset = args.offset() if args.offset() is not None and args.offset() >= 1 else 1
            limit = min(args.limit(), self.MAX_LINES) if args.limit() is not None and args.limit() >= 1 else self.MAX_LINES

            if not baseDir or not str(baseDir).strip():
                return ToolResults.failAsync(ToolResults.error("baseDir is required", ErrorKind.VALIDATION))

            basePath = os.path.abspath(baseDir)
            if not os.path.exists(basePath):
                return ToolResults.failAsync(ToolResults.error("Base directory does not exist", ErrorKind.DIRECTORY_NOT_FOUND))
            if not os.path.isdir(basePath):
                return ToolResults.failAsync(ToolResults.error("Base path is not a directory", ErrorKind.NOT_A_DIRECTORY))

            results = []

            for relativePath in paths:
                filePath = os.path.abspath(os.path.join(basePath, relativePath))

                if not filePath.startswith(basePath):
                    return ToolResults.failAsync(ToolResults.error("Path traversal detected: " + relativePath, ErrorKind.PATH_TRAVERSAL))

                if not os.path.exists(filePath):
                    return ToolResults.failAsync(ToolResults.error("File not found: " + relativePath, ErrorKind.FILE_NOT_FOUND))

                if not os.path.isfile(filePath):
                    return ToolResults.failAsync(ToolResults.error("Not a file: " + relativePath, ErrorKind.NOT_A_FILE))

                content = self.readFileWithLineNumbers(filePath, offset, limit)
                results.append(content)

            return ToolResults.okAsync(results)

        except Exception as e:
            return ToolResults.failAsync(ToolResults.error("Failed to read files: " + str(e), ErrorKind.INTERNAL))

    def readFileWithLineNumbers(self, filePath: str, offset: int, limit: int) -> str:
        sb = []
        try:
            with open(filePath, 'r', encoding='utf-8') as f:
                lineNumber = 0
                linesEmitted = 0

                while True:
                    line = f.readline()
                    if not line:
                        break
                    
                    if line.endswith('\n'):
                        line = line[:-1]
                        
                    lineNumber += 1
                    if lineNumber < offset:
                        continue
                    if linesEmitted >= limit:
                        sb.append(f"... [output truncated at {limit} lines, use offset to read more]\n")
                        break

                    if len(line) > self.MAX_LINE_LENGTH:
                        line = line[:self.MAX_LINE_LENGTH] + "... [truncated]"

                    sb.append(f"{lineNumber:6d}\t{line}\n")
                    linesEmitted += 1
                    
        except Exception:
            pass
        return "".join(sb)
