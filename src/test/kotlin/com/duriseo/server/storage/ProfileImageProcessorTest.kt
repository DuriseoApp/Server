package com.duriseo.server.storage

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.GpsDirectory
import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.config.DuriseoProperties
import com.duriseo.server.support.TestImages
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 프로필 사진 재인코딩 (API.md 8장, NFR-FILE-01, FR-AUTH-07 AC7). 이미지는 모두 코드로 만든다 */
class ProfileImageProcessorTest {

	private val processor = ProfileImageProcessor(DuriseoProperties())

	private fun expectError(code: ErrorCode, block: () -> Unit) {
		val e = kotlin.runCatching(block).exceptionOrNull()
		if (e !is ApiException) throw AssertionError("expected ApiException but was $e")
		assertEquals(code, e.code)
	}

	@Test
	fun `NFR-FILE-01 큰 사진은 긴 변 512px JPEG로 저장한다`() {
		val wide = processor.process(TestImages.jpeg(TestImages.solid(2000, 1000)))
		val tall = processor.process(TestImages.png(TestImages.solid(1000, 3000)))

		assertTrue(TestImages.isJpeg(wide))
		assertTrue(TestImages.isJpeg(tall))
		TestImages.decode(wide).let { assertEquals(512 to 256, it.width to it.height) }
		TestImages.decode(tall).let { assertEquals(171 to 512, it.width to it.height) }
	}

	@Test
	fun `NFR-FILE-01 긴 변이 512px 이하인 사진은 크기를 그대로 두고 JPEG로 다시 인코딩한다`() {
		val result = processor.process(TestImages.png(TestImages.solid(300, 200)))

		assertTrue(TestImages.isJpeg(result))
		TestImages.decode(result).let { assertEquals(300 to 200, it.width to it.height) }
	}

	@Test
	fun `NFR-FILE-01 회전 EXIF를 픽셀에 적용하고 메타를 남기지 않는다`() {
		val base = TestImages.jpeg(TestImages.quadrants(100, 100))
		// EXIF Orientation 1~8별로 (왼쪽 위, 오른쪽 위, 왼쪽 아래, 오른쪽 아래) 색이 어디로 가는지 (EXIF 규격)
		val expected = mapOf(
			1 to listOf(TestImages.RED, TestImages.GREEN, TestImages.BLUE, TestImages.YELLOW),
			2 to listOf(TestImages.GREEN, TestImages.RED, TestImages.YELLOW, TestImages.BLUE),
			3 to listOf(TestImages.YELLOW, TestImages.BLUE, TestImages.GREEN, TestImages.RED),
			4 to listOf(TestImages.BLUE, TestImages.YELLOW, TestImages.RED, TestImages.GREEN),
			5 to listOf(TestImages.RED, TestImages.BLUE, TestImages.GREEN, TestImages.YELLOW),
			6 to listOf(TestImages.BLUE, TestImages.RED, TestImages.YELLOW, TestImages.GREEN),
			7 to listOf(TestImages.YELLOW, TestImages.GREEN, TestImages.BLUE, TestImages.RED),
			8 to listOf(TestImages.GREEN, TestImages.YELLOW, TestImages.RED, TestImages.BLUE),
		)

		expected.forEach { (orientation, colors) ->
			val result = processor.process(TestImages.withExif(base, orientation, withGps = true))
			val image = TestImages.decode(result)
			val actual = listOf(25 to 25, 75 to 25, 25 to 75, 75 to 75).map { (x, y) -> TestImages.nearestColor(image, x, y) }
			assertEquals(colors, actual, "Orientation $orientation")
			assertFalse(TestImages.hasApp1Segment(result), "Orientation $orientation: EXIF segment must be gone")
		}
	}

	@Test
	fun `NFR-FILE-01 회전하면 가로와 세로가 바뀌고 EXIF 위치 정보는 결과에 없다`() {
		val source = TestImages.withExif(TestImages.jpeg(TestImages.quadrants(200, 100)), orientation = 6, withGps = true)
		// 원본에는 Orientation과 GPS 폴더가 있다
		val sourceMeta = ImageMetadataReader.readMetadata(ByteArrayInputStream(source))
		assertNotNull(sourceMeta.getFirstDirectoryOfType(ExifIFD0Directory::class.java))
		assertNotNull(sourceMeta.getFirstDirectoryOfType(GpsDirectory::class.java))

		val result = processor.process(source)

		TestImages.decode(result).let { assertEquals(100 to 200, it.width to it.height) }
		val resultMeta = ImageMetadataReader.readMetadata(ByteArrayInputStream(result))
		assertNull(resultMeta.getFirstDirectoryOfType(ExifIFD0Directory::class.java))
		assertNull(resultMeta.getFirstDirectoryOfType(GpsDirectory::class.java))
	}

