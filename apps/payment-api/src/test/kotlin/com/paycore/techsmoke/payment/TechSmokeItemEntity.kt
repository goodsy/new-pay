package com.paycore.techsmoke.payment

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.Hibernate

/**
 * TECH-001 JPA smoke 전용 Entity(테스트 소스에만 존재, 업무 의미 없음). 순수 Domain 모델이 아니라 persistence adapter 쪽 Entity의 예다.
 *
 * data class가 아닌 이유:
 * - data class의 equals/hashCode는 모든 필드(name, version 포함)를 비교한다. 같은 행이라도 name을 바꾸면 다른 객체가 되고,
 *   Set/Map에 넣은 뒤 값이 바뀌면 찾을 수 없게 된다. toString/copy도 연관이 생기면 지연 로딩·순환을 일으킬 수 있다.
 * - Hibernate는 지연 로딩을 위해 이 클래스의 하위 클래스(프록시)를 만든다. kotlin-allopen/jpa plugin이 open과 기본 생성자를 만들어 준다.
 *
 * equals/hashCode 선택: 식별자(id) 기준. id는 생성 시 지정되고 바뀌지 않으므로 hashCode가 저장 전후로 안정적이다.
 * 클래스 비교는 [Hibernate.getClass]로 프록시를 실제 Entity 클래스로 풀어 비교한다(프록시와 원본이 같은 행이면 같다).
 * 자동 생성 ID를 쓰는 Entity는 저장 전 id가 null이므로 이 규칙을 그대로 쓰면 안 된다(첫 업무 Entity에서 별도 결정).
 */
@Entity
@Table(name = "tech_smoke_item")
class TechSmokeItemEntity(
    @field:Id
    @field:Column(name = "id", nullable = false, updatable = false)
    val id: Long,

    @field:Column(name = "name", nullable = false, length = 100)
    var name: String,
) {
    /**
     * 낙관적 잠금 버전. null이면 Spring Data가 새 Entity로 판단해 persist(INSERT)한다. INSERT 시 Hibernate가 0을 넣고,
     * UPDATE마다 `... WHERE id = ? AND version = ?` 조건으로 1씩 올린다. 애플리케이션이 직접 바꾸지 않는다.
     */
    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long? = null
        protected set

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        return id == (other as TechSmokeItemEntity).id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "TechSmokeItemEntity(id=$id, version=$version)"
}
