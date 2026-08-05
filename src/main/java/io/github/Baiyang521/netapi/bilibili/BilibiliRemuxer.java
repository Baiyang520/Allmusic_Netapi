package io.github.Baiyang521.netapi.bilibili;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class BilibiliRemuxer {
    private BilibiliRemuxer() {
    }

    public static byte[] toM4A(byte[] source) throws Exception {
        List<Box> boxes = parseBoxes(source);
        Box moov = findBox(boxes, "moov");
        if (moov == null) {
            throw new IllegalStateException("Bilibili audio has no moov box");
        }
        Box stsd = findDescendant(source, moov, "stsd");
        if (stsd == null) {
            throw new IllegalStateException("Bilibili audio has no stsd box");
        }
        Box mdhd = findDescendant(source, moov, "mdhd");
        long timescale = mdhd == null ? 48000L : readTimescale(source, mdhd);
        if (timescale <= 0L) {
            timescale = 48000L;
        }
        List<Sample> samples = collectSamples(source, boxes);
        if (samples.isEmpty()) {
            throw new IllegalStateException("Bilibili audio has no samples");
        }
        return buildM4A(source, copyBox(source, stsd), timescale, samples);
    }

    private static List<Sample> collectSamples(byte[] source, List<Box> boxes) throws Exception {
        List<Sample> samples = new ArrayList<>();
        int pendingStart = 0;
        boolean waitingForMdat = false;
        for (Box box : boxes) {
            if (box.type.equals("moof")) {
                pendingStart = samples.size();
                parseMoof(source, box, samples);
                waitingForMdat = true;
            } else if (box.type.equals("mdat") && waitingForMdat) {
                int payload = box.offset + box.headerSize;
                for (int i = pendingStart; i < samples.size(); i++) {
                    Sample sample = samples.get(i);
                    sample.offset = payload;
                    payload += sample.size;
                }
                waitingForMdat = false;
            }
        }
        if (waitingForMdat) {
            throw new IllegalStateException("Bilibili audio moof has no following mdat");
        }
        return samples;
    }

    private static void parseMoof(byte[] source, Box moof, List<Sample> samples) throws Exception {
        for (Box child : childBoxes(source, moof)) {
            if (!child.type.equals("traf")) {
                continue;
            }
            long defaultDuration = 0L;
            long defaultSize = 0L;
            for (Box trafChild : childBoxes(source, child)) {
                if (trafChild.type.equals("tfhd")) {
                    long[] defaults = parseTfhd(source, trafChild);
                    defaultDuration = defaults[0];
                    defaultSize = defaults[1];
                } else if (trafChild.type.equals("trun")) {
                    parseTrun(source, trafChild, defaultDuration, defaultSize, samples);
                }
            }
        }
    }

    private static long[] parseTfhd(byte[] source, Box box) {
        int flags = readInt(source, box.offset + 8) & 0xFFFFFF;
        int cursor = box.offset + 16;
        if ((flags & 0x1) != 0) {
            cursor += 8;
        }
        if ((flags & 0x2) != 0) {
            cursor += 4;
        }
        long defaultDuration = 0L;
        long defaultSize = 0L;
        if ((flags & 0x8) != 0) {
            defaultDuration = readInt(source, cursor) & 0xFFFFFFFFL;
            cursor += 4;
        }
        if ((flags & 0x10) != 0) {
            defaultSize = readInt(source, cursor) & 0xFFFFFFFFL;
            cursor += 4;
        }
        return new long[]{defaultDuration, defaultSize};
    }

    private static void parseTrun(byte[] source, Box box, long defaultDuration,
                                  long defaultSize, List<Sample> samples) throws Exception {
        int flags = readInt(source, box.offset + 8) & 0xFFFFFF;
        int count = readInt(source, box.offset + 12);
        int cursor = box.offset + 16;
        if ((flags & 0x1) != 0) {
            cursor += 4;
        }
        if ((flags & 0x4) != 0) {
            cursor += 4;
        }
        for (int i = 0; i < count; i++) {
            long duration = defaultDuration;
            long size = defaultSize;
            if ((flags & 0x100) != 0) {
                duration = readInt(source, cursor) & 0xFFFFFFFFL;
                cursor += 4;
            }
            if ((flags & 0x200) != 0) {
                size = readInt(source, cursor) & 0xFFFFFFFFL;
                cursor += 4;
            }
            if ((flags & 0x400) != 0) {
                cursor += 4;
            }
            if ((flags & 0x800) != 0) {
                cursor += 4;
            }
            if (size <= 0L || size > Integer.MAX_VALUE) {
                continue;
            }
            samples.add(new Sample(duration, (int) size));
        }
    }

    private static byte[] buildM4A(byte[] source, byte[] stsdBox, long timescale,
                                   List<Sample> samples) throws Exception {
        long totalDuration = 0L;
        int totalSize = 0;
        for (Sample sample : samples) {
            totalDuration += sample.duration;
            totalSize += sample.size;
        }
        byte[] mdatData = new byte[totalSize];
        int position = 0;
        for (Sample sample : samples) {
            System.arraycopy(source, sample.offset, mdatData, position, sample.size);
            position += sample.size;
        }

        long durationMillis = totalDuration * 1000L / timescale;
        byte[] stts = buildStts(samples);
        byte[] stsc = buildStsc(samples.size());
        byte[] stsz = buildStsz(samples);
        byte[] stco = buildStco(0);

        ByteArrayOutputStream moov = new ByteArrayOutputStream();
        moov.write(buildMvhd(durationMillis));
        writeBox(moov, "trak", buildTrak(stsdBox, timescale, totalDuration, stts, stsc, stsz, stco));
        byte[] firstMoov = moov.toByteArray();

        long mdatPayloadOffset = 28L + 8L + firstMoov.length + 8L;
        if (mdatPayloadOffset > Integer.MAX_VALUE) {
            throw new IllegalStateException("Remuxed MP4 is too large");
        }
        stco = buildStco((int) mdatPayloadOffset);
        moov = new ByteArrayOutputStream();
        moov.write(buildMvhd(durationMillis));
        writeBox(moov, "trak", buildTrak(stsdBox, timescale, totalDuration, stts, stsc, stsz, stco));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(buildFtyp());
        writeBox(output, "moov", moov.toByteArray());
        writeBox(output, "mdat", mdatData);
        return output.toByteArray();
    }

    private static byte[] buildTrak(byte[] stsdBox, long timescale, long totalDuration,
                                    byte[] stts, byte[] stsc, byte[] stsz, byte[] stco) throws Exception {
        ByteArrayOutputStream stbl = new ByteArrayOutputStream();
        stbl.write(stsdBox);
        stbl.write(stts);
        stbl.write(stsc);
        stbl.write(stsz);
        stbl.write(stco);

        ByteArrayOutputStream minf = new ByteArrayOutputStream();
        minf.write(buildSmhd());
        minf.write(buildDinf());
        writeBox(minf, "stbl", stbl.toByteArray());

        ByteArrayOutputStream mdia = new ByteArrayOutputStream();
        mdia.write(buildMdhd(timescale, totalDuration));
        mdia.write(buildHdlr());
        writeBox(mdia, "minf", minf.toByteArray());

        ByteArrayOutputStream trak = new ByteArrayOutputStream();
        trak.write(buildTkhd(totalDuration * 1000L / timescale));
        writeBox(trak, "mdia", mdia.toByteArray());
        return trak.toByteArray();
    }

    private static byte[] buildFtyp() throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write("M4A ".getBytes(StandardCharsets.US_ASCII));
        writeInt(payload, 0);
        payload.write("M4A ".getBytes(StandardCharsets.US_ASCII));
        payload.write("mp42".getBytes(StandardCharsets.US_ASCII));
        payload.write("isom".getBytes(StandardCharsets.US_ASCII));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeBox(output, "ftyp", payload.toByteArray());
        return output.toByteArray();
    }

    private static byte[] buildMvhd(long durationMillis) throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeInt(payload, 0);
        writeInt(payload, 0);
        writeInt(payload, 1000);
        writeInt(payload, (int) Math.min(durationMillis, 0xFFFFFFFFL));
        writeInt(payload, 0x00010000);
        writeShort(payload, 0x0100);
        writeShort(payload, 0);
        writeInt(payload, 0);
        writeInt(payload, 0);
        writeMatrix(payload);
        for (int i = 0; i < 6; i++) {
            writeInt(payload, 0);
        }
        writeInt(payload, 2);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeFullBox(output, "mvhd", 0, 0, payload.toByteArray());
        return output.toByteArray();
    }

    private static byte[] buildTkhd(long durationMillis) throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeInt(payload, 0);
        writeInt(payload, 0);
        writeInt(payload, 1);
        writeInt(payload, 0);
        writeInt(payload, (int) Math.min(durationMillis, 0xFFFFFFFFL));
        writeInt(payload, 0);
        writeInt(payload, 0);
        writeShort(payload, 0);
        writeShort(payload, 0);
        writeShort(payload, 0x0100);
        writeShort(payload, 0);
        writeMatrix(payload);
        writeInt(payload, 0);
        writeInt(payload, 0);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeFullBox(output, "tkhd", 0, 3, payload.toByteArray());
        return output.toByteArray();
    }

    private static byte[] buildMdhd(long timescale, long totalDuration) throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeInt(payload, 0);
        writeInt(payload, 0);
        writeInt(payload, (int) timescale);
        writeInt(payload, (int) Math.min(totalDuration, 0xFFFFFFFFL));
        writeShort(payload, 0x55C4);
        writeShort(payload, 0);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeFullBox(output, "mdhd", 0, 0, payload.toByteArray());
        return output.toByteArray();
    }

    private static byte[] buildHdlr() throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeInt(payload, 0);
        payload.write("soun".getBytes(StandardCharsets.US_ASCII));
        writeInt(payload, 0);
        writeInt(payload, 0);
        writeInt(payload, 0);
        payload.write("SoundHandler".getBytes(StandardCharsets.US_ASCII));
        payload.write(0);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeFullBox(output, "hdlr", 0, 0, payload.toByteArray());
        return output.toByteArray();
    }

    private static byte[] buildSmhd() throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeShort(payload, 0);
        writeShort(payload, 0);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeFullBox(output, "smhd", 0, 0, payload.toByteArray());
        return output.toByteArray();
    }

    private static byte[] buildDinf() throws Exception {
        ByteArrayOutputStream urlPayload = new ByteArrayOutputStream();
        urlPayload.write(0);
        urlPayload.write(0);
        urlPayload.write(0);
        urlPayload.write(1);
        ByteArrayOutputStream url = new ByteArrayOutputStream();
        writeBox(url, "url ", urlPayload.toByteArray());

        ByteArrayOutputStream drefPayload = new ByteArrayOutputStream();
        writeInt(drefPayload, 1);
        drefPayload.write(url.toByteArray());
        ByteArrayOutputStream dref = new ByteArrayOutputStream();
        writeFullBox(dref, "dref", 0, 0, drefPayload.toByteArray());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeBox(output, "dinf", dref.toByteArray());
        return output.toByteArray();
    }

    private static byte[] buildStts(List<Sample> samples) throws Exception {
        List<long[]> entries = new ArrayList<>();
        long currentDuration = samples.get(0).duration;
        long currentCount = 1L;
        for (int i = 1; i < samples.size(); i++) {
            long duration = samples.get(i).duration;
            if (duration == currentDuration) {
                currentCount++;
            } else {
                entries.add(new long[]{currentCount, currentDuration});
                currentDuration = duration;
                currentCount = 1L;
            }
        }
        entries.add(new long[]{currentCount, currentDuration});

        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeInt(payload, entries.size());
        for (long[] entry : entries) {
            writeInt(payload, (int) entry[0]);
            writeInt(payload, (int) entry[1]);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeFullBox(output, "stts", 0, 0, payload.toByteArray());
        return output.toByteArray();
    }

    private static byte[] buildStsc(int sampleCount) throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeInt(payload, 1);
        writeInt(payload, 1);
        writeInt(payload, sampleCount);
        writeInt(payload, 1);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeFullBox(output, "stsc", 0, 0, payload.toByteArray());
        return output.toByteArray();
    }

    private static byte[] buildStsz(List<Sample> samples) throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeInt(payload, 0);
        writeInt(payload, samples.size());
        for (Sample sample : samples) {
            writeInt(payload, sample.size);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeFullBox(output, "stsz", 0, 0, payload.toByteArray());
        return output.toByteArray();
    }

    private static byte[] buildStco(int chunkOffset) throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeInt(payload, 1);
        writeInt(payload, chunkOffset);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeFullBox(output, "stco", 0, 0, payload.toByteArray());
        return output.toByteArray();
    }

    private static void writeMatrix(ByteArrayOutputStream out) throws Exception {
        writeInt(out, 0x00010000);
        writeInt(out, 0);
        writeInt(out, 0);
        writeInt(out, 0);
        writeInt(out, 0x00010000);
        writeInt(out, 0);
        writeInt(out, 0);
        writeInt(out, 0);
        writeInt(out, 0x40000000);
    }

    private static void writeFullBox(ByteArrayOutputStream out, String type,
                                     int version, int flags, byte[] content) throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(version & 0xFF);
        payload.write((flags >>> 16) & 0xFF);
        payload.write((flags >>> 8) & 0xFF);
        payload.write(flags & 0xFF);
        payload.write(content);
        writeBox(out, type, payload.toByteArray());
    }

    private static void writeBox(ByteArrayOutputStream out, String type, byte[] payload) throws Exception {
        int size = 8 + payload.length;
        writeInt(out, size);
        out.write(type.getBytes(StandardCharsets.US_ASCII));
        out.write(payload);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static List<Box> parseBoxes(byte[] source) throws Exception {
        List<Box> boxes = new ArrayList<>();
        int offset = 0;
        while (offset + 8 <= source.length) {
            Box box = readBox(source, offset, source.length);
            boxes.add(box);
            if (box.size < 8) {
                break;
            }
            offset += box.size;
        }
        return boxes;
    }

    private static Box findBox(List<Box> boxes, String type) {
        for (Box box : boxes) {
            if (box.type.equals(type)) {
                return box;
            }
        }
        return null;
    }

    private static Box findDescendant(byte[] source, Box parent, String type) throws Exception {
        for (Box child : childBoxes(source, parent)) {
            if (child.type.equals(type)) {
                return child;
            }
            if (isContainer(child.type)) {
                Box found = findDescendant(source, child, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean isContainer(String type) {
        return type.equals("moov") || type.equals("trak") || type.equals("mdia")
                || type.equals("minf") || type.equals("stbl") || type.equals("dinf")
                || type.equals("edts") || type.equals("udta") || type.equals("mvex")
                || type.equals("moof") || type.equals("traf");
    }

    private static List<Box> childBoxes(byte[] source, Box parent) throws Exception {
        List<Box> children = new ArrayList<>();
        int offset = parent.offset + parent.headerSize;
        int end = parent.offset + parent.size;
        while (offset + 8 <= end) {
            Box box = readBox(source, offset, end);
            children.add(box);
            if (box.size < 8) {
                break;
            }
            offset += box.size;
        }
        return children;
    }

    private static Box readBox(byte[] source, int offset, int end) throws Exception {
        if (offset + 8 > source.length) {
            throw new IllegalStateException("Truncated MP4 box");
        }
        int size = readInt(source, offset);
        int headerSize = 8;
        if (size == 1) {
            if (offset + 16 > source.length) {
                throw new IllegalStateException("Truncated large MP4 box");
            }
            long largeSize = 0L;
            for (int i = 0; i < 8; i++) {
                largeSize = (largeSize << 8) | (source[offset + 8 + i] & 0xFFL);
            }
            if (largeSize > Integer.MAX_VALUE) {
                throw new IllegalStateException("MP4 box is too large");
            }
            size = (int) largeSize;
            headerSize = 16;
        } else if (size == 0) {
            size = end - offset;
        }
        if (size < headerSize || offset + size > source.length) {
            throw new IllegalStateException("Invalid MP4 box size");
        }
        String type = new String(source, offset + 4, 4, StandardCharsets.US_ASCII);
        return new Box(type, offset, size, headerSize);
    }

    private static long readTimescale(byte[] source, Box mdhd) {
        int version = source[mdhd.offset + 8] & 0xFF;
        if (version == 1) {
            return readInt(source, mdhd.offset + 28) & 0xFFFFFFFFL;
        }
        return readInt(source, mdhd.offset + 20) & 0xFFFFFFFFL;
    }

    private static byte[] copyBox(byte[] source, Box box) {
        byte[] data = new byte[box.size];
        System.arraycopy(source, box.offset, data, 0, box.size);
        return data;
    }

    private static int readInt(byte[] source, int offset) {
        return ((source[offset] & 0xFF) << 24)
                | ((source[offset + 1] & 0xFF) << 16)
                | ((source[offset + 2] & 0xFF) << 8)
                | (source[offset + 3] & 0xFF);
    }

    private static final class Box {
        private final String type;
        private final int offset;
        private final int size;
        private final int headerSize;

        private Box(String type, int offset, int size, int headerSize) {
            this.type = type;
            this.offset = offset;
            this.size = size;
            this.headerSize = headerSize;
        }
    }

    private static final class Sample {
        private final long duration;
        private final int size;
        private int offset;

        private Sample(long duration, int size) {
            this.duration = duration;
            this.size = size;
        }
    }
}
