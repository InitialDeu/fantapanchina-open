plugins {
    id("com.android.application")
    kotlin("android")
}

// Su GitHub Actions ogni build ha un numero crescente: diventa il versionCode, così ogni APK
// nuovo si installa sopra il vecchio. Il +100 lo tiene sopra le prime versioni (1-3).
val numeroBuild = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0

// La chiave di firma arriva dai secret del repo. Sempre la stessa chiave = aggiornamenti possibili.
val chiave = System.getenv("KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }

/**
 * Impostazioni di chi pubblica l'app, lette da variabili d'ambiente (su GitHub: variabili e secret
 * del repo) o da gradle.properties. Vuote = funzione spenta. Finiscono in BuildConfig.
 */
fun impostazione(nome: String): String =
    (System.getenv(nome) ?: (project.findProperty(nome) as String?) ?: "").replace("\"", "")

android {
    namespace = "io.github.initialdeu.fantapanchina"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.initialdeu.fantapanchina"
        minSdk = 26
        targetSdk = 36
        versionCode = 100 + numeroBuild
        versionName = "3.1.$numeroBuild"

        // Indirizzo della privacy policy, mostrato in Informazioni e richiesto da Google Play.
        buildConfigField("String", "PRIVACY_URL", "\"${impostazione("PRIVACY_URL")}\"")
        // Email di contatto per segnalazioni e domande.
        buildConfigField("String", "CONTATTO_EMAIL", "\"${impostazione("CONTATTO_EMAIL")}\"")
        // Dove mandare le segnalazioni dei contenuti IA (POST JSON). Vuoto = via email, se c'è.
        buildConfigField("String", "SEGNALAZIONI_URL", "\"${impostazione("SEGNALAZIONI_URL")}\"")
        // Pagina del progetto (sorgenti, donazioni), linkata in Informazioni.
        buildConfigField("String", "PROGETTO_URL", "\"${impostazione("PROGETTO_URL")}\"")
        // Chiave TheSportsDB a pagamento; se manca si usano quelle gratuite.
        buildConfigField("String", "THESPORTSDB_KEY", "\"${impostazione("THESPORTSDB_KEY")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (chiave != null) {
            create("release") {
                storeFile = chiave
                storeType = System.getenv("KEYSTORE_TYPE")?.ifBlank { null } ?: "pkcs12"
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            // Senza chiave (build in locale) firma con quella di debug, solo per provare.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }

    // L'SDK di Anthropic porta con sé librerie Java con file di licenza duplicati.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md",
                "META-INF/INDEX.LIST",
                "META-INF/{AL2.0,LGPL2.1}"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("org.jsoup:jsoup:1.18.1")
    // SDK ufficiale per chiamare Claude.
    implementation("com.anthropic:anthropic-java:2.68.0")
    // Allinea kotlin-reflect (usato dall'SDK tramite Jackson) alla versione di Kotlin del progetto.
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.0.21")
}
