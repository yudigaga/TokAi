package com.tokai.module.scan;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;
public final class HostRuleAssetProfilesTest {
 @Test public void theRuntimeAssetIsTikTokAndHasNoDuplicateOrUnboundRuleIds() throws Exception {
  try(InputStream in=new FileInputStream(HostApkTestProfile.assetFile(HostAdaptation.RULES_ASSET))) {
   HostRuleSet rules=HostRuleSet.parse(in);assertEquals(HostAdaptation.VERIFIED_RULES_FINGERPRINT,rules.getFingerprint());
   assertEquals("com.zhiliaoapp.musically",rules.getTargetPackage());
   Set<String> ids=new HashSet<>();for(HostRule r:rules.getRules()) {
    assertTrue(ids.add(r.getId()));assertNotNull(r.getClassDescriptor());
    if(r.getKind()==HostRule.Kind.METHOD){assertNotNull(r.getMethodName());assertNotNull(r.getMethodDescriptor());}
   }
  }
 }
 @Test public void anUpstreamDouyinAssetCannotBeLoadedIntoTikTok() throws Exception {
  File old=HostApkTestProfile.assetFile("douyin-host-rules.tsv");
  try(InputStream in=new FileInputStream(old)){assertThrows(IOException.class,()->HostRuleSet.parse(in));}
 }
}
