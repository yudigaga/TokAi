package com.tokai.module.feature;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public final class HostTransportFailureDiagnosticsTest {
    @Test public void genericIoFailureDoesNotInspectMessagesOrUnverifiedGettersAndIsBounded() {
        List<String> evidence = new ArrayList<>();
        AtomicLong now = new AtomicLong();
        HostTransportFailureDiagnostics diagnostics = new HostTransportFailureDiagnostics(now::get, evidence::add);
        IOException failure = new UntrustedIOException();
        diagnostics.record(failure);
        diagnostics.record(failure);
        assertEquals(1, evidence.size());
        assertEquals("origin=host_transport;status_code=unknown;cronet_internal_error_code=unknown", evidence.get(0));
        now.set(30_000_000_000L);
        diagnostics.record(failure);
        assertEquals(2, evidence.size());
    }

    @Test public void unrelatedFailureIsNotReclassifiedAndBrokenSinkDoesNotEscape() {
        List<String> evidence = new ArrayList<>();
        HostTransportFailureDiagnostics diagnostics = new HostTransportFailureDiagnostics(() -> 0L, evidence::add);
        diagnostics.record(new IllegalStateException("private host message"));
        diagnostics.record(new RuntimeException(new IOException("unverified wrapped failure")));
        assertTrue(evidence.isEmpty());
        new HostTransportFailureDiagnostics(() -> 0L, value -> { throw new AssertionError("sink unavailable"); })
                .record(new UntrustedIOException());
    }

    @Test public void exactHostCronetClassExposesOnlyVerifiedNumericCodesEvenWhenWrapped() throws Exception {
        IOException cronet = cronetFixture();
        List<String> evidence = new ArrayList<>();
        HostTransportFailureDiagnostics diagnostics = new HostTransportFailureDiagnostics(() -> 0L, evidence::add);
        diagnostics.record(new RuntimeException("private wrapper message", cronet));
        assertEquals(1, evidence.size());
        assertEquals("origin=host_transport;status_code=503;cronet_internal_error_code=-105", evidence.get(0));
    }

    public static final class UntrustedIOException extends IOException {
        @Override public String getMessage() { throw new AssertionError("message inspected"); }
        @Override public String toString() { throw new AssertionError("failure stringified"); }
        public int getStatusCode() { throw new AssertionError("unknown getter invoked"); }
        public int getCronetInternalErrorCode() { throw new AssertionError("unknown getter invoked"); }
    }

    /** The exact obfuscated binary name is not a legal Java source identifier. */
    private static IOException cronetFixture() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0xcafebabe); out.writeShort(0); out.writeShort(52); out.writeShort(13);
            utf(out, "X/0CNE"); out.writeByte(7); out.writeShort(1);
            utf(out, "java/io/IOException"); out.writeByte(7); out.writeShort(3);
            utf(out, "<init>"); utf(out, "()V");
            out.writeByte(12); out.writeShort(5); out.writeShort(6);
            out.writeByte(10); out.writeShort(4); out.writeShort(7);
            utf(out, "Code"); utf(out, "getStatusCode"); utf(out, "()I"); utf(out, "getCronetInternalErrorCode");
            out.writeShort(0x0031); out.writeShort(2); out.writeShort(4);
            out.writeShort(0); out.writeShort(0); out.writeShort(3);
            method(out, 5, 6, new byte[]{0x2a, (byte) 0xb7, 0, 8, (byte) 0xb1});
            method(out, 10, 11, new byte[]{0x11, 0x01, (byte) 0xf7, (byte) 0xac});
            method(out, 12, 11, new byte[]{0x11, (byte) 0xff, (byte) 0x97, (byte) 0xac});
            out.writeShort(0);
        }
        class Loader extends ClassLoader {
            Class<?> define(byte[] definition) { return defineClass("X.0CNE", definition, 0, definition.length); }
        }
        return (IOException) new Loader().define(bytes.toByteArray()).getConstructor().newInstance();
    }

    private static void utf(DataOutputStream out, String value) throws IOException { out.writeByte(1); out.writeUTF(value); }
    private static void method(DataOutputStream out, int name, int descriptor, byte[] code) throws IOException {
        out.writeShort(1); out.writeShort(name); out.writeShort(descriptor); out.writeShort(1);
        out.writeShort(9); out.writeInt(12 + code.length); out.writeShort(1); out.writeShort(1);
        out.writeInt(code.length); out.write(code); out.writeShort(0); out.writeShort(0);
    }
}
