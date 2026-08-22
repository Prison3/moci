plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose") version "2.1.0"
    id("com.google.devtools.ksp")
    id("com.google.protobuf")
}

val mociBaseUrl: String = providers.gradleProperty("moci.baseUrl").getOrElse("http://10.0.2.2:5000")
val mociCleartext: Boolean = providers.gradleProperty("moci.cleartext").getOrElse("false").toBoolean()
val mociGrpcPort: Int = providers.gradleProperty("moci.grpcPort").getOrElse("50051").toInt()
val grpcVersion = "1.68.1"
val protobufVersion = "3.25.5"
val repoRoot = rootProject.layout.projectDirectory.dir("..").asFile

fun gitCommitCount(): Int = runCatching {
    providers.exec {
        workingDir(repoRoot)
        commandLine("git", "rev-list", "--count", "HEAD")
    }.standardOutput.asText.get().trim().toInt()
}.getOrElse { 1 }

fun gitDescribe(): String = runCatching {
    providers.exec {
        workingDir(repoRoot)
        commandLine("git", "describe", "--tags", "--always")
    }.standardOutput.asText.get().trim()
}.getOrElse { "dev" }

val mociVersionCode = gitCommitCount()
val mociVersionName = "$mociVersionCode - ${gitDescribe()}"

android {
    namespace = "com.moci.words"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.moci.words"
        minSdk = 26
        targetSdk = 35
        versionCode = mociVersionCode
        versionName = mociVersionName

        buildConfigField("String", "BASE_URL", "\"$mociBaseUrl\"")
        buildConfigField("int", "GRPC_PORT", "$mociGrpcPort")
        manifestPlaceholders["mociCleartext"] = mociCleartext

        ndk {
            // Vosk 提供这些 ABI；模拟器可走 x86_64
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 内部分发：无正式签名时使用 debug 证书
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
            task.plugins {
                create("grpc") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("io.grpc:grpc-okhttp:$grpcVersion")
    implementation("io.grpc:grpc-protobuf-lite:$grpcVersion")
    implementation("io.grpc:grpc-stub:$grpcVersion")
    implementation("javax.annotation:javax.annotation-api:1.3.2")
    // 本地离线语音识别（英文）
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
