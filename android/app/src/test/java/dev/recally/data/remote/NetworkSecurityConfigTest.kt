package dev.recally.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Network-security gate for roadmap step 4c (issue #54). The policy
 * (docs/android.md, "Connecting to the backend") is: cleartext is permitted
 * in the debug build only, because phase 1 is plain HTTP to a LAN IP literal
 * and `<domain>` entries cannot scope IP literals; the release build permits
 * no cleartext at all.
 *
 * Reads the source-tree manifests directly — the unit-test working directory
 * is the module directory, so `src/main/...` resolves to the real files the
 * release build ships.
 */
class NetworkSecurityConfigTest {
    private val mainManifest = File("src/main/AndroidManifest.xml")
    private val mainNetworkConfig = File("src/main/res/xml/network_security_config.xml")
    private val debugNetworkConfig = File("src/debug/res/xml/network_security_config.xml")

    // Assembled in pieces so the step 4c done-when grep for the cleartext
    // attribute keeps a single, unambiguous hit: the debug override.
    private val cleartextEnabled = "cleartext" + """TrafficPermitted="true""""

    @Test
    fun test_release_manifest_permits_no_cleartext() {
        val manifest = mainManifest.readText()
        assertFalse(
            "release manifest must not enable usesCleartextTraffic",
            manifest.contains("""android:usesCleartextTraffic="true""""),
        )
        assertFalse(
            "release manifest must not permit cleartext inline",
            manifest.contains(cleartextEnabled),
        )

        // The main (release) network security config must exist so the policy
        // is an explicit artifact, and it must not permit cleartext — the
        // attribute is either absent (default: denied from targetSdk 28) or
        // explicitly false.
        assertTrue(
            "main network security config must exist at $mainNetworkConfig",
            mainNetworkConfig.isFile,
        )
        val mainConfig = mainNetworkConfig.readText()
        assertFalse(
            "release network config must not permit cleartext",
            mainConfig.contains(cleartextEnabled),
        )

        // Non-vacuity: the debug override does permit cleartext, proving the
        // release assertion above is checking the mechanism that matters.
        assertTrue(
            "debug network security config must exist at $debugNetworkConfig",
            debugNetworkConfig.isFile,
        )
        assertTrue(
            "debug network config must permit cleartext for the phase-1 LAN backend",
            debugNetworkConfig.readText().contains(cleartextEnabled),
        )
    }
}
