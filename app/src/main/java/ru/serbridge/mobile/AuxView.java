package ru.serbridge.mobile;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Показ второго потока (XP2.6 → порт 2327): текст, шестнадцатеричный дамп или расшифровка
 * радара LD2410. В режиме auto кадры LD2410 узнаются сами, остальное — текстом или hex.
 * Все методы — из одного потока (главного).
 */
final class AuxView {

    static final int RAW = 0, INFO = 1, GOOD = 2, WARN = 3;

    static final class Line {
        final String text;
        final int kind;
        final boolean event;        // событие (с меткой времени всегда), а не данные

        Line(String text, int kind, boolean event) {
            this.text = text;
            this.kind = kind;
            this.event = event;
        }
    }

    static final String[] MODES = {"auto", "text", "hex", "ld2410", "off"};

    String mode = "auto";
    int ldPeriodSec = 0;            // 0 — строки радара только при смене «есть/нет человека»

    final Ld2410 ld = new Ld2410();
    Ld2410.Report last;             // последнее показание радара
    long lastReportMs;
    private boolean ldAnnounced;
    private long lastLdLineMs;
    private int prevPresence = -1;  // 0 — никого, 1 — есть; -1 — неизвестно
    private long bytesSinceFrame;
    private boolean noFramesWarned;
    long rxBytes;

    // текст
    private final StringBuilder partial = new StringBuilder();
    private byte[] carry = new byte[0];
    private long partialMs;

    void setMode(String m) {
        mode = m;
        partial.setLength(0);
        carry = new byte[0];
        ld.reset();
        bytesSinceFrame = 0;
        noFramesWarned = false;
    }

    /** Поток подключился заново: начатое не продолжится. */
    void onReconnect() {
        partial.setLength(0);
        carry = new byte[0];
        ld.reset();
        prevPresence = -1;
    }

    boolean ldActive(long now) {
        return last != null && now - lastReportMs <= 3000;
    }

    void feed(byte[] b, long now, List<Line> out) {
        rxBytes += b.length;
        if (mode.equals("off")) return;
        boolean wantLd = mode.equals("auto") || mode.equals("ld2410");
        long before = ld.frames;
        if (wantLd) {
            final long t = now;
            ld.feed(b, 0, b.length, new Ld2410.Sink() {
                @Override
                public void report(Ld2410.Report r) {
                    onReport(r, t, out);
                }

                @Override
                public void ack(int cmd, int status, int length) {
                    String name = Ld2410.commandName(cmd);
                    String s = String.format(Locale.US, "LD2410: ответ на команду 0x%04X", cmd)
                            + (name != null ? " (" + name + ")" : "")
                            + (status < 0 ? "" : status == 0 ? " — выполнена" : " — ошибка " + status);
                    out.add(new Line(s, status > 0 ? WARN : INFO, true));
                }
            });
        }
        boolean gotFrames = ld.frames != before;
        if (mode.equals("ld2410") || (mode.equals("auto") && (gotFrames || ldActive(now)))) {
            if (gotFrames) {
                bytesSinceFrame = 0;
                noFramesWarned = false;
            } else {
                bytesSinceFrame += b.length;
                if (mode.equals("ld2410") && bytesSinceFrame > 300 && !noFramesWarned) {
                    noFramesWarned = true;
                    out.add(new Line("LD2410: байты приходят, но кадров радара в них нет — проверьте скорость"
                            + " (у радара по умолчанию 256000: «:aux uart 256000») или выберите «:auxview hex»",
                            WARN, true));
                }
            }
            return;
        }
        if (mode.equals("hex") || (mode.equals("auto") && !looksLikeText(withCarry(b)))) {
            flushPartial(out);
            carry = new byte[0];
            hexLines(b, out);
        } else {
            text(b, now, out);
        }
    }

    /** Раз в секунду: дописать строку, которой давно не приходит конец. */
    void tick(long now, List<Line> out) {
        if (partial.length() > 0 && now - partialMs > 300) flushPartial(out);
    }

    private void onReport(Ld2410.Report r, long now, List<Line> out) {
        if (!ldAnnounced) {
            ldAnnounced = true;
            out.add(new Line("Распознан радар LD2410: его состояние — на панели; «:auxview ld2410 1» — строка"
                    + " каждую секунду, «:auxview hex» — сырые кадры", INFO, true));
        }
        last = r;
        lastReportMs = now;
        int presence = r.state == 0 ? 0 : 1;
        if (presence != prevPresence) {
            if (prevPresence >= 0 || presence == 1) {
                out.add(new Line("LD2410: " + (presence == 1 ? "человек обнаружен — " + describe(r) : "никого нет"),
                        presence == 1 ? GOOD : INFO, true));
                lastLdLineMs = now;
            }
            prevPresence = presence;
        } else if (ldPeriodSec > 0 && now - lastLdLineMs >= ldPeriodSec * 1000L - 50) {
            out.add(new Line("LD2410: " + describe(r) + gates(r), RAW, false));
            lastLdLineMs = now;
        }
    }

