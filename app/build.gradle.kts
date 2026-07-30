plugins {
    id("com.android.application")
}

android {
    namespace = "com.xiaofanqie.plugin"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xiaofanqie.plugin"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    // 确保 module.prop 被打包到 APK 根目录 META-INF/qauxv/ 下
    // src/main/resources/ 下的文件会直接映射到 APK 根路径
    sourceSets {
        getByName("main") {
            resources.srcDirs("src/main/resources")
        }
    }
}

dependencies {
    // 外部插件只需要最基础的 Android 依赖
    // IHookBridge 等接口在运行时通过 ChainLoaderAgent 获取
    // QAuxiliary 的类通过 ChainLoaderAgent.getModuleClassLoader() 反射调用
    compileOnly("androidx.annotation:annotation:1.7.0")
}
