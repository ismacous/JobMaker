import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/** Lit une propriete de gradle.properties avec valeur de repli. */
fun prop(name: String, default: String): String =
    (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() } ?: default

val llamaTag = prop("jobmaker.llamaTag", "b6100")
val armArch = prop("jobmaker.armArch", "armv8.2-a+dotprod+i8mm+fp16")
val useOpenCl = prop("jobmaker.opencl", "false").toBoolean()
val skipNative = prop("jobmaker.skipNative", "false").toBoolean()

android {
    namespace = "com.jobmaker"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.jobmaker"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // Un seul ABI : le S25 Ultra est arm64. Evite de compiler llama.cpp 4 fois.
        ndk { abiFilters += "arm64-v8a" }

        buildConfigField("String", "LLAMA_TAG", "\"$llamaTag\"")
        buildConfigField("boolean", "NATIVE_ENABLED", "${!skipNative}")

        if (!skipNative) {
            externalNativeBuild {
                cmake {
                    // Le niveau d'optimisation est fixe dans CMakeLists.txt :
                    // un -O3 place ici serait ecrase par le -O0 du mode Debug.
                    cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
                    arguments += listOf(
                        "-DJOBMAKER_LLAMA_TAG=$llamaTag",
                        "-DJOBMAKER_ARM_ARCH=$armArch",
                        "-DJOBMAKER_OPENCL=${if (useOpenCl) "ON" else "OFF"}",
                        "-DANDROID_STL=c++_shared",
                    )
                }
            }
        }
    }

    if (!skipNative) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    signingConfigs {
        // Cle de signature versionnee avec le projet, volontairement.
        //
        // L'application est compilee par GitHub Actions et installee depuis un
        // telephone. Sans cle stable, chaque build serait signe differemment et
        // Android refuserait de l'installer par-dessus le precedent : il
        // faudrait desinstaller, donc perdre son profil, a chaque mise a jour.
        //
        // Ce n'est pas un secret compromis : c'est l'equivalent du
        // debug.keystore que tout projet Android partage, pour une application
        // qui n'est publiee sur aucun magasin. Pour une vraie diffusion, il
        // faudrait une cle privee gardee hors du depot.
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "jobmaker"
            keyAlias = "jobmaker"
            keyPassword = "jobmaker"
        }

        // Signature de release optionnelle : si keystore.properties existe a la
        // racine du projet, les builds release l'utilisent.
        val keystorePropsFile = rootProject.file("keystore.properties")
        if (keystorePropsFile.exists()) {
            create("release") {
                val p = Properties().apply { keystorePropsFile.inputStream().use { load(it) } }
                storeFile = rootProject.file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // Pas de suffixe d'identifiant : c'est ce build qui est installe sur le
        // telephone, il doit porter l'identifiant definitif de l'application.
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
        )
        // Les .so de llama.cpp sont volumineux : pas de compression pour
        // permettre le chargement direct depuis l'APK.
        jniLibs.useLegacyPackaging = false
    }
}

// Schema de la base exporte dans app/schemas : indispensable pour ecrire une
// migration le jour ou le modele de donnees changera.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.documentfile)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
}
