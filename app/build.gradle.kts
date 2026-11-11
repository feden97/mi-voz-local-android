plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val qwenOpenmp = providers.gradleProperty("qwen.openmp")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)
val qwenVulkan = providers.gradleProperty("qwen.vulkan")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)
val qwenOpencl = providers.gradleProperty("qwen.opencl")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)
check(!qwenOpencl.get() && !qwenVulkan.get()) {
    "GPU disabled after a device-wide freeze on the S23 Ultra. Build this version with CPU only."
}
val qwenOpenclSdkDir = providers.gradleProperty("qwen.opencl.sdkDir")
    .orElse(layout.projectDirectory.dir("../build/opencl-sdk").asFile.absolutePath)
val generatedOpenmpJniLibs = layout.buildDirectory.dir("generated/openmpJniLibs")

val copyOpenmpLibs by tasks.registering {
    val sdkDir = providers.environmentVariable("ANDROID_HOME")
        .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
        .orElse("${System.getProperty("user.home")}\\AppData\\Local\\Android\\Sdk")
    inputs.property("qwenOpenmp", qwenOpenmp)
    outputs.dir(generatedOpenmpJniLibs)
    doLast {
        delete(generatedOpenmpJniLibs)
        if (qwenOpenmp.get()) {
            val libomp = fileTree("${sdkDir.get()}/ndk") {
                include("**/toolchains/llvm/prebuilt/*/lib/clang/*/lib/linux/aarch64/libomp.so")
            }.singleFile
            copy {
                from(libomp)
                into(generatedOpenmpJniLibs.get().dir("arm64-v8a"))
            }
        }
    }
}

android {
    namespace = "com.qwen.tts.android"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "ar.com.mivozlocal.android"
        minSdk = 31
        targetSdk = 36
        versionCode = 10
        versionName = "0.3.7"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    defaultConfig {
        externalNativeBuild {
            cmake {
                targets += listOf("qwen3_tts_jni", "mivoz_asr_jni")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DQWEN3_ANDROID_OPENMP=${if (qwenOpenmp.get()) "ON" else "OFF"}",
                    "-DQWEN3_ANDROID_VULKAN=${if (qwenVulkan.get()) "ON" else "OFF"}",
                    "-DQWEN3_ANDROID_OPENCL=${if (qwenOpencl.get()) "ON" else "OFF"}",
                    "-DOpenCL_INCLUDE_DIR=${qwenOpenclSdkDir.get()}/OpenCL-Headers",
                )
            }
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(generatedOpenmpJniLibs.get().asFile)
        }
    }
}

tasks.matching {
    it.name.startsWith("merge") && (it.name.endsWith("NativeLibs") || it.name.endsWith("JniLibFolders"))
}.configureEach {
    dependsOn(copyOpenmpLibs)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation("junit:junit:4.13.2")
}
