package com.tokai.module.scan;

import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.reference.Reference;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;

import org.junit.Assume;
import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.junit.Assert.*;

/** Pins the requested-only observation to the original TikTok DEX, not a numeric fixture. */
public final class TikTokRequestedSpeedHostContractTest {
    @Test public void originalTikTokGetterReadsOnlyTheRequestedSpeedCache() throws Exception {
        String supplied = System.getenv("TOKAI_TEST_APK");
        File apk = supplied == null ? new File("analysis/tiktok-47.5.3/apks/base.apk") : new File(supplied);
        if (supplied == null && !apk.isFile()) apk = new File("../analysis/tiktok-47.5.3/apks/base.apk");
        Assume.assumeTrue("Original TikTok APK is required for this static contract", apk.isFile());
        assertSame(HostAdaptation.PROFILE_TIKTOK, HostApkProfile.read(apk));
        Map<String, ClassDef> classes = new HashMap<>();
        try (ZipFile archive = new ZipFile(apk)) {
            for (String entry : List.of("classes2.dex", "classes5.dex")) {
                try (BufferedInputStream in = new BufferedInputStream(archive.getInputStream(archive.getEntry(entry)))) {
                    for (ClassDef type : DexBackedDexFile.fromInputStream(Opcodes.getDefault(), in).getClasses()) {
                        if (List.of("LX/037e;", "LX/03go;", "LX/08Dz;").contains(type.getType())) {
                            classes.put(type.getType(), ImmutableClassDef.of(type));
                        }
                    }
                }
            }
        }
        Method manager = getter(classes.get("LX/037e;"));
        assertEquals(List.of("LX/03AX;->LJIILJJIL()LX/08Dy;", "LX/08Dy;->getSpeed()F"), calls(manager));
        ClassDef bridge = classes.get("LX/08Dz;");
        assertNotNull(bridge);
        assertTrue(bridge.getInterfaces().contains("LX/08Dy;"));
        assertEquals(List.of("LX/03gn;->getSpeed()F"), calls(getter(bridge)));
        ClassDef info = classes.get("LX/03go;");
        assertNotNull(info);
        assertTrue(info.getInterfaces().contains("LX/03gn;"));
        Method cached = getter(info);
        assertTrue("The getter never queries an effective engine value", calls(cached).isEmpty());
        List<String> fields = new ArrayList<>();
        List<String> opcodes = new ArrayList<>();
        for (Instruction instruction : cached.getImplementation().getInstructions()) {
            opcodes.add(instruction.getOpcode().name());
            if (!(instruction instanceof ReferenceInstruction)) continue;
            Reference ref = ((ReferenceInstruction) instruction).getReference();
            if (ref instanceof FieldReference) {
                FieldReference field = (FieldReference) ref;
                fields.add(field.getDefiningClass() + "->" + field.getName() + ":" + field.getType());
            }
        }
        assertEquals(List.of("LX/03go;->LIZJ:LX/03gZ;", "LX/03gZ;->LIZ:F"), fields);
        assertEquals("The original getter only loads and returns the cached float",
                List.of("IGET_OBJECT", "IGET", "RETURN"), opcodes);
    }

    private static Method getter(ClassDef owner) {
        assertNotNull(owner);
        for (Method method : owner.getMethods()) {
            if ("getSpeed".equals(method.getName()) && method.getParameterTypes().isEmpty()
                    && "F".equals(method.getReturnType())) return method;
        }
        throw new AssertionError("Missing original getSpeed()F: " + owner.getType());
    }

    private static List<String> calls(Method method) {
        List<String> result = new ArrayList<>();
        for (Instruction instruction : method.getImplementation().getInstructions()) {
            if (!(instruction instanceof ReferenceInstruction)) continue;
            Reference reference = ((ReferenceInstruction) instruction).getReference();
            if (!(reference instanceof MethodReference)) continue;
            MethodReference call = (MethodReference) reference;
            StringBuilder descriptor = new StringBuilder(call.getDefiningClass()).append("->")
                    .append(call.getName()).append('(');
            for (CharSequence parameter : call.getParameterTypes()) descriptor.append(parameter);
            result.add(descriptor.append(')').append(call.getReturnType()).toString());
        }
        return result;
    }
}
