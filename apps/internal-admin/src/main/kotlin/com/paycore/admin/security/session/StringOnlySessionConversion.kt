package com.paycore.admin.security.session

import org.springframework.core.convert.ConversionService
import org.springframework.core.convert.TypeDescriptor
import org.springframework.core.convert.converter.GenericConverter
import org.springframework.core.convert.support.GenericConversionService

/**
 * 세션 속성 값 변환: `String` ↔ UTF-8 바이트만(§17.5 — 임의 Java 역직렬화 경로 제거). 문자열이 아닌 값을 저장하려 하면 실패한다(설계상 세션에는 문자열만).
 * 읽기는 어떤 바이트든 문자열로만 만든다 — 변조·손상 값은 객체가 되지 않고, 읽는 쪽 형식 검사에서 거부된다.
 */
object StringOnlySessionConversion {

    fun conversionService(): ConversionService = GenericConversionService().apply {
        addConverter(object : GenericConverter {
            override fun getConvertibleTypes() = setOf(GenericConverter.ConvertiblePair(Any::class.java, ByteArray::class.java))

            override fun convert(source: Any?, sourceType: TypeDescriptor, targetType: TypeDescriptor): Any? = when (source) {
                null -> null
                is String -> source.toByteArray(Charsets.UTF_8)
                else -> throw IllegalArgumentException("only String session attributes are stored (got ${source.javaClass.name})")
            }
        })
        addConverter(object : GenericConverter {
            override fun getConvertibleTypes() = setOf(GenericConverter.ConvertiblePair(ByteArray::class.java, Any::class.java))

            override fun convert(source: Any?, sourceType: TypeDescriptor, targetType: TypeDescriptor): Any? = (source as ByteArray?)?.toString(Charsets.UTF_8)
        })
    }
}
