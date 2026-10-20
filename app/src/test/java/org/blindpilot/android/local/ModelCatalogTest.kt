package org.blindpilot.android.local

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.blindpilot.android.core.Cancellation
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ModelCatalogTest {
    @Test fun keepsOnlyLoadableSingleFilesWithHashes() {
        val sha = "a".repeat(64)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[
                {"type":"file","path":"Q8/m-Q8_0.gguf","size":200,"lfs":{"oid":"$sha"}},
                {"type":"file","path":"m-Q4_K_M.gguf","size":100,"lfs":{"oid":"$sha"}},
                {"type":"file","path":"m-Q6-00001-of-00002.gguf","size":50,"lfs":{"oid":"$sha"}},
                {"type":"file","path":"mmproj-F16.gguf","size":10,"lfs":{"oid":"$sha"}},
                {"type":"file","path":"no-hash.gguf","size":10},
                {"type":"directory","path":"Q8"}]"""))
            val files = ModelCatalog(server.url("").toString().trimEnd('/')).files("org/m-GGUF", Cancellation())
            assertEquals(listOf("m-Q4_K_M.gguf", "m-Q8_0.gguf"), files.map { it.name })
            assertEquals(server.url("/org/m-GGUF/resolve/main/Q8/m-Q8_0.gguf").toString(), files[1].url)
            assertEquals("/api/models/org/m-GGUF/tree/main?recursive=true", server.takeRequest().path)
        }
    }
}
