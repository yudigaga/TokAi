package com.tokai.module.scan;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Assume;

import static org.junit.Assert.*;

public final class HostApkProfileTest {
    @Test public void readsTheExactTikTokBuildFromUtf8AndUtf16BinaryManifests() throws Exception {
        for (boolean utf8 : new boolean[]{true, false}) assertSame(HostAdaptation.PROFILE_TIKTOK,
            HostApkProfile.read(apk(manifest("com.zhiliaoapp.musically", "47.5.3", 2024705030, utf8))));
    }

    @Test public void exactTikTokUpdateVersionCodeIsPartOfTheVerifiedProfile() throws Exception {
        assertEquals(2024705030L, HostAdaptation.VERIFIED_TIKTOK_UPDATE_VERSION_CODE);
        byte[] verified = BinaryHostManifestFixture.manifest(HostAdaptation.TARGET_PACKAGE,
                "47.5.3", 2024705030, true, HostAdaptation.VERIFIED_TIKTOK_UPDATE_VERSION_CODE);
        assertSame(HostAdaptation.PROFILE_TIKTOK, HostApkProfile.read(apk(verified)));
        for (long build : new long[]{-1L, 2024705029L, 2024705031L}) {
            byte[] different = BinaryHostManifestFixture.manifest(HostAdaptation.TARGET_PACKAGE,
                    "47.5.3", 2024705030, true, build);
            assertNull("Unexpected TikTok UPDATE_VERSION_CODE=" + build, HostApkProfile.read(apk(different)));
        }
    }

    @Test public void originalWorkspaceBaseApkMatchesTheVerifiedProfile() throws Exception {
        String path = System.getenv("TOKAI_TEST_TIKTOK_BASE_APK");
        Assume.assumeTrue("TOKAI_TEST_TIKTOK_BASE_APK is not configured", path != null && !path.isEmpty());
        HostApkProfile.Identity identity = HostApkProfile.readIdentity(new File(path));
        assertEquals(HostAdaptation.TARGET_PACKAGE, identity.packageName);
        assertEquals(HostAdaptation.VERIFIED_VERSION_NAME, identity.versionName);
        assertEquals(HostAdaptation.VERIFIED_VERSION_CODE, identity.versionCode);
        assertEquals(HostAdaptation.VERIFIED_TIKTOK_UPDATE_VERSION_CODE, identity.updateVersionCode);
        assertSame(HostAdaptation.PROFILE_TIKTOK, HostApkProfile.read(new File(path)));
    }

    @Test public void unknownVersionsCodesAndProductsNeverSelectTheProfile() throws Exception {
        assertNull(HostApkProfile.read(apk(manifest(HostAdaptation.TARGET_PACKAGE, "47.5.4", 2024705030, true))));
        assertNull(HostApkProfile.read(apk(manifest(HostAdaptation.TARGET_PACKAGE, "47.5.3", 2024705031, true))));
        assertNull(HostApkProfile.read(apk(manifest("com.ss.android.ugc.aweme", "47.5.3", 2024705030, true))));
        assertNull(HostApkProfile.read(apk(manifest(HostAdaptation.TARGET_PACKAGE, "40.8.0", 400801, true))));
    }

    @Test public void unexpectedInternalBuildMetadataRejectsAnOtherwiseIdenticalTikTok() throws Exception {
        for (long build : new long[]{-1L, 2024705029L, 2024705031L, 40809900L}) {
            byte[] xml=BinaryHostManifestFixture.manifest(HostAdaptation.TARGET_PACKAGE,"47.5.3",2024705030,true,build);
            assertEquals(build,HostApkProfile.parseManifest(xml).updateVersionCode);
            assertNull(HostApkProfile.read(apk(xml)));
        }
    }

