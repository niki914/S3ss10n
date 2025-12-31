pluginManagement {
    repositories {
//        maven { url = uri("https://maven.aliyun.com/repository/public") }
//        maven { url = uri("https://maven.aliyun.com/repository/google") }
//        maven { url = uri("https://maven.aliyun.com/repository/central") }
//        maven { url = uri("https://maven.aliyun.com/repository/jcenter") }
//        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }

        maven { url = uri("https://jitpack.io") }

        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
//        maven { url = uri("https://maven.aliyun.com/repository/public") }
//        maven { url = uri("https://maven.aliyun.com/repository/google") }
//        maven { url = uri("https://maven.aliyun.com/repository/central") }
//        maven { url = uri("https://maven.aliyun.com/repository/jcenter") }
//        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }

        maven { url = uri("https://jitpack.io") }

        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
rootProject.name = "S3ss10n"
include(":app")
include(":s3ss10n")
include(":composebase")
