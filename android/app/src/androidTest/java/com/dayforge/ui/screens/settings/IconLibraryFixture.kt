package com.dayforge.ui.screens.settings

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Independent manifest + platform encoders; no production DTO serialisation as oracle. */
internal object IconLibraryFixture {
    fun id(n: Int) = "9c000000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    val red = 0xffff0000.toInt()
    val green = 0xff00ff00.toInt()
    fun archive(offset: Int = 0, originalColor: Int = red): ByteArray {
        fun packId(n: Int) = id(n + offset)
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val png = try {
            bitmap.eraseColor(originalColor)
            ByteArrayOutputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); it.toByteArray() }
        } finally { bitmap.recycle() }
        fun svg(color: String) = """<svg width="1" height="1"><rect width="1" height="1" fill="$color"/></svg>""".toByteArray()
        val blue = svg("#0000ff"); val green = svg("#00ff00")
        fun blob(bytes: ByteArray, type: String = "image/svg+xml") =
            """{"sha256":"${hash(bytes)}","byte_length":${bytes.size},"media_type":"$type","width":1,"height":1}"""
        val manifest = """{"format":"dayforge.icon-pack","format_version":1,"pack_id":"${packId(10)}","revision":1,
            "name":"Native library pack","assets":[
            {"asset_id":"${packId(11)}","name":"Original PNG","purpose":"general","color_mode":"original","light":${blob(png, "image/png")},"dark":${blob(green)}},
            {"asset_id":"${packId(12)}","name":"Template task","purpose":"task","color_mode":"template","light":${blob(blue)},"dark":${blob(green)}}],
            "roles":{"habit.exercise":"${packId(11)}","task.shopping":"${packId(12)}"},"placeholder_asset_id":"${packId(11)}"}"""
        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                for ((name, bytes) in listOf("manifest.json" to manifest.toByteArray()) +
                    listOf(png, blue, green).map { "blobs/${hash(it)}" to it }) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                }
            }; output.toByteArray()
        }
    }
}
