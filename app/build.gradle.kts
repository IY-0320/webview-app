plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 서명 키는 저장소에 넣지 않고, GitHub Actions가 Secrets에서 꺼내 환경 변수로 넘겨준다
val keystoreFile: String? = System.getenv("KEYSTORE_FILE")
val keystorePassword: String? = System.getenv("KEYSTORE_PASSWORD")

android {
    namespace = "com.dreamland.webview"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dreamland.webview"
        minSdk = 24
        targetSdk = 35
        // 업데이트가 설치되려면 버전 번호가 계속 커져야 해서, 빌드 번호를 그대로 쓴다
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "1.0"
    }

    signingConfigs {
        create("release") {
            if (keystoreFile != null && keystorePassword != null) {
                storeFile = file(keystoreFile)
                storePassword = keystorePassword
                keyAlias = "dreamland"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 키가 없으면(내 PC에서 테스트 빌드할 때) 디버그 키로 서명
            signingConfig = if (keystoreFile != null) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
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
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    // 드림랜드 화면에서 "내보내기" 파일을 받아 저장하는 통로 (addWebMessageListener)
    implementation("androidx.webkit:webkit:1.12.1")
}