	@Test
	fun `NFR-FILE-01 투명한 PNG는 흰 배경 JPEG가 된다`() {
		val png = TestImages.png(
			TestImages.image(100, 100, BufferedImage.TYPE_INT_ARGB) {
				it.color = Color.BLACK
				it.fillRect(0, 0, 50, 100) // 왼쪽 절반은 불투명한 검정, 오른쪽 절반은 완전히 투명
			},
		)

		val result = processor.process(png)

		assertTrue(TestImages.isJpeg(result))
		val image = TestImages.decode(result)
		assertEquals(Color.BLACK, TestImages.nearestColor(image, 20, 50))
		assertEquals(Color.WHITE, TestImages.nearestColor(image, 80, 50))
		val corner = Color(image.getRGB(95, 5))
		assertTrue(corner.red > 250 && corner.green > 250 && corner.blue > 250, "transparent area must be white but was $corner")
	}

	@Test
	fun `FR-AUTH-07 AC7 10MB를 넘으면 413 IMAGE_TOO_LARGE다`() {
		expectError(ErrorCode.IMAGE_TOO_LARGE) { processor.process(ByteArray(ProfileImageProcessor.MAX_BYTES + 1)) }
		expectError(ErrorCode.IMAGE_TOO_LARGE) { processor.requireWithinSizeLimit(ProfileImageProcessor.MAX_BYTES + 1L) }
		processor.requireWithinSizeLimit(ProfileImageProcessor.MAX_BYTES.toLong()) // 정확히 10MB는 통과
	}

	@Test
	fun `FR-AUTH-07 AC7 25MP를 넘으면 413이고 25MP 이하는 받는다`() {
		// 헤더만 읽고 거부한다: 5001 x 5000 = 25,005,000 > 25,000,000
		val over = TestImages.png(TestImages.image(5001, 5000, BufferedImage.TYPE_BYTE_GRAY))
		expectError(ErrorCode.IMAGE_TOO_LARGE) { processor.process(over) }

		val exactly = TestImages.png(TestImages.image(5000, 5000, BufferedImage.TYPE_BYTE_GRAY) {
			it.color = Color.GRAY
			it.fillRect(0, 0, 5000, 5000)
		})
		TestImages.decode(processor.process(exactly)).let { assertEquals(512 to 512, it.width to it.height) }
	}

	@Test
	fun `FR-AUTH-07 AC7 이름만 jpg인 GIF나 깨진 파일은 415다`() {
		expectError(ErrorCode.IMAGE_TYPE_NOT_ALLOWED) { processor.process(TestImages.gif(TestImages.solid(40, 40))) }
		expectError(ErrorCode.IMAGE_TYPE_NOT_ALLOWED) { processor.process("this is not an image".toByteArray()) }
		expectError(ErrorCode.IMAGE_TYPE_NOT_ALLOWED) { processor.process(ByteArray(0)) }
		// PNG 머리만 있고 나머지가 잘린 파일
		val truncatedPng = TestImages.png(TestImages.solid(40, 40)).copyOf(20)
		expectError(ErrorCode.IMAGE_TYPE_NOT_ALLOWED) { processor.process(truncatedPng) }
	}

	@Test
	fun `NFR-FILE-01 처리 동시 실행 수가 1이어도 실패한 처리가 자리를 쥐고 있지 않고 겹친 요청이 모두 끝난다`() {
		val limited = ProfileImageProcessor(DuriseoProperties(image = DuriseoProperties.Image(maxConcurrentProcessing = 1)))
		val good = TestImages.png(TestImages.solid(1500, 1500))
		val bad = "not an image".toByteArray()
		val executor = Executors.newFixedThreadPool(4)
		try {
			val start = CountDownLatch(1)
			val futures = (1..4).map { n ->
				executor.submit<Boolean> {
					start.await()
					repeat(3) {
						// 실패(415)와 성공이 섞여도 자리(permit)가 돌아와야 다음 처리가 시작한다
						runCatching { limited.process(bad) }
						TestImages.isJpeg(limited.process(good))
					}
					n > 0
				}
			}
			start.countDown()
			assertTrue(futures.all { it.get(120, TimeUnit.SECONDS) })
		} finally {
			executor.shutdownNow()
		}
	}
}
