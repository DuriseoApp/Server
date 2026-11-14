package com.duriseo.server.support

import java.awt.Color
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** 프로필 사진 테스트용 이미지를 코드로 만든다 (파일 리소스 없음). EXIF는 작은 고정 바이트를 직접 만들어 JPEG에 끼운다 */
object TestImages {

	val RED = Color(255, 0, 0)
	val GREEN = Color(0, 255, 0)
	val BLUE = Color(0, 0, 255)
	val YELLOW = Color(255, 255, 0)

	fun image(width: Int, height: Int, type: Int = BufferedImage.TYPE_INT_RGB, paint: (Graphics2D) -> Unit = {}): BufferedImage {
		val image = BufferedImage(width, height, type)
		val g = image.createGraphics()
		try {
			paint(g)
		} finally {
			g.dispose()
		}
		return image
	}

	/** 한 가지 색으로 채운 이미지 */
	fun solid(width: Int, height: Int, color: Color = BLUE): BufferedImage = image(width, height) {
		it.color = color
		it.fillRect(0, 0, width, height)
	}

	/** 네 칸이 서로 다른 색: 왼쪽 위 빨강, 오른쪽 위 초록, 왼쪽 아래 파랑, 오른쪽 아래 노랑 */
	fun quadrants(width: Int, height: Int): BufferedImage = image(width, height) {
		val halfW = width / 2
		val halfH = height / 2
		it.color = RED
		it.fillRect(0, 0, halfW, halfH)
		it.color = GREEN
		it.fillRect(halfW, 0, width - halfW, halfH)
		it.color = BLUE
		it.fillRect(0, halfH, halfW, height - halfH)
		it.color = YELLOW
		it.fillRect(halfW, halfH, width - halfW, height - halfH)
	}

	fun jpeg(image: BufferedImage): ByteArray = encode(image, "jpg")

	fun png(image: BufferedImage): ByteArray = encode(image, "png")

	fun gif(image: BufferedImage): ByteArray = encode(image, "gif")

	private fun encode(image: BufferedImage, format: String): ByteArray {
		val out = ByteArrayOutputStream()
		check(ImageIO.write(image, format, out)) { "no writer for $format" }
		return out.toByteArray()
	}

	fun decode(bytes: ByteArray): BufferedImage = checkNotNull(ImageIO.read(ByteArrayInputStream(bytes))) { "not a decodable image" }

	/** JPEG 파일의 시작 바이트(SOI)인지 */
	fun isJpeg(bytes: ByteArray): Boolean =
		bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()

	/** (x, y) 픽셀이 [RED], [GREEN], [BLUE], [YELLOW] 중 어느 색에 가장 가까운지 */
	fun nearestColor(image: BufferedImage, x: Int, y: Int): Color {
		val rgb = Color(image.getRGB(x, y))
		return listOf(RED, GREEN, BLUE, YELLOW, Color.WHITE, Color.BLACK).minBy { candidate ->
			val dr = candidate.red - rgb.red
			val dg = candidate.green - rgb.green
			val db = candidate.blue - rgb.blue
			dr * dr + dg * dg + db * db
		}
	}

	/**
	 * JPEG에 EXIF(APP1)를 끼운다: Orientation [orientation]과, [withGps]면 위치(GPS) 폴더 하나. 빅 엔디안 TIFF.
	 * APP0(JFIF)가 있으면 그 뒤에 넣는다.
	 */
	fun withExif(jpeg: ByteArray, orientation: Int, withGps: Boolean = false): ByteArray {
		val tiff = ByteArrayOutputStream()
		fun u16(value: Int) { tiff.write(value shr 8 and 0xFF); tiff.write(value and 0xFF) }
		fun u32(value: Int) { u16(value ushr 16); u16(value and 0xFFFF) }
		tiff.write("MM".toByteArray(Charsets.US_ASCII))
		u16(0x002A)
		u32(8) // 첫 폴더(IFD0) 위치
		val entries = if (withGps) 2 else 1
		u16(entries)
		u16(0x0112); u16(3); u32(1); u16(orientation); u16(0) // Orientation (SHORT)
		if (withGps) {
			u16(0x8825); u16(4); u32(1); u32(8 + 2 + entries * 12 + 4) // GPS 폴더 위치
		}
		u32(0) // 다음 폴더 없음
		if (withGps) {
			u16(1) // GPS 폴더: 항목 하나 (GPSLatitudeRef = "N")
			u16(0x0001); u16(2); u32(2); tiff.write('N'.code); tiff.write(0); u16(0)
			u32(0)
		}
		val payload = "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII) + tiff.toByteArray()
		val segment = ByteArrayOutputStream()
		segment.write(0xFF); segment.write(0xE1)
		val length = payload.size + 2
		segment.write(length shr 8 and 0xFF); segment.write(length and 0xFF)
		segment.write(payload)

		var insertAt = 2 // SOI 다음
		if (jpeg[2] == 0xFF.toByte() && jpeg[3] == 0xE0.toByte()) {
			insertAt = 4 + ((jpeg[4].toInt() and 0xFF) shl 8 or (jpeg[5].toInt() and 0xFF))
		}
		return jpeg.copyOfRange(0, insertAt) + segment.toByteArray() + jpeg.copyOfRange(insertAt, jpeg.size)
	}

	/** JPEG 파일의 마커 중 APP1(EXIF·XMP)이 있는지. 메타가 남지 않았는지 확인한다 */
	fun hasApp1Segment(jpeg: ByteArray): Boolean {
		var i = 2
		while (i + 3 < jpeg.size && jpeg[i] == 0xFF.toByte()) {
			val marker = jpeg[i + 1].toInt() and 0xFF
			if (marker == 0xE1) return true
			if (marker == 0xDA) return false // 압축 데이터 시작
			i += 2 + ((jpeg[i + 2].toInt() and 0xFF) shl 8 or (jpeg[i + 3].toInt() and 0xFF))
		}
		return false
	}
}
