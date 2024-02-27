/*
 * Copyright 2022-2024 Chartboost, Inc.
 * 
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE file.
 */

import org.jfrog.gradle.plugin.artifactory.task.ArtifactoryTask

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
    id("com.jfrog.artifactory")
    id("kotlinx-serialization")
}

repositories {
    google()
    mavenCentral()
    maven("https://cboost.jfrog.io/artifactory/private-chartboost-mediation/") {
        credentials {
            username = System.getenv("JFROG_USER")
            password = System.getenv("JFROG_PASS")
        }
    }
    maven("https://cboost.jfrog.io/artifactory/chartboost-mediation/")
    maven("https://dl-maven-android.mintegral.com/repository/mbridge_android_sdk_oversea")
}

android {
    namespace = "com.chartboost.mediation.mintegraladapter"
    compileSdk = 33

    defaultConfig {
        minSdk = 21
        targetSdk = 33
        // If you touch the following line, don't forget to update scripts/get_rc_version.zsh
        android.defaultConfig.versionName = System.getenv("VERSION_OVERRIDE") ?: "4.16.6.31.0"
        buildConfigField("String", "CHARTBOOST_MEDIATION_MINTEGRAL_ADAPTER_VERSION", "\"${android.defaultConfig.versionName}\"")

        consumerProguardFiles("proguard-rules.pro")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "location"
    productFlavors {
        create("local")
        create("remote")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    "localImplementation"(project(":Helium"))

    // For external usage, please use the following production dependency.
    // You may choose a different release version.
    "remoteImplementation"("com.chartboost:chartboost-mediation-sdk:4.0.0")

    // Partner SDK
    val mintegralVersion = "16.6.31"
    implementation("com.mbridge.msdk.oversea:videojs:$mintegralVersion")
    implementation("com.mbridge.msdk.oversea:mbjscommon:$mintegralVersion")
    implementation("com.mbridge.msdk.oversea:playercommon:$mintegralVersion")
    implementation("com.mbridge.msdk.oversea:reward:$mintegralVersion")
    implementation("com.mbridge.msdk.oversea:videocommon:$mintegralVersion")
    implementation("com.mbridge.msdk.oversea:same:$mintegralVersion")
    implementation("com.mbridge.msdk.oversea:interstitialvideo:$mintegralVersion")
    implementation("com.mbridge.msdk.oversea:mbnative:$mintegralVersion")
    implementation("com.mbridge.msdk.oversea:nativeex:$mintegralVersion")
    implementation("com.mbridge.msdk.oversea:mbbanner:$mintegralVersion")
    implementation("com.mbridge.msdk.oversea:mbbid:$mintegralVersion")

    // Adapter Dependencies
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.4.1")
}

artifactory {
    clientConfig.isIncludeEnvVars = true
    setContextUrl("https://cboost.jfrog.io/artifactory")

    publish {
        repository {
            // If this is a release build, push to the public "chartboost-mediation" artifactory.
            // Otherwise, push to the "private-chartboost-mediation" artifactory.
            val isReleaseBuild = "true" == System.getenv("CHARTBOOST_MEDIATION_IS_RELEASE")
            if (isReleaseBuild) {
                setRepoKey("chartboost-mediation")
            } else {
                setRepoKey("private-chartboost-mediation")
            }
            // Set the environment variables for these to be able to push to artifactory.
            System.getenv("JFROG_USER")?.let {
                setUsername(it)
            }
            System.getenv("JFROG_PASS")?.let {
                setPassword(it)
            }
        }

        defaults {
            publications("MintegralAdapter", "aar")
            setPublishArtifacts(true)
            setPublishPom(true)
        }
    }
}

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("remoteRelease") {
                from(components["remoteRelease"])

                val adapterName = "mintegral"
                groupId = "com.chartboost"
                artifactId = "chartboost-mediation-adapter-$adapterName"
                version =
                    if (project.hasProperty("snapshot")) {
                        android.defaultConfig.versionName + rootProject.ext["SNAPSHOT"]
                    } else {
                        android.defaultConfig.versionName
                    }

                pom {
                    name.set("Chartboost Mediation Adapter Mintegral")
                    description.set("Better monetization. Powered by bidding")
                    url.set("https://www.chartboost.com/mediate/")

                    licenses {
                        license {
                            name.set("https://answers.chartboost.com/en-us/articles/200780239")
                        }
                    }

                    developers {
                        developer {
                            id.set("chartboostmobile")
                            name.set("chartboost mobile")
                            email.set("support@chartboost.com")
                        }
                    }

                    scm {
                        val gitUrl = "https://github.com/ChartBoost/chartboost-mediation-android-adapter-$adapterName"
                        url.set(gitUrl)
                        connection.set(gitUrl)
                        developerConnection.set(gitUrl)
                    }
                }
            }
        }
    }

    tasks.named<ArtifactoryTask>("artifactoryPublish") {
        publications(publishing.publications["remoteRelease"])
    }
}
