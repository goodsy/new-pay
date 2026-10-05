package com.paycore.payment.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ImportOption
import org.springframework.modulith.core.ApplicationModules
import org.springframework.modulith.core.ModulithMetadata

/**
 * 테스트 소스에 있는 Modulith fixture를 검사하기 위한 ApplicationModules.
 *
 * 공개 factory `ApplicationModules.of(...)`는 테스트 클래스를 import하지 않는다(DoNotIncludeTests 고정, spring-modulith-core 2.1.1).
 * 같은 protected 생성자(metadata + ignored predicate + ImportOption)로 테스트 클래스까지 import한다. ignored predicate에는
 * 운영 검증과 같은 PayCoreArchitectureRules.generatedCode()를 넘긴다. 운영 검증(ArchitectureSpec)은 공개 factory를 쓴다.
 */
internal class TestSourceApplicationModules(basePackage: String, ignored: DescribedPredicate<in JavaClass>) :
    ApplicationModules(ModulithMetadata.of(basePackage), ignored, ImportOption { true })