    static String describe(Ld2410.Report r) {
        switch (r.state) {
            case 1:
                return "движение " + r.moveCm + " см (энергия " + r.moveEnergy + ")";
            case 2:
                return "неподвижная цель " + r.stillCm + " см (энергия " + r.stillEnergy + ")";
            case 3:
                return "движение " + r.moveCm + " см (" + r.moveEnergy + "), неподвижная цель "
                        + r.stillCm + " см (" + r.stillEnergy + ")";
            default:
                return "никого нет";
        }
    }

    private static String gates(Ld2410.Report r) {
        if (!r.engineering) return "";
        StringBuilder sb = new StringBuilder(" | ворота: движ.");
        for (int g : r.moveGates) sb.append(' ').append(g);
        sb.append(", неподв.");
        for (int g : r.stillGates) sb.append(' ').append(g);
        if (r.light >= 0) sb.append(", свет ").append(r.light).append(", OUT ").append(r.out);
        return sb.toString();
    }

    /** Строка для панели; null — радара нет. */
    String panelText(long now) {
        if (last == null || now - lastReportMs > 10000) return null;
        if (now - lastReportMs > 3000) return "нет данных";
        Ld2410.Report r = last;
        switch (r.state) {
            case 1:
                return "движ. " + r.moveCm + " см/" + r.moveEnergy;
            case 2:
                return "неподв. " + r.stillCm + " см/" + r.stillEnergy;
            case 3:
                return "движ. " + r.moveCm + " см/" + r.moveEnergy + ", неподв. " + r.stillCm + " см/" + r.stillEnergy;
            default:
                return "никого";
        }
    }

    // ---- текст и hex

    static boolean looksLikeText(byte[] b) {
        int i = 0;
        while (i < b.length) {
            int x = b[i] & 0xFF;
            if (x < 0x80) {
                if (x < 0x20 && x != '\n' && x != '\r' && x != '\t') return false;
                if (x == 0x7F) return false;
                i++;
                continue;
            }
            int need = x >= 0xF0 && x < 0xF8 ? 3 : x >= 0xE0 ? 2 : x >= 0xC2 && x < 0xE0 ? 1 : -1;
            if (need < 0 || x >= 0xF8) return false;
            for (int k = 1; k <= need; k++) {
                if (i + k >= b.length) return true;      // символ разрезан между кусками — допустимо
                if ((b[i + k] & 0xC0) != 0x80) return false;
            }
            i += need + 1;
        }
        return true;
    }

    /** Кусок вместе с началом символа UTF-8, оставшимся от предыдущего куска. */
    private byte[] withCarry(byte[] b) {
        if (carry.length == 0) return b;
        byte[] all = Arrays.copyOf(carry, carry.length + b.length);
        System.arraycopy(b, 0, all, carry.length, b.length);
        return all;
    }

    private void text(byte[] b, long now, List<Line> out) {
        byte[] all = withCarry(b);
        int cut = all.length;
        for (int i = all.length - 1; i >= Math.max(0, all.length - 3); i--) {
            int x = all[i] & 0xFF;
            if ((x & 0xC0) == 0x80) continue;
            int need = x >= 0xF0 ? 4 : x >= 0xE0 ? 3 : x >= 0xC0 ? 2 : 1;
            if (all.length - i < need) cut = i;
            break;
        }
        carry = Arrays.copyOfRange(all, cut, all.length);
        String s = new String(all, 0, cut, StandardCharsets.UTF_8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') {
                out.add(new Line(partial.toString(), RAW, false));
                partial.setLength(0);
            } else if (c >= ' ' || c == '\t') {
                if (partial.length() == 0) partialMs = now;
                partial.append(c == '\t' ? "    " : String.valueOf(c));
                if (partial.length() >= 2000) flushPartial(out);
            }
        }
    }

    private void flushPartial(List<Line> out) {
        if (partial.length() == 0) return;
        out.add(new Line(partial.toString(), RAW, false));
        partial.setLength(0);
    }

    private static void hexLines(byte[] b, List<Line> out) {
        for (int off = 0; off < b.length; off += 32) {
            int end = Math.min(b.length, off + 32);
            StringBuilder sb = new StringBuilder(96);
            for (int i = off; i < end; i++) {
                if (i > off) sb.append(' ');
                sb.append(String.format(Locale.US, "%02X", b[i] & 0xFF));
            }
            out.add(new Line(sb.toString(), RAW, false));
        }
    }
}
