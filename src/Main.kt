import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.system.exitProcess

fun main(vararg args: String) {

    val dir = try {
        Path.of(args[0])
    } catch (_: Exception) {
        println("The target directory must be specified as the only argument of the application")
        exitProcess(1)
    }

    check(Files.exists(dir))
    val result = solution(dir)
    for ((path, size) in result.fileSizes.entries.sortedBy { it.key }) {
        println("$path: $size")
    }
    println("Total: ${result.totalSize}")
}


class SolutionResult(
    val fileSizes: Map<Path, Long>,
    val totalSize: Long,
)

/**
 * Key file types handled:
 * - Regular files: Standard data files with content.
 * - Directories: File system containers that hold other files and directories.
 * - Symbolic links: Special files that point to other files or directories.
 * - Hard links: Multiple directory entries pointing to the same inode.
 *
 * Sparse files are not specially handled in the current implementation as calculating their actual
 * disk usage requires additional system commands. A potential implementation approach would be:
 * - For each file, compare: logical size vs actual blocks used (via `du -h file * block_size`)
 * - Return the smaller of these values as the true disk usage
 *
 * Solution approach:
 * - Since everything in Linux is represented as a file, this implementation counts ALL file types:
 *   - Directories: Counts the size of the directory entry itself (typically small)
 *   - Symbolic links: Counts the size of the link itself, not the target (typically small)
 *   - Regular files: Counts the full file size
 *
 * Hard link handling:
 * - The goal is to calculate "size that will be freed on disk" when files are deleted
 * - When multiple hard links point to the same inode, the actual disk space is only freed
 *   when the last hard link to that inode is deleted
 * - This implementation tracks files by inode to avoid double-counting
 * - Directory hard links (beyond "." and "..") are not considered, as most filesystems
 *   prohibit user-created hard links to directories
 *
 * Assumptions:
 * - Files being processed are isolated, with no external hard links to their inodes
 * - We have appropriate permissions to access all files in the specified directories
 *
 * Examples:
 *
 * Example 1 - Hard links within counted area:
 *
 *   hardlink1 -> inode2
 *
 *   file1 -> inode2
 *
 * Result:
 *
 *   Individual file sizes: {path: hardlink1_path, size: inode2_size; path: file1_path, size: inode2_size}
 *
 *   Total size freed: inode2_size (counted only once)
 *
 * Example 2 - Hard link outside counted area:
 *
 *   external_hardlink -> inode3 (outside target directory)
 *
 *   file2 -> inode3 (inside target directory)
 *
 * Result:
 *
 *   Individual file sizes: {path: file2_path, size: inode3_size}
 *
 *   Total size freed: 0 (because the inode still has an external reference)
 */

/// An optimization for getting file type
private enum class FileType {
    DIRECTORY,
    REGULAR_FILE,
    SYMBOLIC_LINK,
    OTHER;


    // as I understood the way to create static method for class
    companion object {
        fun getTypeFromPath(path: Path): Result<FileType> {
            return try {
                Result.success(
                    when {
                        Files.isSymbolicLink(path) -> SYMBOLIC_LINK
                        Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> DIRECTORY
                        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> REGULAR_FILE
                        else -> OTHER
                    }
                )
            } catch (e: Exception) {
                println("Error determining file type for $path: ${e.message}")
                Result.failure(e)
            }
        }
    }
}

/// Gets files inode
private fun getFileINode(path: Path): Result<Long> {
    return try {
        val inode = Files.getAttribute(path, "unix:ino", LinkOption.NOFOLLOW_LINKS)
        if (inode is Long) {
            Result.success(inode)
        } else {
            Result.failure(IllegalStateException("Couldnt create a long value from inode: $inode"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

/// get actual file size (does not follow symlink)
private fun getActualSize(path: Path): Result<Long> {
    return if (Files.isSymbolicLink(path)) {
        try {
            // as File.size private function follows symlink
            Result.success(
                Files.readSymbolicLink(path).toString().length.toLong()
            )
        }catch(e: Exception) {
            Result.failure(e)
        }
    } else {
        try {
            Result.success(Files.size(path));
        }catch(e: Exception) {
            Result.failure(e)
        }
    }
}

/// gets total amount on hardlinks to the file
private fun getHardLinkAmount(path: Path): Result<Int> {
    return try {
        val tmp = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)
        if (tmp is Int) {
            Result.success(tmp)
        } else {
            Result.failure(IllegalStateException("Couldnt create a long value from inode: $tmp"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

data class FileInfo(val index: Long, val size: Long, val hardLinkAmount: Int) {
    companion object {
        fun from(path: Path): Result<FileInfo> {
            return try {
                val inode = getFileINode(path).getOrThrow()
                val size = getActualSize(path).getOrThrow()
                val hardLinkAmount = getHardLinkAmount(path).getOrThrow()

                Result.success(FileInfo(inode, size, hardLinkAmount))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}

// closure assepts a path to the file and its type for optimization
private fun walkDir(dir: Path, closure: (Path, FileType) -> Result<Unit>) {
    check(Files.isDirectory(dir))

    try {
        Files.list(dir).forEach { path ->
            val fileTypeResult = FileType.getTypeFromPath(path)
            if (fileTypeResult.isSuccess) {

                val fileType = fileTypeResult.getOrElse { return@forEach }

                if (closure(path, fileType).isFailure) {
                    return@forEach
                }

                if (fileType == FileType.DIRECTORY) {
                    walkDir(path, closure)
                }
            } else {
                println("Skipping ${path}: Unable to determine file type")
            }
        }
    }catch(e: Exception) {
        println("Permission denied for $dir")
    }

}

private fun solution(dir: Path): SolutionResult {
    val fileSizes = mutableMapOf<Path, Long>();

    val foundINodes = mutableMapOf<FileInfo, Int>();

    val closure = closure@{ path: Path, fileType: FileType ->
        if (fileType != FileType.OTHER) {
            try {
                // as some very popular file system prohibit hard link on directory usage (Apple fs, Windows, ext4) I will consider that hardlink on dir is forbidden

                val fileResult = FileInfo.from(path)
                if (fileResult.isFailure) {
                    return@closure Result.failure<Unit>(fileResult.exceptionOrNull()
                        ?: Exception("Unknown error"))
                }

                val file = fileResult.getOrThrow()
                fileSizes[path] = file.size

                if (fileType == FileType.DIRECTORY) {
                    // far not the best design session but for simplicity I will leave it
                    foundINodes[file] = file.hardLinkAmount;

                    return@closure Result.success(Unit);
                }

                foundINodes[file] = foundINodes.getOrDefault(file, 0) + 1

            } catch (e: Exception) {
                println("Error while getting file info: $path: ${e.message}")
                return@closure Result.failure<Unit>(e)
            }
        }

        return@closure Result.failure<Unit>(UnsupportedOperationException("Unknown file type: $path"))

    }

    walkDir(dir, closure);

    //calculate the total size

    val totalSize: Long = foundINodes.entries.fold(0L) { acc, (inode, count) ->
        if (count == inode.hardLinkAmount) {
            // All links to this inode are inside the directory, so its size will be freed
            acc + inode.size
        } else {
            // Some links to this inode are outside the directory, so nothing will be freed
            acc
        }
    }

    return SolutionResult(fileSizes, totalSize);
}