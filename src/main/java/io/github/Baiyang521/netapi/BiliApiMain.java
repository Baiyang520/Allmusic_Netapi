package io.github.Baiyang521.netapi;

import com.coloryr.allmusic.server.core.AllMusic;
import com.coloryr.allmusic.server.core.IMusicApi;
import com.coloryr.allmusic.server.core.music.LyricSave;
import com.coloryr.allmusic.server.core.objs.SearchMusicObj;
import com.coloryr.allmusic.server.core.objs.music.SearchPageObj;
import com.coloryr.allmusic.server.core.objs.music.SongInfoObj;
import io.github.Baiyang521.netapi.bilibili.BilibiliApi;
import io.github.Baiyang521.netapi.bilibili.BilibiliStreamServer;
import io.github.Baiyang521.netapi.bilibili.VideoInfo;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class BiliApiMain implements IMusicApi {
    private final BilibiliApi api = new BilibiliApi();

    public BiliApiMain() {
        AllMusic.log.data("<light_purple>[Bilibili]<yellow>Bilibili NetAPI loaded");
    }

    @Override
    public String getId() {
        return "bilibili";
    }

    @Override
    public SongInfoObj getMusic(String id, String player, boolean isList) {
        VideoInfo info = api.getVideo(id);
        if (info == null) {
            AllMusic.log.data("<light_purple>[Bilibili]<red>Video info not found: " + id);
            return null;
        }
        return new SongInfoObj(info.author, info.title, info.bvid, "", player,
                "Bilibili", isList, info.duration * 1000L, info.pic, false, null, getId());
    }

    @Override
    public SearchPageObj search(String[] name, boolean isDefault) {
        String keyword = buildKeyword(name, isDefault);
        if (keyword.isEmpty()) {
            return new SearchPageObj(new ArrayList<>(), 0, getId());
        }
        List<BilibiliApi.SearchResult> results = api.search(keyword);
        List<SearchMusicObj> music = new ArrayList<>();
        for (BilibiliApi.SearchResult item : results) {
            music.add(new SearchMusicObj(item.id, item.name, item.author, item.al));
        }
        return new SearchPageObj(music, Math.max(0, music.size() / 10), getId());
    }

    @Override
    public void setList(String id, Object sender) {
        AllMusic.log.data("<light_purple>[Bilibili]<yellow>Bilibili playlist is not supported yet");
    }

    @Override
    public LyricSave getLyric(String id) {
        return new LyricSave();
    }

    @Override
    public String getPlayUrl(String id) {
        VideoInfo info = api.getVideo(id);
        if (info == null) {
            AllMusic.log.data("<light_purple>[Bilibili]<red>Video info not found: " + id);
            return null;
        }
        String directUrl = api.getPlayUrl(id);
        if (directUrl == null) {
            AllMusic.log.data("<light_purple>[Bilibili]<red>Play URL not found: " + id);
            return null;
        }
        String key = info.bvid + "_" + info.cid;
        File dataFolder = AllMusic.side == null ? null : AllMusic.side.getFolder();
        String url = BilibiliStreamServer.createUrl(key, directUrl, dataFolder);
        if (url == null) {
            AllMusic.log.data("<light_purple>[Bilibili]<red>Unable to prepare playable stream: " + id);
        }
        return url;
    }

    @Override
    public boolean isBusy() {
        return false;
    }

    @Override
    public String getMusicId(String arg) {
        return api.normalizeId(arg);
    }

    @Override
    public boolean checkId(String id) {
        return api.checkId(id);
    }

    private static String buildKeyword(String[] args, boolean isDefault) {
        if (args == null || args.length == 0) {
            return "";
        }
        int start = isDefault ? 0 : 1;
        StringBuilder builder = new StringBuilder();
        for (int index = start; index < args.length; index++) {
            if (index > start) {
                builder.append(' ');
            }
            builder.append(args[index]);
        }
        return builder.toString().trim();
    }
}
