package com.duriseo.server.storage

import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.io.IOException
import javax.imageio.ImageIO
import javax.imageio.ImageReader

/**
 * 공지 이미지 검사 (API.md 8.2, D-181). 프로필 사진과 달리 다시 인코딩하지 않는다: 형식을 판정하고 실제로 디코드가 되는지만 확인하고
 * 원본 바이트를 그대로 저장하게 한다(투명 PNG·WebP를 지키려고).
 * 형식은 `Content-Type`·확장자가 아니라 실제 디코더로 정한다. PNG·JPEG·WebP가 아니거나 디코드에 실패하면 415 `IMAGE_TYPE_NOT_ALLOWED`다.
 */
@Component
class NoticeImageInspector {

	init {
		// WebP(TwelveMonkeys) 플러그인을 실행 jar에서도 찾게 한다
		ImageIO.scanForPlugins()
	}

	/** [bytes]의 형식에 맞는 확장자(`png`·`jpg`·`webp`)를 돌려준다 */
	fun inspect(bytes: ByteArray): String {
		if (bytes.isEmpty()) throw notAllowed("image is empty")
		val input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)) ?: throw notAllowed("not a PNG, JPEG or WebP image")
		input.use {
			val readers = ImageIO.getImageReaders(input)
			while (readers.hasNext()) {
				val reader = readers.next()
				val extension = extensionOf(reader)
				if (extension == null) {
					reader.dispose()
					continue
				}
				try {
					decode(reader, input)
					return extension
				} finally {
					reader.dispose()
				}
			}
		}
		throw notAllowed("not a PNG, JPEG or WebP image")
	}

	private fun decode(reader: ImageReader, input: javax.imageio.stream.ImageInputStream) {
		try {
			reader.setInput(input, false, true)
			val width = reader.getWidth(0)
			val height = reader.getHeight(0)
			if (width <= 0 || height <= 0) throw notAllowed("image has no size")
			// 1MB 안에 들어가도 압축률이 높은 이미지가 메모리를 크게 쓰지 못하게 디코드 전에 픽셀 수를 본다
			if (width.toLong() * height > MAX_PIXELS) throw ApiException(ErrorCode.IMAGE_TOO_LARGE, "image has more than 25MP")
			reader.read(0) // 실제 디코드. 깨진 파일은 여기서 실패한다
		} catch (e: ApiException) {
			throw e
		} catch (e: IOException) {
			throw notAllowed("image cannot be decoded")
		} catch (e: RuntimeException) {
			throw notAllowed("image cannot be decoded")
		}
	}

	private fun extensionOf(reader: ImageReader): String? {
		val names = reader.originatingProvider?.formatNames?.map { it.lowercase() }.orEmpty()
		return when {
			"png" in names -> "png"
			"jpeg" in names || "jpg" in names -> "jpg"
			"webp" in names -> "webp"
			else -> null
		}
	}

	private fun notAllowed(message: String) = ApiException(ErrorCode.IMAGE_TYPE_NOT_ALLOWED, message)

	private companion object {
		const val MAX_PIXELS = 25_000_000L
	}
}
