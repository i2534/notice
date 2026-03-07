// 本地构建用阿里云镜像，GitHub Actions 等 CI 用官方源（含 Google Maven，AGP 等由此解析）
pluginManagement {
    repositories {
        if (System.getenv("CI") != "true" && System.getenv("GITHUB_ACTIONS") != "true") {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        } else {
            google()
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("CI") != "true" && System.getenv("GITHUB_ACTIONS") != "true") {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        } else {
            google()
        }
        mavenCentral()
        maven { url = uri("https://repo.eclipse.org/content/repositories/paho-snapshots/") }
    }
}

rootProject.name = "Notice"
include(":app")