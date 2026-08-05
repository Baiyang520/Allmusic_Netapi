package io.github.Baiyang521.netapi.bilibili;

public final class VideoInfo {
    public final String bvid;
    public final long aid;
    public final long cid;
    public final String title;
    public final String author;
    public final long duration;
    public final String pic;

    public VideoInfo(String bvid, long aid, long cid, String title,
                     String author, long duration, String pic) {
        this.bvid = bvid;
        this.aid = aid;
        this.cid = cid;
        this.title = title;
        this.author = author;
        this.duration = duration;
        this.pic = pic;
    }
}
