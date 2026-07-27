plugins {
	alias(libs.plugins.android.library)
	alias(libs.plugins.kotlin.compose)
}

android {
	namespace = "org.jellyfin.androidtv.fork"
	compileSdk = libs.versions.android.compileSdk.get().toInt()

	defaultConfig {
		minSdk = libs.versions.android.minSdk.get().toInt()
	}

	buildFeatures {
		compose = true
	}

	lint {
		lintConfig = file("$rootDir/android-lint.xml")
		abortOnError = false
	}

	testOptions.unitTests.all {
		it.useJUnitPlatform()
	}
}

dependencies {
	// Jellyfin
	implementation(projects.design)
	implementation(projects.preference)
	api(libs.jellyfin.sdk) {
		// Mirrors the flavor switch in :app so both modules resolve the same SDK
		val sdkVersion = findProperty("sdk.version")?.toString()
		when (sdkVersion) {
			"local" -> version { strictly("latest-SNAPSHOT") }
			"snapshot" -> version { strictly("master-SNAPSHOT") }
			"unstable-snapshot" -> version { strictly("openapi-unstable-SNAPSHOT") }
		}
	}

	// Kotlin
	implementation(libs.kotlinx.coroutines)

	// Android(x)
	implementation(libs.androidx.core)
	implementation(libs.bundles.androidx.lifecycle)
	implementation(libs.bundles.androidx.compose)

	// Dependency Injection
	api(libs.bundles.koin)

	// Logging
	implementation(libs.timber)

	// Testing
	testImplementation(libs.kotest.runner.junit5)
	testImplementation(libs.kotest.assertions)
	testImplementation(libs.mockk)
}
