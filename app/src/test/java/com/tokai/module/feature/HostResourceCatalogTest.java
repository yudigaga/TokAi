package com.tokai.module.feature;
import org.junit.Test;
import static org.junit.Assert.*;
public final class HostResourceCatalogTest {
 @Test public void tikTokNamesConfirmOnlyTheirCanonicalSemanticKeys() {
  assertTrue(HostResourceCatalog.matches(2131374347, HostResourceCatalog.VERIFIED_PACKAGE, "g90"));
  assertTrue(HostResourceCatalog.matches(2131374327, "g8v"));
  assertTrue(HostResourceCatalog.matches(2131371285, "eps"));
  assertTrue(HostResourceCatalog.matches(2131401927, "wbl"));
  assertTrue(HostResourceCatalog.matches(2131415727, "user_avatar"));
  assertTrue(HostResourceCatalog.matches(2131379772, "ixi"));
  assertFalse(HostResourceCatalog.matches(2131374347, "com.tokai.module", "g90"));
  assertFalse(HostResourceCatalog.matches(2131374347, "com.ss.android.ugc.aweme", "g90"));
 }
 @Test public void changedOrUnknownResourceNamesCannotConfirmAnIdentifier() {
  assertFalse(HostResourceCatalog.matches(2131374327, "digg"));
  assertFalse(HostResourceCatalog.matches(2131374327, (String)null));
  assertFalse(HostResourceCatalog.matches(0x7f0a9734, "like"));
 }
 @Test public void douyinNamesAndUnmappedComponentsCannotSelectTikTokActions() {
  assertFalse(HostResourceCatalog.matches(2131374336, "gw+"));
  assertFalse(HostResourceCatalog.matches(2131374327, "gw1"));
  assertFalse(HostResourceCatalog.matches(2131379772, "j_x"));
  assertFalse(HostResourceCatalog.matches(2131369833, "d5w"));
 }
}
