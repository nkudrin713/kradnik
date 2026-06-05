plugins {
	kotlin("jvm") version "2.2.21"
	kotlin("plugin.spring") version "2.2.21"
	kotlin("plugin.jpa") version "2.2.21"
	kotlin("plugin.allopen") version "2.2.21"
	id("org.springframework.boot") version "4.0.6"
	id("io.spring.dependency-management") version "1.1.7"
	jacoco
}

group = "com.nkudrin713"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-flyway")
	implementation("org.flywaydb:flyway-core")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
	implementation("org.postgresql:postgresql")
	implementation("com.github.pengrad:java-telegram-bot-api:9.2.0")
	testImplementation("io.kotest:kotest-property:6.0.0")
	testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
	testImplementation("io.mockk:mockk:1.14.6")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
	archiveFileName.set("app.jar")
}

tasks.test {
	useJUnitPlatform()
	finalizedBy(tasks.jacocoTestReport)
}

val jacocoExcludedClasses = listOf(
	"**/*Application.class",
	"**/*Application*.class",
	"**/*\$Companion.class",
	"**/*Config.class",
	"**/*Configuration.class",
	"**/*Command.class",
	"**/*Command\$*.class",
	"**/*Decision.class",
	"**/*Decision\$*.class",
	"**/*Dto.class",
	"**/*Dto\$*.class",
	"**/*Exception.class",
	"**/*Exception\$*.class",
	"**/*Kt.class",
	"**/*Metadata.class",
	"**/*Repository.class",
	"**/*Request.class",
	"**/*Request\$*.class",
	"**/*Result.class",
	"**/*Result\$*.class",
	"**/*Status.class",
	"**/PlatformDownloadHandler.class",
	"**/ProcessRunner.class",
	"**/TelegramCommandHandler.class",
	"**/WorkDirCleaner.class",
	"**/config/**",
	"**/domain/**",
	"**/repository/**",
)

val mainSourceSet = extensions.getByType<SourceSetContainer>().named("main")
val jacocoClassDirectories = mainSourceSet.map { sourceSet ->
	files(
		sourceSet.output.classesDirs.map { classDir ->
			fileTree(classDir) {
				exclude(jacocoExcludedClasses)
			}
		}
	)
}

tasks.jacocoTestReport {
	dependsOn(tasks.test)
	classDirectories.setFrom(jacocoClassDirectories)
	reports {
		xml.required.set(true)
		html.required.set(true)
		csv.required.set(false)
	}
}

tasks.jacocoTestCoverageVerification {
	classDirectories.setFrom(jacocoClassDirectories)
	violationRules {
		rule {
			limit {
				minimum = "0.80".toBigDecimal()
			}
		}
	}
}

tasks.check {
	dependsOn(tasks.jacocoTestCoverageVerification)
}
