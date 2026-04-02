import com.android.build.api.variant.VariantOutputConfiguration
import org.gradle.api.provider.Property

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("app"))

android {
    namespace = "com.pikachu.music"
    compileSdk = 34

    sourceSets {
        getByName("main") {
            assets.srcDir(layout.buildDirectory.dir("generated/assets"))
        }
    }

    defaultConfig {
        applicationId = "com.pikachu.music"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.11.0")
}

tasks.register<Copy>("copyWebAssets") {
    from(rootProject.file("index.html"))
    into(layout.buildDirectory.dir("generated/assets"))
}

tasks.named("preBuild") {
    dependsOn("copyWebAssets")
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.outputs.forEach { output ->
            if (output.outputType == VariantOutputConfiguration.OutputType.SINGLE) {
                val getter =
                    output.javaClass.methods.firstOrNull { it.name == "getOutputFileName" && it.parameterCount == 0 }
                val prop = getter?.invoke(output) as? Property<*>
                @Suppress("UNCHECKED_CAST")
                (prop as? Property<String>)?.set("PikachuMusic.apk")
            }
        }
    }
}
