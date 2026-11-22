package io.github.manuelrtsch.sidetrack.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one decision [ArtworkImages] makes before it loads: a device URI is read from MediaProvider,
 * anything else goes over the network. Getting this wrong would try to fetch `content://` art.
 */
class ArtworkImagesTest {
    @Test fun `a device album-art address is not a network address`() {
        assertTrue(ArtworkImages.isContentUri("content://media/external/audio/albumart/42"))
        assertTrue(ArtworkImages.isContentUri("content://media/external/images/media/7"))

        assertFalse(ArtworkImages.isContentUri("https://example.test/cover.jpg"))
        assertFalse(ArtworkImages.isContentUri("http://host:4533/rest/getCoverArt?id=1"))
        assertFalse(ArtworkImages.isContentUri(""))
    }
}
