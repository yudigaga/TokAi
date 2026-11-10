package com.tokai.module.i18n;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.LocaleList;
import com.tokai.module.R;
import com.tokai.module.config.SettingsRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "en")
public final class LocalizedResourceContractTest {
    // Explicit positions distinguish placeholders from URL escapes such as %2F.
    private static final Pattern FORMAT = Pattern.compile(
            "%%|%n|%([1-9][0-9]*)\\$[-#+ 0,(<]*[0-9]*(?:\\.[0-9]+)?([tT]?[a-zA-Z])");

    @Test public void allCatalogMessagesResolveAndFormatWithoutChangingHostOrSystemLocale() throws Exception {
        Path res = Paths.get("src/main/res");
        if (!Files.isDirectory(res)) res = Paths.get("app/src/main/res");
        assertTrue("Cannot locate module resources", Files.isDirectory(res));
        Map<String, Message> english = read(res.resolve("values"));
        String[] directories = {"values-b+zh+Hans", "values-b+zh+Hant", "values-ko", "values-ja", "values"};
        String[] languages = {"zh-Hans", "zh-Hant", "ko", "ja", "en"};
        Context host = RuntimeEnvironment.getApplication();
        host.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit().clear().commit();
        SettingsRepository store = new SettingsRepository(host);
        ModuleLocale.bind(host, store);
        Configuration originalHost = new Configuration(host.getResources().getConfiguration());
        LocaleList originalSystem = Resources.getSystem().getConfiguration().getLocales();
        Locale originalDefault = Locale.getDefault();
        int rendered = 0, formatted = 0;
        try {
            for (int language = 0; language < languages.length; language++) {
                Map<String, Message> translated = read(res.resolve(directories[language]));
                assertEquals(directories[language] + " resource keys", english.keySet(), translated.keySet());
                ModuleLocale.select(store, languages[language]);
                for (Map.Entry<String, Message> entry : translated.entrySet()) {
                    String label = languages[language] + "/" + entry.getKey();
                    Message message = entry.getValue();
                    assertEquals(label + " argument positions/types", english.get(entry.getKey()).signature, message.signature);
                    int id = R.string.class.getField(entry.getKey()).getInt(null);
                    String resolved = UiText.get(id);
                    assertFalse(label + " resolved empty", resolved.trim().isEmpty());
                    if (!message.signature.isEmpty()) {
                        try {
                            assertFalse(label + " formatted empty", UiText.get(id, message.arguments()).trim().isEmpty());
                        } catch (RuntimeException failure) {
                            throw new AssertionError(label + " could not format: " + resolved, failure);
                        }
                        formatted++;
                    }
                    assertEquals(label + " changed host configuration", originalHost, host.getResources().getConfiguration());
                    assertEquals(label + " changed system locales", originalSystem, Resources.getSystem().getConfiguration().getLocales());
                    assertEquals(label + " changed Java default", originalDefault, Locale.getDefault());
                    rendered++;
                }
            }
        } finally {
            ModuleLocale.select(store, "system");
        }
        assertTrue("No catalog messages exercised", rendered > 0);
        assertTrue("No formatted messages exercised", formatted > 0);
        System.out.println("Localized resource contract: keys=" + english.size()
                + "; languages=" + languages.length + "; rendered=" + rendered + "; formatted=" + formatted);
    }

    @Test public void formatContractAllowsReorderingAndRepetitionAndIgnoresLiteralPercentText() {
        assertEquals(new Message("%1$s %2$d", true).signature,
                new Message("%2$d %1$s %1$s", true).signature);
        assertTrue(new Message("https://example.test/a%2Fb and %%1$s", true).signature.isEmpty());
        assertTrue(new Message("Protocol example: %1$s, 100%", false).signature.isEmpty());
        assertNotEquals(new Message("%1$s", true).signature, new Message("%1$d", true).signature);
    }

    private static Map<String, Message> read(Path directory) throws Exception {
        Map<String, Message> messages = new LinkedHashMap<>();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.getFileName().toString().matches("strings.*\\.xml"))
                    .sorted()::iterator) {
                NodeList strings = factory.newDocumentBuilder().parse(file.toFile()).getElementsByTagName("string");
                for (int index = 0; index < strings.getLength(); index++) {
                    Element element = (Element) strings.item(index);
                    String key = element.getAttribute("name"), text = element.getTextContent();
                    assertFalse(file + " missing name", key.isEmpty());
                    assertFalse(file + "/" + key + " is empty", text.trim().isEmpty() || "\"\"".equals(text.trim()));
                    Message previous = messages.put(key, new Message(text, !"false".equals(element.getAttribute("formatted"))));
                    assertNull(file + "/" + key + " duplicate ID", previous);
                }
            }
        }
        assertFalse(directory + " has no strings", messages.isEmpty());
        return messages;
    }

    private static final class Message {
        final Map<Integer, Set<String>> signature = new TreeMap<>();
        Message(String text, boolean formatted) {
            if (!formatted) return;
            Matcher matcher = FORMAT.matcher(text);
            while (matcher.find()) {
                if (matcher.group(1) == null) continue;
                int position = Integer.parseInt(matcher.group(1));
                assertTrue("Unreasonable format argument position: " + position, position <= 100);
                signature.computeIfAbsent(position, ignored -> new LinkedHashSet<>())
                        .add(matcher.group(2).toLowerCase(Locale.ROOT));
            }
        }
        Object[] arguments() {
            int count = signature.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
            Object[] result = new Object[count];
            Arrays.fill(result, "sample");
            for (Map.Entry<Integer, Set<String>> entry : signature.entrySet()) {
                for (String type : entry.getValue()) {
                    if (type.startsWith("t") && type.length() == 2) result[entry.getKey() - 1] = new Date(0);
                    else if ("dox".contains(type)) result[entry.getKey() - 1] = 7L;
                    else if ("efga".contains(type)) result[entry.getKey() - 1] = 1.25d;
                    else if ("c".equals(type)) result[entry.getKey() - 1] = 'A';
                    else assertTrue("Unsupported format conversion: " + type, "bhs".contains(type));
                }
            }
            return result;
        }
    }
}
