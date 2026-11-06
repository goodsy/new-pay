package com.paycore.simulator.acquirer.adapter.integration

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/**
 * 업로드 쓰기 채널의 크기 상한(ACQUIRING-001 2-E rev 0.9 — 재리뷰 m-2). [AcquirerSftpPolicy.openFile]이 쓰기로 연 채널을 이것으로 감싼다: 쓰기 끝
 * (현재 위치 + 남은 바이트)이 [maxBytes]를 넘으면 그 쓰기를 거부한다. SFTP WRITE 요청뿐 아니라 copy-data 같은 서버 안 복사 확장도 sshd `FileHandle`의
 * 이 채널(position → write)을 거치므로 같은 상한을 받는다 — 쓰기 이벤트(`SftpEventListener.writing`)에만 기대지 않는다.
 */
class BoundedUploadChannel(private val delegate: SeekableByteChannel, private val maxBytes: Long) : SeekableByteChannel {

    override fun write(src: ByteBuffer): Int {
        if (delegate.position() + src.remaining() > maxBytes) throw IOException("upload exceeds the acquirer file size limit")
        return delegate.write(src)
    }

    override fun read(dst: ByteBuffer): Int = delegate.read(dst)

    override fun position(): Long = delegate.position()

    override fun position(newPosition: Long): SeekableByteChannel {
        delegate.position(newPosition)
        return this
    }

    override fun size(): Long = delegate.size()

    override fun truncate(size: Long): SeekableByteChannel {
        delegate.truncate(size)
        return this
    }

    override fun isOpen(): Boolean = delegate.isOpen

    override fun close() = delegate.close()
}
