import java.util.Properties

// Tetra Auto SE, "stand alone edition" (the user, 13 Nov: "the same as the app is now on Android, with no office or data and what
// else is not used in the car"): Tetra for Android itself, built for a head unit — Android 10, Tetra Car its start and home
// screen. Nothing is copied: the code, resources and assets are app's own, its dependencies are read from app, and its manifest
// is app's with Tetra Car as the only icon. A change to Tetra is a change to the car.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val app = project(":app")
evaluationDependsOn(":app")

val signing = File(System.getProperty("user.home"), ".android/tetra-release.properties")
    .takeIf { it.exists() }
    ?.let { file -> Properties().apply { file.inputStream().use(::load) } }

// app's manifest with one launcher, Tetra Car (also offered as the unit's home screen); every other icon left out.
// shortcut: done while Gradle reads this file, plain text; a manifest task would be needed only if this ever grows past it.
val carManifest = layout.buildDirectory.file("generated/car/AndroidManifest.xml").get().asFile.apply {
    parentFile.mkdirs()
    val launcher = Regex("""\s*<category android:name="android.intent.category.LAUNCHER" />""")
    val car = Regex("""<activity android:name="\.CarActivity" android:exported="false"([^>]*?)/>""", RegexOption.DOT_MATCHES_ALL)
    var text = app.file("src/main/AndroidManifest.xml").readText().replace(launcher, "")
    // what a head unit does not use (the user: "remove what we don't need"): Office, PDF, scanners, photos, notes, calendar, timers,
    // pills, mail, passwords, Data, the home screen's widgets, the phone's own calls and SMS (the unit has no SIM: the phone does
    // them over CarWire), and the phone's half of Tetra Auto
    val unused = listOf("DriveOpenerReceiver", "TvWidget", "MapsCarService", "ContactsAuthService", "ContactsSyncService", "PhoneInCallService",
        "PhoneCallActivity", "BriefActivity", "AutoProjectionService", "AutoShareActivity", "DataActivity", "PhoneCallReceiver", "PhoneRelayReceiver",
        "PhoneMissedReceiver", "PhoneScreening", "PhotoAnalysisService", "TvTalkService", "AlarmRingActivity", "SmsDeliverReceiver", "SmsReceivedReceiver",
        "MmsDeliverReceiver", "SmsRespondService", "SmsCompose", "TimerService", "StandDream", "DataSpeedService", "NotesActivity", "CodesActivity",
        "CalendarActivity", "TimerActivity", "PillsActivity", "ContactsActivity", "MailActivity", "PasswordsActivity", "PasswordsAutofill", "AutofillUnlock",
        "AppPhotos", "AppFiles", "AppNotes", "AppScanner", "AppCodes", "AppOffice", "AppTextEditor", "AppCalendar", "AppTimer", "AppPills", "AppMaps",
        "AppMail", "AppMessages", "AppBrowser", "AppCar", "AppPasswords", "AppContacts", "AppPhone", "AppData", "AppAssistant", "CalendarOpen", "OfficeOpen",
        "TextEditorActivity", "MailShare", "SendToComputer", "LocationReminderReceiver", "CalendarAlarmReceiver", "PillsReceiver", "PillsVoiceService")
    for (name in unused) {
        val one = Regex("""\s*<(activity|activity-alias|service|receiver) android:name="\.$name"(?:[^>]*?/>|.*?</\1>)""", RegexOption.DOT_MATCHES_ALL)
        check(one.containsMatchIn(text)) { "$name is not in app's manifest: take it off this list" }
        text = text.replace(one, "")
    }
    check(car.containsMatchIn(text)) { "CarActivity is not in app's manifest as this build expects" }
    writeText(text.replace(car) { m ->
        """<activity android:name=".CarActivity" android:exported="true"${m.groupValues[1]}>
            <intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter>
            <intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.HOME" /><category android:name="android.intent.category.DEFAULT" /></intent-filter>
        </activity>"""
    }.replace("<application ", """<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="29" />
    <application android:requestLegacyExternalStorage="true" """)) // Android 10: /sdcard/Tetra with the storage permission
}

