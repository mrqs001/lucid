plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin { jvmToolchain(17) }

application { mainClass.set("lucid.core.eval.MainKt") }

dependencies {
    implementation(project(":core:engine"))
    implementation(project(":core:sim"))
}

dependencies {
    testImplementation(libs.junit)
}
