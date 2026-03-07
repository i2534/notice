// 本地构建用阿里云镜像，GitHub Actions 等 CI 用官方源
pluginManagement {
    repositories {
        if (System.getenv("CI") != "true" && System.getenv("GITHUB_ACTIONS") != "true") {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
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
        }
        mavenCentral()
        maven { url = uri("https://repo.eclipse.org/content/repositories/paho-snapshots/") }
    }
}

rootProject.name = "Notice"
include(":app")