package com.tokai.module.scan;
import org.junit.Test;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;
public final class HostAdaptationTest {
 @Test public void versionEvidenceRequiresTheExactTikTokProductNameAndActualCode() {
  assertTrue(HostAdaptation.matchesVersion(HostAdaptation.TARGET_PACKAGE,"47.5.3",2024705030L));
  assertFalse(HostAdaptation.matchesVersion("com.ss.android.ugc.aweme","47.5.3",2024705030L));
  assertFalse(HostAdaptation.matchesVersion(HostAdaptation.TARGET_PACKAGE,"47.5.3",2024705031L));
  assertFalse(HostAdaptation.matchesVersion(HostAdaptation.TARGET_PACKAGE,"40.8.0",400801L));
  assertFalse(HostAdaptation.matchesVersion(null,null,0L));
 }
 @Test public void historicalDouyinProfilesRemainKeysAndCannotBeSelectedAsTikTok() {
  assertNull(HostAdaptation.forVersion("40.6.0",400601L));
  assertNull(HostAdaptation.forVersion("40.8.0",400801L));
  assertSame(HostAdaptation.PROFILE_TIKTOK,HostAdaptation.forVersion("47.5.3",2024705030L));
  assertFalse(HostAdaptation.matchesRules(HostAdaptation.PROFILE_408.fingerprint));
  assertThrows(UnsupportedOperationException.class,()->HostAdaptation.getFeedResources().clear());
  assertEquals("video_seek_bar",HostAdaptation.getFeedResources().get(2131401999));
 }
 @Test public void packagedRulesAndResourcesAreBoundToTheIndependentTikTokProfile() throws Exception {
  try(InputStream in=new FileInputStream(HostApkTestProfile.assetFile(HostAdaptation.RULES_ASSET))) {
   HostRuleSet rules=HostRuleSet.parse(in);
   assertEquals(HostAdaptation.PROFILE_TIKTOK.fingerprint,rules.getFingerprint());
   assertEquals(HostAdaptation.TARGET_PACKAGE,rules.getTargetPackage());
   Set<String> ids=new HashSet<>();for(HostRule r:rules.getRules())assertTrue(ids.add(r.getId()));
   for(String id:new String[]{"feed_holder_selected","feed_aweme_accessor","main_feed_api_v1",
    "host_settings_activity","adv_player_set_speed","tiktok_comment_publish_checked","tiktok_im_send"})
    assertTrue("missing migrated core contract "+id,ids.contains(id));
  }
 }
 @Test public void speechAndMessageCapabilitiesHaveSeparateInstallationDiagnostics() {
  for(String id:new String[]{"auto_comment","comment_tts","comment_voice","dm_speech","native_auto_reply"}) {
   HostFeatureCatalog.Feature feature=null;for(HostFeatureCatalog.Feature f:HostFeatureCatalog.getFeatures())if(f.id.equals(id))feature=f;
   assertNotNull(id,feature);assertFalse(feature.rules.isEmpty());assertFalse(feature.installedKey.isEmpty());
  }
 }
}