android {
    namespace = "com.kalotrapezis.drive"
    compileSdk { version = release(37) { minorApiLevel = 2 } }

    defaultConfig {
        applicationId = "com.kalotrapezis.tetra.auto.se"
        minSdk = 29 // head units: Android 10 sold as "13" (QP1A build ids)
        targetSdk = 36
        versionCode = app.extensions.getByType<com.android.build.api.dsl.ApplicationExtension>().defaultConfig.versionCode
        versionName = app.extensions.getByType<com.android.build.api.dsl.ApplicationExtension>().defaultConfig.versionName + "-se"
        val languages = listOf("en") + app.file("src/main/res").listFiles().orEmpty()
            .filter { it.name.matches(Regex("values-[a-z]{2,3}(-r[A-Z]{2})?")) && File(it, "strings.xml").exists() }
            .map { it.name.removePrefix("values-").replace("-r", "-") }.sorted()
        buildConfigField("String", "LANGUAGES", "\"${languages.joinToString(",")}\"")
    }
    sourceSets["main"].apply {
        manifest.srcFile(carManifest)
        java.srcDirs(app.file("src/main/java"))
        kotlin.srcDirs(app.file("src/main/java"))
        res.srcDirs(app.file("src/main/res"))
        assets.srcDirs(app.file("src/main/assets"))
    }
    androidResources { generateLocaleConfig = true }
    signingConfigs {
        signing?.let { properties ->
            create("release") {
                storeFile = file(properties.getProperty("storeFile"))
                storePassword = properties.getProperty("storePassword")
                keyAlias = properties.getProperty("keyAlias")
                keyPassword = properties.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        debug { isDebuggable = project.hasProperty("debuggable") }
        release {
            isMinifyEnabled = false
            signing?.let { signingConfig = signingConfigs.getByName("release") }
        }
    }
    // head units run Android 10: what newer than that is called must be guarded (lintCarDebug... `./gradlew :car:lintDebug`)
    lint { checkOnly += setOf("NewApi", "InlinedApi"); abortOnError = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        resources { excludes += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md") }
        jniLibs { useLegacyPackaging = true }
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a") // UIS8581A units: often a 32-bit Android on a 64-bit chip
            if (project.hasProperty("emulator")) include("x86_64")
            isUniversalApk = false
        }
    }
}

// app's own dependencies, so the two never drift apart
dependencies {
    listOf("implementation", "debugImplementation").forEach { conf ->
        app.configurations.getByName(conf).dependencies.forEach { add(conf, it.copy()) }
    }
}

/** GraphHopper's ByteBuffer.get/put(index, bytes, offset, length) calls (Android 14+) turned into Nio29's (Android 10). */
abstract class Nio29Calls : com.android.build.api.instrumentation.AsmClassVisitorFactory<com.android.build.api.instrumentation.InstrumentationParameters.None> {
    override fun createClassVisitor(classContext: com.android.build.api.instrumentation.ClassContext, nextClassVisitor: org.objectweb.asm.ClassVisitor) =
        object : org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9, nextClassVisitor) {
            override fun visitMethod(access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?) =
                object : org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                        if (opcode == org.objectweb.asm.Opcodes.INVOKEVIRTUAL && owner == "java/nio/ByteBuffer" && (name == "get" || name == "put") && descriptor == "(I[BII)Ljava/nio/ByteBuffer;")
                            super.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC, "com/kalotrapezis/drive/Nio29", name, "(Ljava/nio/ByteBuffer;I[BII)Ljava/nio/ByteBuffer;", false)
                        else super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                    }
                }
        }
    override fun isInstrumentable(classData: com.android.build.api.instrumentation.ClassData) = classData.className == "com.graphhopper.storage.MMapDataAccess"
}
androidComponents {
    onVariants { v -> v.instrumentation.transformClassesWith(Nio29Calls::class.java, com.android.build.api.instrumentation.InstrumentationScope.ALL) {} }
}