    @Test public void updateMetadataIsCompiledAndOnlyReadFromTheApplication() throws Exception {
        byte[] xml=BinaryHostManifestFixture.manifest(HostAdaptation.TARGET_PACKAGE,"47.5.3",2024705030,true,40809900L);
        int root=8+buffer(xml).getInt(12), application=root+96, meta=application+36;
        byte[] wrongNamespace=xml.clone();buffer(wrongNamespace).putInt(meta+36,-1);
        assertEquals(-1L,HostApkProfile.parseManifest(wrongNamespace).updateVersionCode);
        byte[] wrongOwner=xml.clone();buffer(wrongOwner).putInt(application+20,14);
        assertEquals(-1L,HostApkProfile.parseManifest(wrongOwner).updateVersionCode);
        byte[] compiledOther=xml.clone();buffer(compiledOther).putInt(meta+36+20+8,13);
        buffer(compiledOther).putInt(meta+36+20+16,40809985);
        assertEquals(40809985L,HostApkProfile.parseManifest(compiledOther).updateVersionCode);
    }

    @Test public void invalidChunkAttributeAndStringOffsetsFailAsIoErrors() throws Exception {
        byte[] valid = manifest(HostAdaptation.TARGET_PACKAGE, "40.8.0", 400801, true);
        assertInvalid(Arrays.copyOf(valid, valid.length - 1));

        byte[] badChunk = valid.clone();
        buffer(badChunk).putInt(12, Integer.MAX_VALUE);
        assertInvalid(badChunk);

        byte[] badString = valid.clone();
        buffer(badString).putInt(8 + 28, Integer.MAX_VALUE);
        assertInvalid(badString);

        byte[] badAttribute = valid.clone();
        int element = 8 + buffer(valid).getInt(12);
        buffer(badAttribute).putShort(element + 28, (short) 0xffff);
        assertInvalid(badAttribute);

        assertInvalid(new byte[16 * 1024 * 1024 + 1]);
    }

    @Test public void versionAttributesRequireTheAndroidNamespace() throws Exception {
        byte[] xml = manifest(HostAdaptation.TARGET_PACKAGE, "40.8.0", 400801, true);
        int element = 8 + buffer(xml).getInt(12);
        buffer(xml).putInt(element + 36 + 20, -1);
        assertInvalid(xml);
    }

    @Test public void uncompiledRawTextCannotOverrideTheActualTypedVersion() throws Exception {
        byte[] xml = manifest(HostAdaptation.TARGET_PACKAGE, "40.7.0", 400801, true);
        int element = 8 + buffer(xml).getInt(12);
        buffer(xml).putInt(element + 36 + 20 + 8, 7); // Raw text says 40.8.0; the compiled value remains 40.7.0.
        assertNull(HostApkProfile.read(apk(xml)));
    }

    private static void assertInvalid(byte[] manifest) {
        try {
            HostApkProfile.parseManifest(manifest);
            fail("Malformed Manifest must fail explicitly");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private static File apk(byte[] manifest) throws IOException {
        // Its misleading name ensures profile selection is bound to Manifest contents.
        File apk = Files.createTempFile("douyin-40.8.0-", ".apk").toFile();
        apk.deleteOnExit();
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(apk.toPath()))) {
            output.putNextEntry(new ZipEntry("AndroidManifest.xml"));
            output.write(manifest);
            output.closeEntry();
        }
        return apk;
    }

    private static ByteBuffer buffer(byte[] bytes) { return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); }

    private static byte[] manifest(String packageName, String versionName, int versionCode, boolean utf8) throws IOException {
        long updateCode = "47.5.3".equals(versionName) && versionCode == 2024705030
                ? HostAdaptation.VERIFIED_TIKTOK_UPDATE_VERSION_CODE
                : "40.8.0".equals(versionName) && versionCode == 400801
                ? HostAdaptation.VERIFIED_UPDATE_VERSION_CODE : -1;
        return BinaryHostManifestFixture.manifest(packageName, versionName, versionCode, utf8, updateCode);
    }

    private static void attribute(ByteBuffer data, int namespace, int name, int raw, int type, int value) {
        data.putInt(namespace).putInt(name).putInt(raw).putShort((short) 8);
        data.put((byte) 0).put((byte) type).putInt(value);
    }
}
