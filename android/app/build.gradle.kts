plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.hookbox.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hookbox.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.2.4"

        // RELAY_TOKEN 由 CI 的 secret 注入：./gradlew assembleDebug -PrelayToken=xxx
        // 不写死进源码，因为仓库是公开的。本地构建不带这个参数就是空串。
        val relayToken = (project.findProperty("relayToken") as String?).orEmpty()
        buildConfigField("String", "RELAY_TOKEN", "\"$relayToken\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            // 关掉混淆：Room 生成的实现类 + Compose 反射容易在 R8 下出问题，
            // 自用场景没必要为省几 MB 冒这个险。
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

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // 图标只用核心集（箭头/搜索/设置/复制/删除等），版本由 BOM 统一管理
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
