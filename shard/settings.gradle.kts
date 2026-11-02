pluginManagement {
    // Conventions shared by every module: the `shard.java` plugin (docs/development.md "Building and testing").
    includeBuild("build-logic")
}

rootProject.name = "shard"

// The swarm's shared build cache (scripts/gradle-cache/): on only when SHARD_CACHE_URL is set, which
// scripts/vm-gradle.sh does on boat VMs. Without the variables the build is exactly the local one.
//   SHARD_CACHE_URL, SHARD_CACHE_USER, SHARD_CACHE_PASSWORD   where to read from, and who
//   SHARD_CACHE_PUSH=true                                     also store what this build ran
providers.environmentVariable("SHARD_CACHE_URL").orNull?.let { cacheUrl ->
    buildCache {
        remote<HttpBuildCache> {
            url = uri(cacheUrl)
            credentials {
                username = providers.environmentVariable("SHARD_CACHE_USER").orNull
                password = providers.environmentVariable("SHARD_CACHE_PASSWORD").orNull
            }
            isPush = providers.environmentVariable("SHARD_CACHE_PUSH").orNull == "true"
        }
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // Vanilla's release jars, from Mindustry's GitHub releases (build-logic's Vanilla checks their SHA-256)
        exclusiveContent {
            forRepository {
                ivy {
                    name = "MindustryReleases"
                    url = uri("https://github.com/")
                    patternLayout { artifact("[organisation]/Mindustry/releases/download/[revision]/[module].[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeGroup("Anuken") }
        }
    }
}

// Modules from ARCHITECTURE.md §2. Add yours here and give it a line in build-logic's ModuleRules.
include(":common")
include(":content")
include(":wire")
include(":logic")
include(":sim")
include(":compat")
include(":net")
include(":server")
include(":testkit")
include(":tools:oracle")
include(":tools:indexer")
include(":tools:wirelift")
include(":tools:content-extract")
include(":tools:bots")
include(":tools:replay")
include(":tools:fuzz")
