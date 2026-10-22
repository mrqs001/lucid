plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

// The engine must never see simulator ground truth, so it depends on :core:model only.
// Tests may use the simulator to produce input data.
dependencies {
    api(project(":core:model"))
    testImplementation(project(":core:sim"))
    testImplementation(libs.junit)
}
