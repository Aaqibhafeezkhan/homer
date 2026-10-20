package homer.agentdebug.stubs

import homer.app.infra.FileOperations
import java.io.OutputStream
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.readBytes
import kotlin.io.path.readText

class DebugFileOperations : FileOperations {
    override fun fileSize(path: Path): Long = if (path.exists()) path.fileSize() else 0L

    override fun readText(path: Path): String = if (path.exists()) path.readText() else ""

    override fun fileExists(path: Path): Boolean = path.exists()

    override suspend fun streamFile(
        path: Path,
        outputStream: OutputStream,
        bufferSize: Int,
    ) {
        if (path.exists()) outputStream.write(path.readBytes())
    }

    override fun dirExists(path: Path): Boolean = path.exists() && path.isDirectory()

    override fun listRegularFiles(
        dir: Path,
        limit: Int,
    ): List<Path> {
        if (!dir.exists() || !dir.isDirectory()) return emptyList()
        return dir
            .toFile()
            .walkTopDown()
            .filter { it.isFile }
            .map { it.toPath() }
            .take(limit)
            .toList()
    }

    override fun lastModifiedEpochSecond(path: Path): Long = path.getLastModifiedTime().toInstant().epochSecond
}
