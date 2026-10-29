package de.itemis.mps.envgen

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals

class CacheDirTest {

    @Test
    fun `cache folder follows the platform`() {
        val env = mapOf("XDG_CACHE_HOME" to "/xdg", "LOCALAPPDATA" to "C:\\Users\\me\\AppData\\Local")
        assertEquals(File("/xdg/mps-env-gen"), userCacheDir(OS.LINUX, env::get, "/home/me"))
        assertEquals(File("/home/me/.cache/mps-env-gen"), userCacheDir(OS.LINUX, { null }, "/home/me"))
        assertEquals(File("/Users/me/Library/Caches/mps-env-gen"), userCacheDir(OS.MAC, env::get, "/Users/me"))
        assertEquals(File("C:\\Users\\me\\AppData\\Local", "mps-env-gen\\cache"), userCacheDir(OS.WINDOWS, env::get, "C:\\Users\\me"))
    }

    @Test
    fun `MPS_ENV_GEN_CACHE overrides the platform folder`() {
        val env = mapOf(CACHE_ENV to "/elsewhere", "XDG_CACHE_HOME" to "/xdg")
        assertEquals(File("/elsewhere"), userCacheDir(OS.LINUX, env::get, "/home/me"))
        assertEquals(File("/elsewhere/distributions"), distributionsDir(userCacheDir(OS.MAC, env::get, "/Users/me")))
    }
}
