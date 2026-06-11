import os
from concurrent.futures import Future

class ErrorKind:
    VALIDATION = "VALIDATION"
    DIRECTORY_NOT_FOUND = "DIRECTORY_NOT_FOUND"
    PATH_TRAVERSAL = "PATH_TRAVERSAL"
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

class WriteFileArgs:
    def __init__(self, path: str, baseDir: str, content: str):
        self._path = path
        self._baseDir = baseDir
        self._content = content

    def path(self): return self._path
    def baseDir(self): return self._baseDir
    def content(self): return self._content

class WriteFileTool:
    name = "WriteFile"
    description = "Creates a new file or overwrites an existing file with the provided content. " \
        "Parent directories are created automatically if they don't exist. " \
        "Use EditFile for surgical edits to existing files — this tool replaces the entire file."
    workspace = "filesystem"

    def execute(self, args: WriteFileArgs) -> Future:
        try:
            if not args.baseDir() or not str(args.baseDir()).strip():
                return ToolResults.failAsync(ToolResults.error("baseDir is required", ErrorKind.VALIDATION))
                
            basePath = os.path.abspath(args.baseDir())
            if not os.path.isdir(basePath):
                return ToolResults.failAsync(ToolResults.error("Base directory does not exist", ErrorKind.DIRECTORY_NOT_FOUND))

            filePath = os.path.abspath(os.path.join(basePath, args.path()))
            if not filePath.startswith(basePath):
                return ToolResults.failAsync(ToolResults.error("Path traversal detected: " + args.path(), ErrorKind.PATH_TRAVERSAL))

            content = args.content() if args.content() is not None else ""

            parent = os.path.dirname(filePath)
            if parent and not os.path.exists(parent):
                os.makedirs(parent)

            existed = os.path.exists(filePath)

            with open(filePath, 'w', encoding='utf-8') as f:
                f.write(content)

            lineCount = content.count('\n') + (0 if not content else 1)
            verb = "Overwrote" if existed else "Created"
            
            return ToolResults.okAsync(f"{verb} {args.path()} ({lineCount} lines, {len(content)} bytes)")

        except Exception as e:
            return ToolResults.failAsync(ToolResults.error("IO error: " + str(e), ErrorKind.INTERNAL))
