package com.duriseo.server.storage

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifDirectoryBase
import com.drew.metadata.exif.ExifIFD0Directory
import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.config.DuriseoProperties
import org.springframework.stereotype.Component
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.Semaphore
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.ImageWriteParam
import javax.imageio.stream.ImageInputStream
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 프로필 사진 재인코딩 (API.md 8장, PRD NFR-FILE-01). 올라온 원본을 긴 변 512px JPEG로 다시 만들고 원본·메타는 버린다.
 *
 * 순서: 크기(10MB) → 헤더로 형식 판정과 픽셀 수(25MP) 확인(디코드 전) → 축소 디코드 → 회전(Orientation) → 투명은 흰 배경 → 512px JPEG.
 * 형식은 Content-Type·확장자가 아니라 실제 디코더가 읽을 수 있는지로 정한다(JPEG·PNG가 아니거나 못 읽으면 415).
 *
 * 메모리: 25MP 전체 디코드는 100MB가 넘으므로 헤더로 크기만 읽고, 디코드는 `ImageReader`의 source subsampling으로 긴 변이
 * 512px 이상 1024px 미만이 되는 정수 비율로 줄여 읽는다. 처리 동시 실행 수는 [DuriseoProperties.Image.maxConcurrentProcessing]로 제한한다.
 * 트랜잭션과 무관하다: 부르는 쪽이 트랜잭션 밖에서 부른다.
 */
@Component
class ProfileImageProcessor(properties: DuriseoProperties) {

	private val quality = properties.image.jpegQuality
	private val permits = Semaphore(max(1, properties.image.maxConcurrentProcessing), true)

	init {
		// Spring Boot 실행 jar에서도 클래스패스의 ImageIO 플러그인(TwelveMonkeys)을 찾게 한다
		ImageIO.scanForPlugins()
	}

	/** [source]를 JPEG(긴 변 512px 이하, 메타 없음)로 돌려준다. 10MB·25MP 초과는 413 `IMAGE_TOO_LARGE`, JPEG·PNG가 아니거나 디코드 실패는 415 `IMAGE_TYPE_NOT_ALLOWED` */
	fun process(source: ByteArray): ByteArray {
		requireWithinSizeLimit(source.size.toLong())
		if (source.isEmpty()) throw notAllowed("image is empty")
		permits.acquire()
		try {
			return reencode(source)
		} finally {
			permits.release()
		}
	}

	/** 원본이 10MB를 넘으면 413 `IMAGE_TOO_LARGE`. 내용을 메모리에 올리기 전에 크기만으로 거부할 때 쓴다 */
	fun requireWithinSizeLimit(size: Long) {
		if (size > MAX_BYTES) throw ApiException(ErrorCode.IMAGE_TOO_LARGE, "image is larger than 10MB")
	}

	private fun reencode(source: ByteArray): ByteArray =
		MemoryCacheImageInputStream(ByteArrayInputStream(source)).use { input ->
			val reader = findReader(input) ?: throw notAllowed("not a JPEG or PNG image")
			try {
				reader.setInput(input, false, true) // 헤더를 읽은 뒤 이어서 디코드한다. 메타데이터는 읽지 않는다
				val (width, height) = try {
					reader.getWidth(0) to reader.getHeight(0)
				} catch (e: IOException) {
					throw notAllowed("image header is unreadable")
				} catch (e: RuntimeException) {
					throw notAllowed("image header is unreadable")
				}
				if (width <= 0 || height <= 0) throw notAllowed("image has no size")
				// 디코드하기 전에 픽셀 수를 본다
				if (width.toLong() * height > MAX_PIXELS) throw ApiException(ErrorCode.IMAGE_TOO_LARGE, "image has more than 25MP")

				val step = max(1, max(width, height) / TARGET_SIDE)
				val param = reader.defaultReadParam
				param.setSourceSubsampling(step, step, 0, 0)
				val decoded = try {
					reader.read(0, param)
				} catch (e: IOException) {
					throw notAllowed("image cannot be decoded")
				} catch (e: RuntimeException) {
					throw notAllowed("image cannot be decoded")
				}
				val orientation = if (isJpeg(reader)) orientationOf(source) else NORMAL_ORIENTATION
				encodeJpeg(render(decoded, orientation))
			} finally {
				reader.dispose()
			}
		}

	/** JPEG·PNG를 읽을 수 있는 디코더. 없으면 null (GIF·WebP·HEIC·깨진 파일 등) */
	private fun findReader(input: ImageInputStream): ImageReader? {
		val readers = ImageIO.getImageReaders(input)
		while (readers.hasNext()) {
			val reader = readers.next()
			val names = reader.originatingProvider?.formatNames?.map { it.lowercase() }.orEmpty()
			if (names.any { it in ALLOWED_FORMATS }) return reader
			reader.dispose()
		}
		return null
	}

