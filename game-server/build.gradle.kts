plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
    alias(libs.plugins.kotlinPluginSpring)
    alias(libs.plugins.springBoot)
    alias(libs.plugins.springDependencyManagement)
    alias(libs.plugins.kover)
}

// Explicitly set the main class for Spring Boot to avoid conflicts
springBoot {
    mainClass.set("com.wingedsheep.gameserver.GameServerApplicationKt")
}

dependencies {
    implementation(project(":rules-engine"))
    implementation(project(":mtg-sdk"))
    implementation(project(":mtg-sets"))
    implementation(project(":mtg-search"))
    implementation(project(":ai"))
    implementation(libs.bundles.kotlinxEcosystem)
    implementation(libs.bundles.springBootWeb)
    implementation(libs.springBootStarterDataRedis)
    implementation(libs.springdocOpenapi)
    implementation(kotlin("reflect"))

    testImplementation(libs.springBootStarterTest)
    testImplementation(libs.kotestRunner)
    testImplementation(libs.kotestAssertions)
    testImplementation(libs.kotestExtensionsSpring)
    testImplementation(libs.kotlinxCoroutinesTest)
    testImplementation(libs.mockk)
}

// Task to run AI matchup script
tasks.register<JavaExec>("runAiMatchup") {
    group = "application"
    description = "Run AI matchup between two decks"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.wingedsheep.gameserver.ai.benchmark.AiMatchupRunnerKt")
    
    // Default arguments
    args = listOf("--deck1=red_creatures", "--deck2=white_creatures", "--games=100")
    
    // Allow passing arguments from command line
    if (project.hasProperty("args")) {
        args = (project.property("args") as String).split(" ")
    }
}
