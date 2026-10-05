// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.appports.badjavanio.sample.application

import java.nio.file.Files
import java.nio.file.Path

/** 파일(정산 파일 등)을 Port 없이 application에서 직접 읽음. */
class DirectFileService {
    fun read(path: String): List<String> = Files.readAllLines(Path.of(path))
}
