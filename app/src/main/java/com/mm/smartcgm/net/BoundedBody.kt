package com.mm.smartcgm.net

object BoundedBody {
    fun read(stream: java.io.InputStream, limit: Int = 1_000_000): String {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            require(out.size() + count <= limit) { "Response too large" }
            out.write(buffer, 0, count)
        }
        return out.toString("UTF-8")
    }
}
