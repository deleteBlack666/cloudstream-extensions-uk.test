import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import com.android.build.gradle.BaseExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    dependencies {
        classpath(libs.recloudstream.gradle)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin) apply false
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) = extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: BaseExtension.() -> Unit) = extensions.getByName<BaseExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        // when running through github workflow, GITHUB_REPOSITORY should contain current repository name
        // you can modify it to use other git hosting services, like gitlab
        // setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/deleteBlack666/cloudstream-extensions-uk")
        setRepo("https://github.com/deleteBlack666/cloudstream-extensions-uk.test")
        authors = listOf("deleteBlack666")
    }

    android {
        namespace = "ua.deleteBlack666"

        defaultConfig {
            minSdk = 21
            compileSdkVersion(35)
            targetSdk = 35

        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }

        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8) // Required
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions"
                )
            }
        }
    }

    dependencies {
    dependencies {
        val cloudstream by configurations
        val implementation by configurations
        val libs = rootProject.libs

        // 1. Визначаємо шлях, куди буде збережено файл
        val cloudstreamJar = layout.buildDirectory.file("cloudstream/classes.jar").get().asFile

        // 2. Завантажуємо файл, якщо його ще немає
        if (!cloudstreamJar.exists()) {
            cloudstreamJar.parentFile.mkdirs()
            
            val url = java.net.URL("https://github.com/deleteBlack666/cloudstream-extensions-uk.test/releases/download/dependencies-v1/classes.jar")
            
            url.openStream().use { input ->
                cloudstreamJar.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            println("Завантажено classes.jar (${cloudstreamJar.length() / 1024 / 1024} MB)")
        }

        // 3. Передаємо завантажений файл у конфігурацію
        cloudstream(files(cloudstreamJar))

        // 4. Стандартні залежності
        implementation(kotlin("stdlib")) 
        implementation(libs.nicehttp) 
        implementation(libs.jsoup) 
    }

    tasks.withType<Test>().configureEach {
        if (name == "testReleaseUnitTest") {
            ignoreFailures = true // ignore fail test
        }
    }
}

tasks.register<Delete>("clean") {
    delete(getLayout().buildDirectory)
}

tasks.register<TestReport>("testReport") {
    description = "Aggregate all test results as a HTML report"
    group = "Build"
    destinationDirectory = layout.buildDirectory.dir("reports/allTests")
    testResults.from(subprojects.map { project -> project.tasks.getByName("testReleaseUnitTest") })
}
