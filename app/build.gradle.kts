plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.jetbrainsKotlinSerialization)
}

android {
    namespace = "cn.huacheng.safebaiyun"
    compileSdk = 34

    defaultConfig {
        applicationId = "cn.huacheng.safebaiyun"
        minSdk = 21
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    // 源码直接使用 foundation（layout.* / LazyColumn / background / isSystemInDarkTheme），
    // 不依赖 material3 的传递依赖，避免 material3 调整内部依赖时静默断链
    implementation(libs.androidx.foundation)
    // 源码直接使用 animation（slideIn / slideOut），同样显式声明
    implementation(libs.androidx.animation)
    // Icons.Filled.* / Icons.AutoMirrored.*：Material3 1.3.0 起已移除对 material-icons-core 的依赖
    implementation(libs.androidx.material.icons.core)
    implementation(libs.kotlinx.serialization.json)
    // GlobalScope / launch / Dispatchers.Main / MutableStateFlow 的直接来源
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.glance)
    implementation(libs.glance.material)
    implementation(libs.navigation)


    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
