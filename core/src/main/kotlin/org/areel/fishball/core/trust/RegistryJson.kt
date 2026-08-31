package org.areel.fishball.core.trust

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire format for data/source-tiers.json, kept separate from the domain model on purpose.
 *
 * Registry.kt has no serialization dependency, so the resolution rules — where the risk is —
 * stay compilable and testable without a build system. This file is the only part that knows
 * what the file on disk looks like.
 */
private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

@Serializable
private data class RegistryDto(
    val version: Int = 0,
    val publisherPatterns: List<PatternDto> = emptyList(),
    val publishers: List<PublisherDto> = emptyList(),
    val platforms: List<PlatformDto> = emptyList(),
    val ownerGroups: Map<String, List<String>> = emptyMap(),
)

@Serializable
private data class PatternDto(
    val match: String,
    val tier: Tier,
    val displayName: String,
    val explanation: String? = null,
)

@Serializable
private data class PublisherDto(
    val id: String,
    val displayName: String,
    val tier: Tier,
    val explanation: String? = null,
    val domains: List<String> = emptyList(),
    val accounts: List<String> = emptyList(),
    val topics: List<String> = emptyList(),
    val note: String? = null,
)

@Serializable
private data class PlatformDto(
    val domain: String,
    val displayName: String,
    val defaultTier: Tier,
    val verification: String = "none",
    val explanation: String? = null,
    val note: String? = null,
)

fun parseSourceRegistry(text: String): SourceRegistry {
    val dto = json.decodeFromString(RegistryDto.serializer(), text)
    require(dto.publishers.isNotEmpty()) { "source-tiers.json has no publishers" }
    return SourceRegistry(
        version = dto.version,
        publishers = dto.publishers.map {
            Publisher(it.id, it.displayName, it.tier, it.explanation, it.domains, it.accounts, it.topics)
        },
        platforms = dto.platforms.map {
            Platform(it.domain, it.displayName, it.defaultTier, it.verification, it.explanation)
        },
        patterns = dto.publisherPatterns.map {
            PatternRule(it.match, it.tier, it.displayName, it.explanation)
        },
        ownerGroups = dto.ownerGroups,
    )
}

/** Loads the registry bundled as a classpath resource. */
fun loadBundledRegistry(resource: String = "/source-tiers.json"): SourceRegistry {
    val stream = SourceRegistry::class.java.getResourceAsStream(resource)
        ?: error("registry resource not found on classpath: $resource")
    return parseSourceRegistry(stream.bufferedReader().use { it.readText() })
}
