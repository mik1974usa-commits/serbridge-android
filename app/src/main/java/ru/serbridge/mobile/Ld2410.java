package ru.serbridge.mobile;

/**
 * Разбор потока радара HLK-LD2410 (LD2410B/C) по его выходу TX.
 * Кадр данных:   F4 F3 F2 F1 | длина (2 байта, LE) | данные | F8 F7 F6 F5
 *   данные: тип (01 — инженерный, 02 — обычный), AA, состояние цели, дистанция движения (2),
 *           энергия движения, дистанция неподвижной цели (2), её энергия, дистанция обнаружения (2),
 *           [инженерный: N, M, энергии ворот движения ×(N+1), неподвижности ×(M+1), освещённость, OUT],
 *           55, 00
 * Ответ на команду: FD FC FB FA | длина | слово команды | состояние | … | 04 03 02 01
 * Не потокобезопасен.
 */
final class Ld2410 {

    static final class Report {
        int state;            // 0 — никого, 1 — движение, 2 — неподвижная цель, 3 — то и другое
        int moveCm, moveEnergy, stillCm, stillEnergy, detectCm;
        boolean engineering;
        int[] moveGates, stillGates;   // только в инженерном режиме
        int light = -1, out = -1;      // только в инженерном режиме LD2410B/C
    }

    interface Sink {
        void report(Report r);

        void ack(int cmd, int status, int length);
    }

    private static final int MAX_LEN = 128;          // самый длинный кадр LD2410 — около 45 байт данных
    private final byte[] buf = new byte[512];
    private int n;

    long frames;          // правильных кадров
    long badFrames;       // заголовок есть, но кадр испорчен
    long junkBytes;       // байт вне кадров

    void reset() {
        n = 0;
    }

    void feed(byte[] b, int off, int len, Sink sink) {
        while (len > 0) {
            int k = Math.min(len, buf.length - n);
            if (k == 0) {                  // буфер забит мусором без конца кадра — выбросить половину
                junkBytes += buf.length / 2;
                System.arraycopy(buf, buf.length / 2, buf, 0, n - buf.length / 2);
                n -= buf.length / 2;
                continue;
            }
            System.arraycopy(b, off, buf, n, k);
            n += k;
            off += k;
            len -= k;
            parse(sink);
        }
    }

    private int u8(int i) {
        return buf[i] & 0xFF;
    }

    private int u16(int i) {
        return u8(i) | (u8(i + 1) << 8);
    }

    private boolean isHeader(int i) {
        return (u8(i) == 0xF4 && u8(i + 1) == 0xF3 && u8(i + 2) == 0xF2 && u8(i + 3) == 0xF1)
                || (u8(i) == 0xFD && u8(i + 1) == 0xFC && u8(i + 2) == 0xFB && u8(i + 3) == 0xFA);
    }

    private void parse(Sink sink) {
        int i = 0;
        while (true) {
            int h = -1;
            for (int j = i; j + 4 <= n; j++) {
                if (isHeader(j)) {
                    h = j;
                    break;
                }
            }
            if (h < 0) {                         // заголовка нет: оставить 3 байта — вдруг это его начало
                int keep = Math.min(3, n - i);
                junkBytes += (n - i) - keep;
                compact(n - keep);
                return;
            }
            junkBytes += h - i;
            if (n - h < 6) {
                compact(h);
                return;
            }
            int len = u16(h + 4);
            if (len > MAX_LEN) {
                badFrames++;
                i = h + 1;
                continue;
            }
            int total = 4 + 2 + len + 4;
            if (n - h < total) {
                compact(h);
                return;
            }
            int t = h + 6 + len;
            boolean data = u8(h) == 0xF4;
            boolean tailOk = data
                    ? u8(t) == 0xF8 && u8(t + 1) == 0xF7 && u8(t + 2) == 0xF6 && u8(t + 3) == 0xF5
                    : u8(t) == 0x04 && u8(t + 1) == 0x03 && u8(t + 2) == 0x02 && u8(t + 3) == 0x01;
            if (!tailOk) {
                badFrames++;
                i = h + 1;
                continue;
            }
            if (data) {
                Report r = decode(h + 6, len);
                if (r == null) {
                    badFrames++;
                } else {
                    frames++;
                    sink.report(r);
                }
            } else if (len >= 2) {
                frames++;
                int word = u16(h + 6);
                sink.ack(word & ~0x0100, len >= 4 ? u16(h + 8) : -1, len);
            } else {
                badFrames++;
            }
            i = h + total;
        }
    }

    private void compact(int from) {
        if (from <= 0) return;
        System.arraycopy(buf, from, buf, 0, n - from);
        n -= from;
    }

    private Report decode(int d, int len) {
        if (len < 13) return null;
        int type = u8(d);
        if ((type != 1 && type != 2) || u8(d + 1) != 0xAA) return null;
        if (u8(d + len - 2) != 0x55 || u8(d + len - 1) != 0x00) return null;
        Report r = new Report();
        r.state = u8(d + 2);
        if (r.state > 3) return null;
        r.moveCm = u16(d + 3);
        r.moveEnergy = u8(d + 5);
        r.stillCm = u16(d + 6);
        r.stillEnergy = u8(d + 8);
        r.detectCm = u16(d + 9);
        if (type == 1) {
            int end = len - 2;                    // перед 55 00
            int p = 11;
            if (p + 2 > end) return null;
            int nm = u8(d + p), ns = u8(d + p + 1);
            p += 2;
            if (nm > 8 || ns > 8 || p + nm + 1 + ns + 1 > end) return null;
            r.engineering = true;
            r.moveGates = new int[nm + 1];
            for (int g = 0; g <= nm; g++) r.moveGates[g] = u8(d + p + g);
            p += nm + 1;
            r.stillGates = new int[ns + 1];
            for (int g = 0; g <= ns; g++) r.stillGates[g] = u8(d + p + g);
            p += ns + 1;
            if (end - p >= 2) {
                r.light = u8(d + p);
                r.out = u8(d + p + 1);
            }
        }
        return r;
    }

    static String commandName(int cmd) {
        switch (cmd) {
            case 0x00FF: return "вход в режим настройки";
            case 0x00FE: return "выход из режима настройки";
            case 0x0060: return "дальность и задержка";
            case 0x0061: return "чтение параметров";
            case 0x0062: return "инженерный режим вкл.";
            case 0x0063: return "инженерный режим выкл.";
            case 0x0064: return "чувствительность ворот";
            case 0x00A0: return "версия прошивки";
            case 0x00A1: return "скорость порта";
            case 0x00A2: return "заводские настройки";
            case 0x00A3: return "перезапуск";
            case 0x00A4: return "Bluetooth";
            case 0x00A5: return "MAC-адрес";
            case 0x00AA: return "разрешение ворот (запись)";
            case 0x00AB: return "разрешение ворот (чтение)";
            case 0x00AD: return "OUT и освещённость (запись)";
            case 0x00AE: return "OUT и освещённость (чтение)";
            default: return null;
        }
    }
}
