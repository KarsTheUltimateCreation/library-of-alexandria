/*
 * Packaging of the JVM ".jar" flavour of the extension, published next to the APK.
 *
 * Adapted from keiyoushi/extensions-source gradle/build-logic (CreateExtensionJarTask / SignExtensionJarTask),
 * Apache License 2.0. Keiyoushi publishes a signed jar (JVM bytecode + merged AndroidManifest + resources) for
 * every extension and advertises it as `jarUrl` in its index; runtimes that execute JVM bytecode instead of
 * dex can load it directly.
 */

import com.android.apksig.ApkSigner
import com.android.apksig.KeyConfig
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

private const val FIXED_JAR_TIME = 1735689600000L // 2025-01-01, reproducible entries

private fun entry(name: String) = JarEntry(name).apply { time = FIXED_JAR_TIME }

abstract class CreateExtensionJarTask : DefaultTask() {
    @get:Classpath abstract val jars: ListProperty<RegularFile>
    @get:Classpath abstract val dirs: ListProperty<Directory>

    /** Host-provided libraries: their classes must never be copied into the jar. */
    @get:Classpath abstract val providedClasspath: ConfigurableFileCollection

    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val manifestFile: RegularFileProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.NONE) abstract val apkDir: DirectoryProperty
    @get:OutputFile abstract val outputJar: RegularFileProperty

    @TaskAction
    fun create() {
        val provided = HashSet<String>()
        providedClasspath.files.filter { it.isFile && it.extension == "jar" }.forEach { f ->
            runCatching { JarFile(f).use { jf -> jf.entries().asSequence().forEach { provided += it.name } } }
        }
        val out = outputJar.get().asFile.apply { parentFile.mkdirs() }
        val written = HashSet<String>()
        JarOutputStream(out.outputStream().buffered()).use { jar ->
            fun put(name: String, bytes: () -> ByteArray) {
                // Signing writes its own META-INF; only Kotlin module metadata is worth keeping from inputs.
                if (name in provided || (name.startsWith("META-INF/") && !name.endsWith(".kotlin_module"))) return
                if (!written.add(name)) return
                jar.putNextEntry(entry(name)); jar.write(bytes()); jar.closeEntry()
            }
            put("AndroidManifest.xml") { manifestFile.get().asFile.readBytes() }
            dirs.get().forEach { d ->
                val root = d.asFile
                root.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(root).invariantSeparatorsPath }
                    .forEach { f -> put(f.relativeTo(root).invariantSeparatorsPath) { f.readBytes() } }
            }
            jars.get().forEach { rf ->
                JarFile(rf.asFile).use { src ->
                    src.entries().asSequence().filter { !it.isDirectory }.sortedBy { it.name }
                        .forEach { e -> put(e.name) { src.getInputStream(e).readBytes() } }
                }
            }
            val apk = apkDir.get().asFile.walkTopDown().first { it.extension == "apk" }
            JarFile(apk).use { src ->
                src.entries().asSequence()
                    .filter { !it.isDirectory && (it.name.startsWith("res/") || it.name.startsWith("assets/") || it.name == "resources.arsc") }
                    .sortedBy { it.name }
                    .forEach { e -> put(e.name) { src.getInputStream(e).readBytes() } }
            }
        }
    }
}

abstract class SignExtensionJarTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val inputJar: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val keystore: ConfigurableFileCollection
    @get:Input abstract val keyAlias: Property<String>
    @get:Input abstract val minSdkVersion: Property<Int>
    @get:Internal abstract val storePassword: Property<String>
    @get:Internal abstract val keyPassword: Property<String>
    @get:OutputFile abstract val outputJar: RegularFileProperty

    @TaskAction
    fun sign() {
        val input = inputJar.get().asFile
        val out = outputJar.get().asFile.apply { parentFile.mkdirs() }
        val ks = keystore.files.firstOrNull()?.takeIf { it.exists() }
        if (ks == null) {
            logger.lifecycle("No keystore available - writing unsigned jar ${out.name}")
            input.copyTo(out, overwrite = true)
            return
        }
        val store = load(ks, storePassword.get().toCharArray())
        val alias = keyAlias.get()
        val key = store.getKey(alias, keyPassword.get().toCharArray()) as? PrivateKey ?: error("No private key for alias '$alias'")
        val certs = store.getCertificateChain(alias).map { it as X509Certificate }
        ApkSigner.Builder(listOf(ApkSigner.SignerConfig.Builder("CERT", KeyConfig.Jca(key), certs, true).build()))
            .setInputApk(input).setOutputApk(out)
            .setV1SigningEnabled(true).setV2SigningEnabled(false).setV3SigningEnabled(false)
            .setMinSdkVersion(minSdkVersion.get())
            .build().sign()
    }

    private fun load(file: File, password: CharArray): KeyStore {
        for (type in listOf("PKCS12", "JKS")) {
            runCatching { return KeyStore.getInstance(type).apply { file.inputStream().use { load(it, password) } } }
        }
        error("Unable to load keystore ${file.name}")
    }
}