	private fun isJpeg(reader: ImageReader): Boolean =
		reader.originatingProvider?.formatNames?.any { it.lowercase() in JPEG_FORMATS } == true

	/** EXIF Orientation(1~8). 없거나 읽을 수 없으면 1 (회전 없음) */
	private fun orientationOf(source: ByteArray): Int =
		try {
			val directory = ImageMetadataReader.readMetadata(ByteArrayInputStream(source))
				.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
			val value = if (directory != null && directory.containsTag(ExifDirectoryBase.TAG_ORIENTATION)) {
				directory.getInt(ExifDirectoryBase.TAG_ORIENTATION)
			} else {
				NORMAL_ORIENTATION
			}
			if (value in 1..8) value else NORMAL_ORIENTATION
		} catch (e: Exception) {
			NORMAL_ORIENTATION
		}

	/** 회전을 적용하고 긴 변 512px 안으로 줄여(작으면 그대로) 흰 배경 RGB 이미지로 그린다. 투명한 부분은 흰색이 된다 */
	private fun render(decoded: BufferedImage, orientation: Int): BufferedImage {
		val sourceWidth = decoded.width
		val sourceHeight = decoded.height
		val swapped = orientation in 5..8
		val orientedWidth = if (swapped) sourceHeight else sourceWidth
		val orientedHeight = if (swapped) sourceWidth else sourceHeight
		val scale = min(1.0, TARGET_SIDE.toDouble() / max(orientedWidth, orientedHeight))
		val targetWidth = max(1, (orientedWidth * scale).roundToInt())
		val targetHeight = max(1, (orientedHeight * scale).roundToInt())

		val output = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
		val graphics = output.createGraphics()
		try {
			graphics.color = Color.WHITE // surface-0
			graphics.fillRect(0, 0, targetWidth, targetHeight)
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
			graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
			graphics.scale(targetWidth.toDouble() / orientedWidth, targetHeight.toDouble() / orientedHeight)
			graphics.transform(orientationTransform(orientation, sourceWidth.toDouble(), sourceHeight.toDouble()))
			graphics.drawImage(decoded, 0, 0, null)
		} finally {
			graphics.dispose()
		}
		return output
	}

	/** EXIF Orientation 값에 맞춰 원본 좌표를 바른 방향의 좌표로 옮기는 변환. [w]·[h]는 원본(디코드한) 크기 */
	private fun orientationTransform(orientation: Int, w: Double, h: Double): AffineTransform =
		when (orientation) {
			2 -> AffineTransform(-1.0, 0.0, 0.0, 1.0, w, 0.0) // 좌우 반전
			3 -> AffineTransform(-1.0, 0.0, 0.0, -1.0, w, h) // 180도
			4 -> AffineTransform(1.0, 0.0, 0.0, -1.0, 0.0, h) // 상하 반전
			5 -> AffineTransform(0.0, 1.0, 1.0, 0.0, 0.0, 0.0) // 대각선 기준 반전
			6 -> AffineTransform(0.0, 1.0, -1.0, 0.0, h, 0.0) // 시계 방향 90도
			7 -> AffineTransform(0.0, -1.0, -1.0, 0.0, h, w) // 반대 대각선 기준 반전
			8 -> AffineTransform(0.0, -1.0, 1.0, 0.0, 0.0, w) // 반시계 방향 90도
			else -> AffineTransform()
		}

	private fun encodeJpeg(image: BufferedImage): ByteArray {
		val writers = ImageIO.getImageWritersByFormatName("jpeg")
		check(writers.hasNext()) { "no JPEG writer is available" }
		val writer = writers.next()
		try {
			val param = writer.defaultWriteParam
			param.compressionMode = ImageWriteParam.MODE_EXPLICIT
			param.compressionQuality = quality
			val bytes = ByteArrayOutputStream()
			MemoryCacheImageOutputStream(bytes).use { output ->
				writer.output = output
				// 메타데이터를 넘기지 않는다: EXIF·위치·ICC 등 원본 메타는 결과에 없다
				writer.write(null, IIOImage(image, null, null), param)
			}
			return bytes.toByteArray()
		} finally {
			writer.dispose()
		}
	}

	private fun notAllowed(message: String) = ApiException(ErrorCode.IMAGE_TYPE_NOT_ALLOWED, message)

	companion object {
		/** 원본 최대 크기 10MB (NFR-FILE-01) */
		const val MAX_BYTES = 10 * 1024 * 1024

		/** 픽셀 수 상한 25MP (D-153). 25,000,000개 */
		const val MAX_PIXELS = 25_000_000L

		/** 결과 긴 변 */
		const val TARGET_SIDE = 512

		private const val NORMAL_ORIENTATION = 1
		private val JPEG_FORMATS = setOf("jpeg", "jpg")
		private val ALLOWED_FORMATS = JPEG_FORMATS + "png"
	}
}
