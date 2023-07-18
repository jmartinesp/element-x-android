/*
 * Copyright (c) 2022 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.util.Properties

plugins {
    id("io.element.android-compose-library")
    alias(libs.plugins.ksp)
    id("kotlin-parcelize")
}

fun readLocalProperty(name: String) = Properties().apply {
    try {
        load(rootProject.file("local.properties").reader())
    } catch (ignored: java.io.IOException) {
    }
}[name]

android {
    namespace = "io.element.android.features.location.api"

    defaultConfig {
        resValue(
            type = "string",
            name = "maptiler_api_key",
            value = System.getenv("ELEMENT_ANDROID_MAPTILER_API_KEY")
                ?: readLocalProperty("services.maptiler.apikey") as? String
                ?: ""
        )
    }
}

dependencies {
    implementation(projects.libraries.architecture)
    implementation(projects.libraries.designsystem)
    implementation(projects.libraries.core)
    implementation(projects.libraries.matrixui)
    implementation(projects.libraries.uiStrings)
    implementation(libs.coil.compose)
    ksp(libs.showkase.processor)

    testImplementation(libs.test.junit)
    testImplementation(libs.test.truth)
}
