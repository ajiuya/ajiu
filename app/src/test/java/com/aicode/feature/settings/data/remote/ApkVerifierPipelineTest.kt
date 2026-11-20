package com.aicode.feature.settings.data.remote

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ApkVerifierPipelineTest {
    private val context = mockk<Context>()
    private val packageManager = mockk<PackageManager>()
    private val apk = File("/download/update.apk")
    private val packageName = "com.ajiu"
    private val flags = PackageManager.GET_SIGNING_CERTIFICATES
    private lateinit var verifier: ApkVerifier

    @Before
    fun setUp() {
        every { context.packageManager } returns packageManager
        every { context.packageName } returns packageName
        verifier = ApkVerifier(context)
    }

    @Test
    fun unparseableArchive_isRejectedBeforeInstalledLookup() {
        every { packageManager.getPackageArchiveInfo(apk.absolutePath, flags) } returns null

        assertEquals(ApkVerifyResult.NotAnApk, verifier.verify(apk))
        verify(exactly = 0) { packageManager.getPackageInfo(any<String>(), any<Int>()) }
    }

    @Test
    fun foreignPackage_isRejectedBeforeInstalledLookupEvenWithHigherVersionCode() {
        val archive = packageInfo(1185, "2.0.0", name = "other.app")
        every { packageManager.getPackageArchiveInfo(apk.absolutePath, flags) } returns archive

        assertEquals(ApkVerifyResult.PackageMismatch("other.app"), verifier.verify(apk))
        verify(exactly = 0) { packageManager.getPackageInfo(any<String>(), any<Int>()) }
    }

    @Test
    fun missingInstalledPackage_isRejected() {
        val archive = packageInfo(1184, "1.13.0")
        every { packageManager.getPackageArchiveInfo(apk.absolutePath, flags) } returns archive
        every { packageManager.getPackageInfo(packageName, flags) } throws PackageManager.NameNotFoundException()

        assertEquals(ApkVerifyResult.NotAnApk, verifier.verify(apk))
    }

    @Test
    fun matchingSigner_allowsRcToStableWithEqualVersionCode() {
        val installed = packageInfo(1184, "1.13.0-rc2")
        val archive = packageInfo(1184, "1.13.0")
        packages(archive, installed)

        assertEquals(ApkVerifyResult.Ok(1184), verifier.verify(apk))
    }

    @Test
    fun matchingSigner_doesNotTurnIdenticalMetadataIntoAnUpdate() {
        packages(packageInfo(1184, "1.13.0"), packageInfo(1184, "1.13.0"))

        assertEquals(ApkVerifyResult.NotNewer(1184), verifier.verify(apk))
    }

    @Test
    fun matchingSigner_stillRejectsLowerVersionCodeWithHigherVersionName() {
        packages(packageInfo(1183, "2.0.0"), packageInfo(1184, "1.13.0-rc2"))

        assertEquals(ApkVerifyResult.NotNewer(1183), verifier.verify(apk))
    }

    @Test
    fun unrelatedSigner_isRejectedBeforeVersionComparison() {
        listOf(1183L, 1184L, 1185L).forEach { code ->
            packages(
                packageInfo(code, "1.13.0", currentSigners = listOf("unrelated")),
                packageInfo(1184, "1.13.0-rc2")
            )

            assertEquals(ApkVerifyResult.SignatureMismatch, verifier.verify(apk))
        }
    }

    @Test
    fun everyArchiveSigner_mustBelongToInstalledSigningHistory() {
        packages(
            packageInfo(1184, "1.13.0", currentSigners = listOf("current", "unrelated")),
            packageInfo(1184, "1.13.0-rc2", history = listOf("old", "current"))
        )

        assertEquals(ApkVerifyResult.SignatureMismatch, verifier.verify(apk))
    }

    @Test
    fun archiveHistory_cannotSubstituteForItsActualSigner() {
        packages(
            packageInfo(1184, "1.13.0", currentSigners = listOf("unrelated"), history = listOf("current")),
            packageInfo(1184, "1.13.0-rc2")
        )

        assertEquals(ApkVerifyResult.SignatureMismatch, verifier.verify(apk))
    }

    @Test
    fun signerFromInstalledRotationHistory_isStillAccepted() {
        packages(
            packageInfo(1184, "1.13.0", currentSigners = listOf("old")),
            packageInfo(1184, "1.13.0-rc2", history = listOf("old", "current"))
        )

        assertEquals(ApkVerifyResult.Ok(1184), verifier.verify(apk))
    }

    @Test
    fun missingArchiveOrInstalledSigningInfo_isRejected() {
        val archive = packageInfo(1184, "1.13.0").apply { signingInfo = null }
        packages(archive, packageInfo(1184, "1.13.0-rc2"))
        assertEquals(ApkVerifyResult.SignatureMismatch, verifier.verify(apk))

        val installed = packageInfo(1184, "1.13.0-rc2").apply { signingInfo = null }
        packages(packageInfo(1184, "1.13.0"), installed)
        assertEquals(ApkVerifyResult.SignatureMismatch, verifier.verify(apk))
    }

    @Test
    fun emptyArchiveSignerSet_isRejected() {
        packages(packageInfo(1184, "1.13.0", currentSigners = emptyList()), packageInfo(1184, "1.13.0-rc2"))

        assertEquals(ApkVerifyResult.SignatureMismatch, verifier.verify(apk))
    }

    private fun packages(archive: PackageInfo, installed: PackageInfo) {
        every { packageManager.getPackageArchiveInfo(apk.absolutePath, flags) } returns archive
        every { packageManager.getPackageInfo(packageName, flags) } returns installed
    }

    private fun packageInfo(
        code: Long,
        version: String?,
        name: String = packageName,
        currentSigners: List<String> = listOf("current"),
        history: List<String> = currentSigners
    ): PackageInfo = PackageInfo().apply {
        packageName = name
        versionName = version
        setLongVersionCode(code)
        signingInfo = mockk<SigningInfo>().also { signing ->
            every { signing.apkContentsSigners } returns signatures(currentSigners)
            every { signing.signingCertificateHistory } returns signatures(history)
        }
    }

    private fun signatures(signers: List<String>): Array<Signature> =
        signers.map { Signature(it.toByteArray()) }.toTypedArray()
}
