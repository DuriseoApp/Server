package com.duriseo.server.storage

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * 로컬 디스크 저장소 (맥, 개발 서버). [root] 폴더 아래에 키 그대로 경로를 만든다.
 * 쓰기는 같은 폴더의 임시 파일에 쓴 뒤 옮겨 읽는 쪽이 반쯤 쓴 파일을 보지 않게 한다.
 */
class LocalDiskProfileImageStorage(root: Path) : ProfileImageStorage {

	private val root: Path = root.toAbsolutePath().normalize()

	override fun put(key: String, bytes: ByteArray) {
		val target = resolve(key)
		Files.createDirectories(target.parent)
		val temp = target.resolveSibling(".${UUID.randomUUID()}$TEMP_SUFFIX")
		try {
			Files.write(temp, bytes)
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
		} finally {
			Files.deleteIfExists(temp)
		}
	}

	override fun get(key: String): InputStream? =
		try {
			Files.newInputStream(resolve(key))
		} catch (e: NoSuchFileException) {
			null
		}

	override fun delete(key: String) {
		// 빈 사용자 폴더는 남긴다: 지우면 같은 사용자의 동시 업로드(put)와 겹쳐 실패할 수 있다
		Files.deleteIfExists(resolve(key))
	}

	override fun list(prefix: String, action: (StoredObject) -> Unit) {
		val start = root.resolve(prefix.trimEnd('/')).normalize()
		if (!start.startsWith(root) || !Files.isDirectory(start)) return
		Files.walk(start).use { paths ->
			paths.filter { Files.isRegularFile(it) && !it.fileName.toString().endsWith(TEMP_SUFFIX) }.forEach { path ->
				val key = root.relativize(path).joinToString("/")
				if (key.startsWith(prefix)) {
					val modified = try {
						Files.getLastModifiedTime(path).toInstant()
					} catch (e: NoSuchFileException) {
						return@forEach // 훑는 중에 지워짐
					}
					action(StoredObject(key, modified))
				}
			}
		}
	}

	/** 키를 [root] 아래 경로로 바꾼다. 밖으로 나가는 키는 거부한다 (키는 서버가 만들지만 방어한다) */
	private fun resolve(key: String): Path {
		val path = root.resolve(key).normalize()
		require(path.startsWith(root) && path != root) { "storage key escapes the storage root" }
		return path
	}

	private companion object {
		const val TEMP_SUFFIX = ".tmp"
	}
}
