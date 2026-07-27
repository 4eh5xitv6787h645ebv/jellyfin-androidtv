enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "jellyfin-androidtv"

// Application
include(":app")

// Modules
include(":design")
include(":fork") // FORK: all fork-specific code lives here
include(":playback:core")
include(":playback:jellyfin")
include(":playback:media3:exoplayer")
include(":playback:media3:session")
include(":preference")

pluginManagement {
	repositories {
		gradlePluginPortal()
		mavenCentral()
		google()
	}
}

dependencyResolutionManagement {
	repositories {
		mavenCentral()
		google()

		// Jellyfin SDK
		mavenLocal {
			content {
				includeVersionByRegex("org.jellyfin.sdk", ".*", "latest-SNAPSHOT")
			}
		}
		maven("https://s01.oss.sonatype.org/content/repositories/snapshots/") {
			content {
				includeVersionByRegex("org.jellyfin.sdk", ".*", "master-SNAPSHOT")
				includeVersionByRegex("org.jellyfin.sdk", ".*", "openapi-unstable-SNAPSHOT")
			}
		}
		// FORK: upstream's snapshot repo above is s01.oss.sonatype.org, which Sonatype has retired
		// (it 404s). SDK snapshots now live in the Central Portal snapshot repository. This fork
		// needs them because it targets Jellyfin 12, whose API only exists in the unstable SDK.
		maven("https://central.sonatype.com/repository/maven-snapshots/") {
			content {
				includeVersionByRegex("org.jellyfin.sdk", ".*", "master-SNAPSHOT")
				includeVersionByRegex("org.jellyfin.sdk", ".*", "openapi-unstable-SNAPSHOT")
			}
		}
	}
}
