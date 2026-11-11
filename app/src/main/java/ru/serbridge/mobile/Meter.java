package ru.serbridge.mobile;

import java.util.Locale;
import java.util.Map;

/**
 * Состояние вольтметра и питания МК по строкам потока измерений (порт 2326):
 *   H adc=ADS1115 rate=5 err=0 on=1111 n1=… n2=…   — заголовок
 *   V t=… 1=среднее:мин:макс 2=… …                 — показания за интервал
 *   E noadc                                         — АЦП не отвечает
 *   T vcc=4.98 pwr=1 sense=1                        — питание отлаживаемого МК
 * Все методы — в главном потоке.
 */
final class Meter {

    interface Events {
        void info(String text);

        void warn(String text);

        void good(String text);

        void alarm(String text);
    }

    static final int N = 4;
    static final Locale RU = new Locale("ru", "RU");

    String adc = "?";
    final boolean[] on = {true, true, true, true};
    final String[] names = {"ВХ1", "ВХ2", "ВХ3", "ВХ4"};
    final Double[] value = new Double[N];
    final Double[] sessMin = new Double[N], sessMax = new Double[N];
    final Double[] alarmLo = new Double[N], alarmHi = new Double[N];
    private final boolean[] alarmActive = new boolean[N];

    boolean hasData;                 // приходят строки V
    long lastData;
    boolean noAdcReported, adcLostEpisode, ads1015Reported;

    Double tVcc;                     // питание МК; null — мост не сообщал
    Boolean tPwr;
    boolean tSense = true;
    long lastT;

    void onLine(String line, Events ev) {
        if (line.startsWith("H ")) onHeader(Bridge.parseKv(line), ev);
        else if (line.startsWith("V ")) onValues(Bridge.parseKv(line), ev);
        else if (line.startsWith("E noadc")) onNoAdc(ev);
        else if (line.startsWith("T ")) onTarget(Bridge.parseKv(line), ev);
    }

    /** Связь с потоком измерений пропала: данные больше не свежие. */
    void onDisconnected() {
        hasData = false;
        tVcc = null;
        tPwr = null;
    }

    private void onHeader(Map<String, String> kv, Events ev) {
        if (kv.containsKey("adc")) adc = kv.get("adc");
        String o = kv.get("on");
        if (o != null) {
            for (int i = 0; i < N && i < o.length(); i++) on[i] = o.charAt(i) == '1';
        }
        for (int i = 0; i < N; i++) {
            String n = kv.get("n" + (i + 1));
            if (n != null && !n.isEmpty()) names[i] = n;
        }
        if (adc.contains("1015") && !ads1015Reported) {
            ads1015Reported = true;
            ev.warn("Вместо ADS1115 стоит ADS1015 (12 бит): работать будет, но разрешение в 16 раз хуже.");
        }
    }

    private void onValues(Map<String, String> kv, Events ev) {
        long now = System.currentTimeMillis();
        if (adcLostEpisode) ev.good(stamp() + "АЦП ADS1115 снова на связи");
        adcLostEpisode = false;
        noAdcReported = false;
        hasData = true;
        lastData = now;
        for (int i = 0; i < N; i++) {
            String v = kv.get(String.valueOf(i + 1));
            if (v == null) {                   // канал выключен: после включения тревога сработает заново
                value[i] = null;
                alarmActive[i] = false;
                continue;
            }
            String[] p = v.split(":");
            Double avg = num(p[0]);
            Double mn = p.length > 1 ? num(p[1]) : avg;
            Double mx = p.length > 2 ? num(p[2]) : avg;
            value[i] = avg;
            if (mn != null && (sessMin[i] == null || mn < sessMin[i])) sessMin[i] = mn;
            if (mx != null && (sessMax[i] == null || mx > sessMax[i])) sessMax[i] = mx;
            checkAlarm(i, mn, mx, ev);
        }
    }

    private void checkAlarm(int i, Double mn, Double mx, Events ev) {
        String bad = null;
        if (alarmLo[i] != null && mn != null && mn < alarmLo[i])
            bad = names[i] + ": " + f3(mn) + " В < " + f3(alarmLo[i]) + " В";
        else if (alarmHi[i] != null && mx != null && mx > alarmHi[i])
            bad = names[i] + ": " + f3(mx) + " В > " + f3(alarmHi[i]) + " В";
        if (bad != null && !alarmActive[i]) ev.alarm(stamp() + "!!! " + bad);
        alarmActive[i] = bad != null;          // сообщить снова — только после возврата в норму
    }

    private void onNoAdc(Events ev) {
        boolean wasData = hasData;
        hasData = false;
        adc = "none";
        if (!noAdcReported) {
            if (wasData) {
                ev.warn(stamp() + "АЦП ADS1115 перестал отвечать — мост восстанавливает шину I2C");
                adcLostEpisode = true;
            } else {
                ev.info("Модуль ADS1115 не найден — напряжения ВХ1…ВХ4 не показываются.");
            }
        }
        noAdcReported = true;
    }

    private void onTarget(Map<String, String> kv, Events ev) {
        Double vcc = num(kv.get("vcc"));
        if (vcc == null) return;
        boolean pwr = "1".equals(kv.get("pwr"));
        Boolean before = tPwr;
        tVcc = vcc;
        tPwr = pwr;
        tSense = !"0".equals(kv.get("sense"));
        lastT = System.currentTimeMillis();
        if (before == null && !pwr)
            ev.warn("МК без питания (нет напряжения на XP2.3): мост отключил линии TX и RESET.");
        else if (Boolean.FALSE.equals(before) && pwr)
            ev.good(stamp() + "Питание МК появилось: " + f2(vcc) + " В — связь включена");
        else if (Boolean.TRUE.equals(before) && !pwr)
            ev.warn(stamp() + "Питание МК пропало (" + f2(vcc) + " В) — линии TX и RESET отключены");
    }

    void resetMinMax() {
        for (int i = 0; i < N; i++) {
            sessMin[i] = value[i];
            sessMax[i] = value[i];
        }
    }

    boolean isStale() {
        return hasData && System.currentTimeMillis() - lastData > 3000;
    }

    boolean targetFresh() {
        return tVcc != null && System.currentTimeMillis() - lastT <= 3000;
    }

    /** false — МК без питания (мост отключил TX); null — неизвестно. */
    Boolean targetPowered() {
        return targetFresh() ? tPwr : null;
    }

    static Double num(String s) {
        if (s == null || s.isEmpty() || s.equals("-")) return null;
        try {
            double d = Double.parseDouble(s.replace(',', '.'));
            return Double.isNaN(d) ? null : d;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String f3(double v) {
        return String.format(RU, "%.3f", v);
    }

    static String f2(double v) {
        return String.format(RU, "%.2f", v);
    }

    static String stamp() {
        return new java.text.SimpleDateFormat("[HH:mm:ss.SSS] ", RU).format(new java.util.Date());
    }
}
