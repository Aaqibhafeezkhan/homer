package homer.app.infra

import java.io.OutputStream
import java.nio.file.Path

/**
 * Interface for file I/O operations
 * Provides abstraction over Path extension functions for testability
 */
interface FileOperations {
    /**
     * Get the size of a file in bytes
     */
    fun fileSize(path: Path): Long

    /**
     * Read the entire content of a file as text
     */
    fun readText(path: Path): String

    /**
     * Check if a file exists
     */
    fun fileExists(path: Path): Boolean

    /**
     * Stream a file's contents to an output stream
     */
    suspend fun streamFile(
        path: Path,
        outputStream: OutputStream,
        bufferSize: Int = 8192,
    )

    /**
     * Check if the given path exists and is a directory.
     */
    fun dirExists(path: Path): Boolean

    /**
     * Recursively list regular files under [dir], returning at most [limit] of them
     * in an unspecified order. The cap bounds the walk so a directory with a huge
     * number of files cannot be materialized in memory all at once.
     */
    fun listRegularFiles(
        dir: Path,
        limit: Int,
    ): List<Path>

    /**
     * Last-modified time of [path] in Unix epoch seconds.
     */
    fun lastModifiedEpochSecond(path: Path): Long
}
