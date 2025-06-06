package eu.darken.butler.common.collections

import okio.Buffer
import okio.ByteString
import okio.Sink
import okio.Source
import okio.buffer
import okio.gzip
import okio.use


fun ByteString.fromGzip(): ByteString = Buffer().use { buf ->
    buf.write(this)
    (buf as Source).gzip().buffer().use { it.readByteString() }
}

fun ByteString.toGzip(): ByteString = Buffer().use { buf ->
    (buf as Sink).gzip().buffer().use { it.write(this) }
    buf.use { it.readByteString() }
}