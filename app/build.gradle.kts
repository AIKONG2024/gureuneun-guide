import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 릴리스 서명 정보는 저장소 밖 keystore.properties 에서 읽음 (없으면 디버그 키로 서명)
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "app.gureuneun.guide"
    compileSdk = 34

    // CarUxRestrictionsManager 사용
    useLibrary("android.car")

    defaultConfig {
        applicationId = "app.gureuneun.guide"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        // 에뮬레이터에서 호스트 PC 의 동반 서버 주소
        buildConfigField("String", "COMPANION_URL", "\"http://10.0.2.2:8787\"")
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystoreProps.isNotEmpty()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // 차 앱에는 명소 사진(photos)만 넣고 웹 페이지와 스크립트, 웹 글꼴(vendor)은 뺌
    androidResources {
        ignoreAssetsPattern = "!vendor:!js:!css:!*.html:!.DS_Store"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/versions/9/previous-compilation-data.bin"
        }
    }

    // 휴대폰 그룹 페이지와 같은 명소 사진(companion/web/photos)을 차 화면에서도 씀
    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets", "../companion/web")
        }
    }
}

dependencies {
    // Pleos Connect SDK
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")

    // UI
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // QR 생성, 동반 서버 연결
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 단위 시험 (질문 알아듣기, 조사 처리, 시나리오 자료 점검)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
