package com.tokai.module.scan;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Semantic aliases proved against TikTok 47.5.3 layouts and their owning Assem classes. */
public final class TikTokFeedResources {
    private static final Map<Integer, Integer> IDS = ids();
    private static final Map<Integer, String> NAMES = names();
    private TikTokFeedResources() { }
    public static int id(int canonical) { return IDS.getOrDefault(canonical, -1); }
    public static int canonicalId(int actual) {
        for (Map.Entry<Integer, Integer> entry : IDS.entrySet()) if (entry.getValue() == actual) return entry.getKey();
        return -1;
    }
    public static String name(int canonical) { return NAMES.get(id(canonical)); }
    public static Map<Integer, String> names() {
        Map<Integer, String> values = new LinkedHashMap<>();
        values.put(2131402965, "widget_container");
        values.put(2131377734, "lke");
        values.put(2131401356, "user_avatar");
        values.put(2131374112, "ixi");
        values.put(2131374130, "iy0");
        values.put(2131364148, "bn7");
        values.put(2131370430, "g90");
        values.put(2131370425, "g8v");
        values.put(2131370431, "g91");
        values.put(2131370432, "g92");
        values.put(2131370438, "g98");
        values.put(2131368349, "epr");
        values.put(2131368350, "eps");
        values.put(2131368128, "ejs");
        values.put(2131368149, "ekc");
        values.put(2131368131, "ejv");
        values.put(2131392465, "wbl");
        values.put(2131392321, "w8p");
        values.put(2131392344, "w9b");
        values.put(2131373210, "i_5");
        values.put(2131383414, "ppy");
        values.put(2131381373, "o8s");
        values.put(2131396722, "title");
        values.put(2131401999, "video_seek_bar");
        // VideoDescAssem / bad.xml and VideoAuthorInfoRelationAssem / bap.xml.
        values.put(2131368631, "exd");
        values.put(2131373377, "idn");
        values.put(2131367915, "ee1");
        values.put(2131399722, "tv_post_time");
        // EarlyFeedbackButtonAssem / baj.xml and VideoProgressBarAssem / bcy.xml.
        values.put(2131390619, "uzp");
        values.put(2131363871, "bfp");
        values.put(2131401959, "video_player_progress");
        // LandscapeEntranceAssem / bd6.xml: only the entry button, never the player.
        values.put(2131374470, "j77");
        // VideoUserCardAssem / bcr.xml and VideoViewCell's independent bottom tool area.
        values.put(2131401351, "userCardLayout");
        values.put(2131396853, "zj7");
        values.put(2131377722, "lk3");
        return Collections.unmodifiableMap(values);
    }
    private static Map<Integer, Integer> ids() {
        Map<Integer, Integer> values = new LinkedHashMap<>();
        values.put(2131362267, 2131402965);
        values.put(2131402182, 2131377734);
        values.put(2131415727, 2131401356);
        values.put(2131379772, 2131374112);
        values.put(2131379761, 2131374130);
        values.put(2131366240, 2131364148);
        values.put(2131374347, 2131370430);
        values.put(2131374327, 2131370425);
        values.put(2131374339, 2131370431);
        values.put(2131374340, 2131370432);
        values.put(2131374360, 2131370438);
        values.put(2131371414, 2131368349);
        values.put(2131371285, 2131368350);
        values.put(2131371299, 2131368128);
        values.put(2131371376, 2131368149);
        values.put(2131371605, 2131368131);
        values.put(2131401927, 2131392465);
        values.put(2131400515, 2131392321);
        values.put(2131405175, 2131392344);
        values.put(2131371073, 2131373210);
        values.put(2131416104, 2131383414);
        values.put(2131416105, 2131381373);
        values.put(2131398470, 2131396722);
        values.put(2131378139, 2131401999);
        values.put(2131368631, 2131368631);
        values.put(2131373377, 2131373377);
        values.put(2131370902, 2131367915);
        values.put(2131415966, 2131399722);
        values.put(2131371909, 2131390619);
        values.put(2131363871, 2131363871);
        values.put(2131369833, 2131401959);
        values.put(2131393278, 2131374470);
        values.put(2131388590, 2131401351);
        values.put(2131396853, 2131396853);
        values.put(2131367357, 2131377722);
        return Collections.unmodifiableMap(values);
    }
}
