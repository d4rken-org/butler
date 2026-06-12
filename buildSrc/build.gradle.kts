plugins {
    `kotlin-dsl`
    `java-library`
}

gradlePlugin {
    plugins {
        create("projectConfigPlugin") {
            id = "projectConfig"
            implementationClass = "ProjectConfigPlugin"
        }
    }
}

repositories {
    google()
    mavenCentral()
}
dependencies {
    implementation(libs.android.gradle.plugin)
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.ksp.gradle.plugin)
    implementation(libs.hilt.gradle.plugin)
    implementation(libs.javapoet)
}