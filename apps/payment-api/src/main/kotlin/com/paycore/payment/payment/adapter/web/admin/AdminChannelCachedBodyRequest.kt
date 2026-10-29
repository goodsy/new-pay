package com.paycore.payment.payment.adapter.web.admin

import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader

/** 서명 검증에 쓴 본문 바이트를 다음 단계(컨트롤러)가 그대로 다시 읽게 하는 요청 wrapper — 서명한 바이트 = 처리하는 바이트. 읽을 때마다 처음부터. */
class AdminChannelCachedBodyRequest(request: HttpServletRequest, private val body: ByteArray) : HttpServletRequestWrapper(request) {

    override fun getInputStream(): ServletInputStream {
        val source = ByteArrayInputStream(body)
        return object : ServletInputStream() {
            override fun read(): Int = source.read()

            override fun read(b: ByteArray, off: Int, len: Int): Int = source.read(b, off, len)

            override fun isFinished(): Boolean = source.available() == 0

            override fun isReady(): Boolean = true

            override fun setReadListener(listener: ReadListener?) = throw UnsupportedOperationException("asynchronous read is not supported")
        }
    }

    override fun getReader(): BufferedReader = BufferedReader(InputStreamReader(ByteArrayInputStream(body), Charsets.UTF_8))

    override fun getContentLength(): Int = body.size

    override fun getContentLengthLong(): Long = body.size.toLong()
}
