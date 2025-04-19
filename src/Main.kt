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

/***
Key file types:
- regular file
- directory
- symbolic link
- hard link

Solution description:
    - As everything on Linux is file, I think its reasonable to count ALL files INCLUDING DIRECTORIES, SYMLINKS, and the etc.
        - Directoires: I will count the size of a directory file, usually its quite small.
        - Symlink: I will count the size of symlink itself, usually its very small.
        - Regular File: I will count just its size
    - Hard links
        - As hard links are often used in fs I will count them as well. The task says "size that will be freed on the disk", so we have to work out situations where 2 or more hardlink points on the same file, while actual inode(file) is being freed only once.
        - The most popular fs prohibit using (users space)hardlinks on directories. I will not consider cases when somehow hardlink is created on a directory (except . and ..)
    - I assume that files I work with are isolated, and nothing else is using their inodes in any way.

    Example 1:
        Hardlink -> inode 2
        File -> inode 2
    Result:
        fileSized -> {path: hardlink_path, size: inode2_size; path: file_path, size: indode2_size}
        TotalSize -> inode2 size
----------------------------
    Example 2:
        Hardlink_outside of dir -> inode
        File -> inode
    Result:
        fileSized -> path: {file_path, size: inode_size}
        TotalSize -> 0 (Because when the directory is deleted, nothing is freed on the disk)

**/

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


private fun getFileINodeAndSize(path: Path): Result<Triple<Long, Long, Int>> =
    try {
        val inode = getFileINode(path).getOrThrow();

        val size = getActualSize(path).getOrThrow();

        val hardLinkAmount = getHardLinkAmount(path).getOrThrow();

        Result.success(Triple(inode, size, hardLinkAmount))

    }catch(e: Exception) {
        Result.failure(e)
    }


data class FileInfo(val index: Long, val size: Long, val hardLinkAmount: Int)

// closure assepts a path to the file and its type for optimization
private fun walkDir(dir: Path, closure: (Path, FileType) -> Result<Unit>) {
    check(Files.isDirectory(dir))

    Files.list(dir).forEach { path ->
        val fileTypeResult = FileType.getTypeFromPath(path)
        if (fileTypeResult.isSuccess) {

            val fileType = fileTypeResult.getOrElse { return@forEach }

            if (closure(path, fileType).isFailure) { return@forEach }

            if (fileType == FileType.DIRECTORY) {
                walkDir(path, closure)
            }
        } else {
            println("Skipping ${path}: Unable to determine file type")
        }
    }

}

private fun solution(dir: Path): SolutionResult {
    val fileSizes = mutableMapOf<Path, Long>();

    val foundINodes = mutableMapOf<FileInfo, Int>();

    val closure = closure@{ path: Path, fileType: FileType ->
        if (fileType != FileType.OTHER) {
            try {
                val (iNodeIndex, size, hardLinkAmount) = getFileINodeAndSize(path).getOrThrow()

                val file = FileInfo(iNodeIndex, size, hardLinkAmount)
                // as some very popular file system prohibit hard link on directory usage (Apple fs, Windows, ext4) I will consider that hardlink on dir is forbidden

                fileSizes[path] = size

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