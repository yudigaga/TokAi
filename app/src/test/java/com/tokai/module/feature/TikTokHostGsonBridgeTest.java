package com.tokai.module.feature;

import X.TikTokMediaWork;
import com.google.gson.Gson;
import org.junit.Test;
import static org.junit.Assert.*;

public final class TikTokHostGsonBridgeTest {
    public static final class NativeGson {
        Object received;
        public String LJIILL(Object value) { received=value;return "{\"native\":true}"; }
    }
    public static final class WrongGson { public Object LJIILL(Object value) { return value; } }
    @Test public void usesTheVerifiedObfuscatedSerializerAndPreservesItsInput() throws Exception {
        NativeGson gson=new NativeGson();Object input=new Object();
        assertEquals("{\"native\":true}",HostGsonBridge.toJson(gson,input));assertSame(input,gson.received);
    }
    @Test public void standardGsonBridgeStillWorksAndWrongReturnShapesFailClosed() throws Exception {
        assertEquals("\"text\"",HostGsonBridge.toJson(new Gson(),"text"));
        assertEquals("null",HostGsonBridge.toJson(new Gson(),null));
        assertThrows(NoSuchMethodException.class,()->HostGsonBridge.serializerMethod(WrongGson.class));
    }
    @Test public void obfuscatedAnnotationValueAndAlternateAreSharedAcrossMappedReaders() throws Exception {
        java.lang.reflect.Field id=TikTokMediaWork.class.getField("identity");
        assertEquals("aweme_id",HostGsonBridge.serializedName(id));
        assertTrue(HostGsonBridge.matchesName(id,"aweme_id"));assertTrue(HostGsonBridge.matchesName(id,"item_id"));
        assertFalse(HostGsonBridge.matchesName(id,"identity"));
    }
}
