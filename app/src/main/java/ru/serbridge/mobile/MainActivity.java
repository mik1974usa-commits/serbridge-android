package ru.serbridge.mobile;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Мобильная консоль беспроводного моста SerialBridge — аналог serbridge.exe:
 * вывод МК, отправка строк, панель напряжений, питание МК и команды «:…».
 */
public class MainActivity extends Activity implements Bridge.Listener, Meter.Events {

    // ---- цвета
    private static final int C_TEXT = 0xFFE0E0E0;   // вывод МК
    private static final int C_INFO = 0xFF4FC3F7;
    private static final int C_WARN = 0xFFFFD54F;
    private static final int C_ERR = 0xFFEF5350;
    private static final int C_GOOD = 0xFF81C784;
    private static final int C_DIM = 0xFF8A8F96;
    private static final int C_LABEL = 0xFF4DB6AC;
    private static final int C_AUX = 0xFFCE93D8;    // второй поток (XP2.6)

    private static final int MAX_LINES = 5000;
    private static final int[] BAUDS = {9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600};
    private static final String[] EOL_NAMES = {"lf", "crlf", "cr", "none"};
    private static final String[] EOL_VALUES = {"\n", "\r\n", "\r", ""};

    // ---- пункты меню
    private static final int M_RESET = 1, M_FIND = 2, M_CONNECT = 3, M_BAUD = 4, M_EOL = 5, M_TIME = 6, M_HEX = 7,
            M_MINMAX = 8, M_SCREEN = 9, M_FONT = 10, M_CLEAR = 11, M_SHARE = 12, M_HELP = 13, M_STATUS = 14;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Bridge bridge;
    private final Meter meter = new Meter();
    private final AuxView aux = new AuxView();
    private SharedPreferences prefs;

    private TextView panel;
    private ListView list;
    private EditText input;
    private TerminalAdapter adapter;

    // ---- терминал (только главный поток)
    private final ArrayList<CharSequence> lines = new ArrayList<>();
    private final StringBuilder partial = new StringBuilder();
    private byte[] utf8Carry = new byte[0];
    private final ConcurrentLinkedQueue<byte[]> pending = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean flushScheduled = new AtomicBoolean();
    private final ArrayList<String> history = new ArrayList<>();

    // ---- настройки
    private int eol;              // индекс в EOL_VALUES
    private boolean timestamps, hex, showMinMax, keepScreen;
    private float fontSp;

    // ---- состояние связи
    private boolean dataUp, discovering, started;
    private String lastFail;
    private String bridgeName;
    private int bridgeBaud;

    // =================================================================== жизненный цикл

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("serbridge", MODE_PRIVATE);
        eol = prefs.getInt("eol", 0);
        timestamps = prefs.getBoolean("time", false);
        hex = prefs.getBoolean("hex", false);
        showMinMax = prefs.getBoolean("minmax", true);
        keepScreen = prefs.getBoolean("screen", true);
        fontSp = prefs.getFloat("font", 13f);
        aux.setMode(prefs.getString("auxview", "auto"));
        aux.ldPeriodSec = prefs.getInt("auxperiod", 0);
        if (keepScreen) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        panel = findViewById(R.id.panel);
        list = findViewById(R.id.terminal);
        input = findViewById(R.id.input);
        Button send = findViewById(R.id.send);

        adapter = new TerminalAdapter();
        list.setAdapter(adapter);
        list.setTranscriptMode(ListView.TRANSCRIPT_MODE_NORMAL);

        send.setOnClickListener(v -> submit());
        send.setOnLongClickListener(v -> {
            showHistory();
            return true;
        });
        input.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_SEND || enter) {
                submit();
                return true;
            }
            return false;
        });
        panel.setOnClickListener(v -> {
            showMinMax = !showMinMax;
            prefs.edit().putBoolean("minmax", showMinMax).apply();
            renderPanel();
        });
        panel.setOnLongClickListener(v -> {
            meter.resetMinMax();
            renderPanel();
            Toast.makeText(this, "Мин/макс сброшены", Toast.LENGTH_SHORT).show();
            return true;
        });

        bridge = new Bridge(this);
        String h = prefs.getString("host", null);
        if (h != null) {
            bridge.setTarget(h, prefs.getInt("data", Bridge.DATA_PORT), prefs.getInt("ctrl", Bridge.CTRL_PORT),
                    prefs.getInt("meter", Bridge.METER_PORT));
            bridgeName = prefs.getString("name", null);
        }
        info("SerialBridge для Android. Строка без «:» уходит в МК, «:help» — список команд.");
        info("Нажатие на панель напряжений — показать/скрыть мин/макс, долгое нажатие — сбросить их.");
        updateSubtitle();
    }

    @Override
    protected void onStart() {
        super.onStart();
        watchWifi(true);
        bridge.start();
        ui.removeCallbacks(tick);
        ui.post(tick);
        if (bridge.host() == null && !started) discover(true);
        started = true;
    }

    @Override
    protected void onStop() {
        // Пока приложение свёрнуто, мост копит вывод МК (до 32 КБ) и отдаст его при возвращении.
        bridge.stop();
        watchWifi(false);
        ui.removeCallbacks(tick);                    // в фоне панель обновлять незачем
        dataUp = false;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        bridge.shutdown();
        super.onDestroy();
    }

    // ---- привязка к Wi-Fi
    // Если у сети Wi-Fi нет выхода в интернет (например, сеть самого моста SerBridge-XXXX),
    // Android шлёт трафик приложений через мобильные данные, и мост недоступен.
    // Поэтому, пока есть Wi-Fi, весь сетевой обмен приложения идёт именно через него.
    private ConnectivityManager.NetworkCallback wifiCallback;

    private void watchWifi(boolean on) {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        try {
            if (on && wifiCallback == null) {
                wifiCallback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        cm.bindProcessToNetwork(network);
                        bridge.reconnect();          // соединения, начатые не через Wi-Fi, — заново
                    }

                    @Override
                    public void onLost(Network network) {
                        // При переходе между сетями Wi-Fi новая может появиться раньше, чем пропадёт старая
                        if (network.equals(cm.getBoundNetworkForProcess())) cm.bindProcessToNetwork(null);
                    }
                };
                NetworkRequest req = new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .build();
                cm.registerNetworkCallback(req, wifiCallback);
            } else if (!on && wifiCallback != null) {
                cm.unregisterNetworkCallback(wifiCallback);
                wifiCallback = null;
                cm.bindProcessToNetwork(null);
            }
        } catch (RuntimeException e) {
            wifiCallback = null;                     // без привязки — работаем через сеть по умолчанию
        }
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            List<AuxView.Line> out = new ArrayList<>();
            aux.tick(System.currentTimeMillis(), out);
            showAuxLines(out);
            renderPanel();
            updateSubtitle();
            ui.postDelayed(this, 1000);
        }
    };

    // =================================================================== Bridge.Listener (сетевые потоки)

    @Override
    public void onDataBytes(byte[] data) {
        pending.add(data);
        if (flushScheduled.compareAndSet(false, true)) ui.postDelayed(flush, 40);
    }

    @Override
    public void onDataState(boolean connected, String why) {
        ui.post(() -> {
            if (connected) {
                dataUp = true;
                lastFail = null;
                good(stamp() + "Подключено к мосту " + bridge.host());
                bridge.command("status", r -> ui.post(() -> takeStatus(r)));
            } else {
                if (dataUp) {
                    warn(stamp() + "Связь с мостом потеряна (" + why + ") — переподключаюсь");
                } else if (why != null && !why.equals(lastFail)) {
                    warn(stamp() + "Нет связи с " + bridge.host() + ": " + why + ". Повторяю каждые 2 с; "
                            + "«:find» — найти мост заново.");
                }
                lastFail = why;
                dataUp = false;
            }
            updateSubtitle();
        });
    }

    @Override
    public void onMeterLine(String line) {
        ui.post(() -> {
            meter.onLine(line, this);
            renderPanel();
        });
    }

    @Override
    public void onMeterState(boolean connected, String why) {
        if (connected) return;
        ui.post(() -> {
            if (why != null) warn(stamp() + why);
            meter.onDisconnected();
            renderPanel();
        });
    }

    @Override
    public void onSendFailed(String why) {
        ui.post(() -> warn(why));
    }

    @Override
    public void onAuxBytes(byte[] data) {
        ui.post(() -> {
            List<AuxView.Line> out = new ArrayList<>();
            aux.feed(data, System.currentTimeMillis(), out);
            showAuxLines(out);
            if (aux.last != null) renderPanel();
        });
    }

    @Override
    public void onAuxState(boolean connected) {
        ui.post(() -> {
            List<AuxView.Line> out = new ArrayList<>();
            aux.tick(Long.MAX_VALUE, out);        // недописанную строку — показать
            showAuxLines(out);
            aux.onReconnect();
        });
    }

    /** Строки второго потока: «AUX›» и свой цвет; события — всегда с меткой времени. */
    private void showAuxLines(List<AuxView.Line> out) {
        for (AuxView.Line l : out) {
            String t = (l.event ? stamp() : timestamps ? clock() : "") + "AUX› " + l.text;
            int c = l.kind == AuxView.GOOD ? C_GOOD : l.kind == AuxView.WARN ? C_WARN
                    : l.kind == AuxView.INFO ? C_INFO : C_AUX;
            addLine(t, c);
        }
    }

    // =================================================================== Meter.Events (главный поток)

    @Override
    public void info(String text) {
        addLine(text, C_INFO);
    }

    @Override
    public void warn(String text) {
        addLine(text, C_WARN);
    }

    @Override
    public void good(String text) {
        addLine(text, C_GOOD);
    }

    @Override
    public void alarm(String text) {
        addLine(text, C_ERR);
    }

    private void error(String text) {
        addLine(text, C_ERR);
    }

    // =================================================================== терминал

    private final Runnable flush = new Runnable() {
        @Override
        public void run() {
            flushScheduled.set(false);
            byte[] b;
            boolean any = false;
            while ((b = pending.poll()) != null) {
                any = true;
                if (hex) appendHex(b);
                else appendText(decodeUtf8(b));
            }
            if (any) {
                trim();
                adapter.notifyDataSetChanged();
            }
        }
    };

    /** UTF-8 с переносом незавершённого многобайтного символа в следующий кусок. */
    private String decodeUtf8(byte[] b) {
        byte[] all = b;
        if (utf8Carry.length > 0) {
            all = Arrays.copyOf(utf8Carry, utf8Carry.length + b.length);
            System.arraycopy(b, 0, all, utf8Carry.length, b.length);
        }
        int cut = all.length;
        for (int i = all.length - 1; i >= Math.max(0, all.length - 3); i--) {
            int x = all[i] & 0xFF;
            if ((x & 0xC0) == 0x80) continue;           // продолжение символа
            int need = x >= 0xF0 ? 4 : x >= 0xE0 ? 3 : x >= 0xC0 ? 2 : 1;
            if (all.length - i < need) cut = i;
            break;
        }
        utf8Carry = Arrays.copyOfRange(all, cut, all.length);
        return new String(all, 0, cut, StandardCharsets.UTF_8);
    }

    private void appendText(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') {
                commitPartial();
            } else if (c == '\r') {
                // CR LF → один перевод строки
            } else if (c >= ' ' || c == '\t') {
                if (partial.length() == 0 && timestamps) partial.append(clock());
                partial.append(c == '\t' ? "    " : String.valueOf(c));
                if (partial.length() > 4000) commitPartial();
            }
        }
    }

    private void commitPartial() {
        lines.add(partial.toString());
        partial.setLength(0);
    }

    private void appendHex(byte[] b) {
        for (int off = 0; off < b.length; off += 16) {
            int end = Math.min(b.length, off + 16);
            StringBuilder sb = new StringBuilder(80);
            if (timestamps) sb.append(clock());
            StringBuilder ascii = new StringBuilder(16);
            for (int i = off; i < end; i++) {
                int x = b[i] & 0xFF;
                sb.append(String.format(Locale.US, "%02X ", x));
                ascii.append(x >= 32 && x < 127 ? (char) x : '.');
            }
            for (int i = end; i < off + 16; i++) sb.append("   ");
            sb.append(' ').append(ascii);
            lines.add(sb.toString());
        }
    }

    /** Служебная строка (цветная). Встаёт перед недописанной строкой МК, не разрывая её. */
    private void addLine(String text, int color) {
        SpannableString ss = new SpannableString(text);
        ss.setSpan(new ForegroundColorSpan(color), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        lines.add(ss);
        trim();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void trim() {
        if (lines.size() > MAX_LINES) lines.subList(0, lines.size() - MAX_LINES + 500).clear();
    }

    private void clearTerminal() {
        lines.clear();
        partial.setLength(0);
        adapter.notifyDataSetChanged();
    }

    private final class TerminalAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return lines.size() + (partial.length() > 0 ? 1 : 0);
        }

        @Override
        public Object getItem(int position) {
            return position < lines.size() ? lines.get(position) : partial.toString();
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView tv = convertView instanceof TextView ? (TextView) convertView : new TextView(MainActivity.this);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp);
            tv.setTextColor(C_TEXT);
            tv.setPadding(0, 1, 0, 1);
            tv.setText((CharSequence) getItem(position));
            return tv;
        }
    }

    // =================================================================== панель напряжений

    private void renderPanel() {
        boolean showT = meter.targetFresh();
        boolean showV = meter.hasData;
        String radar = aux.panelText(System.currentTimeMillis());
        if (!showT && !showV && radar == null) {
            panel.setVisibility(View.GONE);
            return;
        }
        SpannableStringBuilder sb = new SpannableStringBuilder();
        String sep = showMinMax ? "\n" : "   ";
        if (showT && meter.tVcc != null) {
            put(sb, "МК ", C_LABEL);
            if (!meter.tSense) put(sb, Meter.f2(meter.tVcc) + " В", C_DIM);
            else if (Boolean.TRUE.equals(meter.tPwr)) put(sb, Meter.f2(meter.tVcc) + " В", C_GOOD);
            else put(sb, "без питания", C_ERR);
        }
        if (showV) {
            boolean stale = meter.isStale();
            for (int i = 0; i < Meter.N; i++) {
                Double v = meter.value[i];
                if (!meter.on[i] || v == null) continue;
                if (sb.length() > 0) sb.append(sep);
                put(sb, meter.names[i] + " ", stale ? C_DIM : C_LABEL);
                put(sb, Meter.f3(v) + " В", stale ? C_DIM : C_TEXT);
                Double mn = meter.sessMin[i], mx = meter.sessMax[i];
                if (showMinMax && mn != null && mx != null)
                    put(sb, " [" + Meter.f3(mn) + ".." + Meter.f3(mx) + "]", C_DIM);
            }
            if (stale) {
                if (sb.length() > 0) sb.append(sep);
                put(sb, "(нет новых данных)", C_DIM);
            }
        }
        if (radar != null) {
            if (sb.length() > 0) sb.append(sep);
            put(sb, "Радар ", C_LABEL);
            put(sb, radar, radar.equals("нет данных") ? C_DIM : radar.equals("никого") ? C_TEXT : C_GOOD);
        }
        panel.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp);
        panel.setText(sb);
        panel.setVisibility(View.VISIBLE);
    }

    private static void put(SpannableStringBuilder sb, String text, int color) {
        int start = sb.length();
        sb.append(text);
        sb.setSpan(new ForegroundColorSpan(color), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void updateSubtitle() {
        ActionBar ab = getActionBar();
        if (ab == null) return;
        String h = bridge.host();
        String s;
        if (discovering) s = "поиск моста…";
        else if (h == null) s = "мост не выбран — «:find» или «:connect»";
        else if (dataUp) s = (bridgeName != null ? bridgeName + " · " : "") + h
                + (bridgeBaud > 0 ? " · " + bridgeBaud + " бод" : "");
        else s = "нет связи с " + h + " — повтор…";
        ab.setSubtitle(s);
    }

    // =================================================================== ввод

    private void submit() {
        String t = input.getText().toString();
        input.setText("");
        if (!t.trim().isEmpty()) {
            history.remove(t);
            history.add(t);
            if (history.size() > 30) history.remove(0);
        }
        if (t.startsWith(":")) {
            addLine("› " + t, C_DIM);
            command(t.substring(1).trim());
        } else {
            sendToMcu(t);
        }
    }

    private void sendToMcu(String t) {
        if (Boolean.FALSE.equals(meter.targetPowered())) {
            warn("МК без питания (нет напряжения на XP2.3) — не отправлено.");
            return;
        }
        if (!dataUp) {
            warn("Нет связи с мостом — не отправлено.");
            return;
        }
        bridge.send((t + EOL_VALUES[eol]).getBytes(StandardCharsets.UTF_8));
        addLine("→ " + t, C_DIM);
    }

    private void showHistory() {
        if (history.isEmpty()) {
            Toast.makeText(this, "История пуста", Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] items = new String[history.size()];
        for (int i = 0; i < items.length; i++) items[i] = history.get(history.size() - 1 - i);
        new AlertDialog.Builder(this)
                .setTitle("История")
                .setItems(items, (d, which) -> {
                    input.setText(items[which]);
                    input.setSelection(items[which].length());
                })
                .show();
    }

    // =================================================================== команды «:…»

    private void command(String line) {
        String[] p = line.split("\\s+", 2);
        String cmd = p[0].toLowerCase(Locale.ROOT);
        String arg = p.length > 1 ? p[1].trim() : "";
        switch (cmd) {
            case "":
            case "help":
            case "?":
                showHelp();
                break;
            case "find":
                discover(false);
                break;
            case "connect":
                if (arg.isEmpty()) showConnectDialog();
                else connectTo(arg);
                break;
            case "status":
                bridge.command("status", r -> ui.post(() -> showStatus(r)));
                break;
            case "baud":
                if (arg.isEmpty()) showBaudDialog();
                else setBaud(arg);
                break;
            case "eol":
                setEol(arg);
                break;
            case "time":
                timestamps = !timestamps;
                prefs.edit().putBoolean("time", timestamps).apply();
                info("Метки времени " + (timestamps ? "включены" : "выключены"));
                break;
            case "hex":
                setHex(!hex);
                break;
            case "tx":
                sendHex(arg);
                break;
            case "clear":
                clearTerminal();
                break;
            case "reset":
                remote(arg.isEmpty() ? "reset" : "reset " + arg);
                break;
            case "aux":
                remote(arg.isEmpty() ? "aux" : "aux " + arg);
                break;
            case "auxview":
                auxView(arg);
                break;
            case "bridge":
                if (arg.isEmpty()) warn("Укажите команду мосту, например «:bridge help»");
                else remote(arg);
                break;
            case "v":
                voltmeter(arg);
                break;
            case "share":
                shareLog();
                break;
            default:
                warn("Неизвестная команда «:" + cmd + "». «:help» — список команд.");
        }
    }

    /** «:auxview [auto|text|hex|ld2410 [с]|off]» — как показывать второй поток (XP2.6). */
    private void auxView(String arg) {
        String[] p = arg.isEmpty() ? new String[0] : arg.toLowerCase(Locale.ROOT).split("\\s+");
        if (p.length == 0) {
            info("Второй поток (XP2.6): показ «" + aux.mode + "»" + (aux.ldPeriodSec > 0
                    ? ", радар — строка раз в " + aux.ldPeriodSec + " с" : "")
                    + "; принято " + aux.rxBytes + " байт; кадров LD2410: " + aux.ld.frames
                    + ", испорченных " + aux.ld.badFrames);
            info("Варианты: auto | text | hex | ld2410 [секунд] | off. Включить приём на мосту: «:aux uart 256000»");
            return;
        }
        if (!Arrays.asList(AuxView.MODES).contains(p[0])) {
            warn("Варианты: auto | text | hex | ld2410 [секунд] | off");
            return;
        }
        List<AuxView.Line> out = new ArrayList<>();
        aux.tick(Long.MAX_VALUE, out);
        showAuxLines(out);
        aux.setMode(p[0]);
        if (p[0].equals("ld2410") || p[0].equals("auto")) {
            int sec = p.length > 1 ? Bridge.toInt(p[1], -1) : 0;
            if (sec < 0 || sec > 3600) {
                warn("Период — целое число секунд 0…3600 (0 — только при появлении и уходе человека)");
                sec = 0;
            }
            aux.ldPeriodSec = sec;
        }
        prefs.edit().putString("auxview", aux.mode).putInt("auxperiod", aux.ldPeriodSec).apply();
        info("Второй поток (XP2.6): " + describeAuxMode());
        renderPanel();
    }

    private String describeAuxMode() {
        switch (aux.mode) {
            case "text": return "текст";
            case "hex": return "шестнадцатеричный вывод";
            case "ld2410": return "радар LD2410" + (aux.ldPeriodSec > 0 ? ", строка раз в " + aux.ldPeriodSec + " с" : "");
            case "off": return "не показывать";
            default: return "auto — радар LD2410 узнаётся сам, остальное текстом или hex";
        }
    }

    private void remote(String cmd) {
        bridge.command(cmd, r -> ui.post(() -> showReply(r)));
    }

    private void showReply(String r) {
        if (r.startsWith("OK")) {
            String rest = r.substring(2).trim();
            info(rest.isEmpty() ? "OK" : rest);
        } else {
            error(r);
        }
    }

    private void setBaud(String arg) {
        bridge.command("baud " + arg, r -> ui.post(() -> {
            showReply(r);
            if (r.startsWith("OK")) bridgeBaud = Bridge.toInt(arg, bridgeBaud);
            updateSubtitle();
        }));
    }

    private void setEol(String arg) {
        for (int i = 0; i < EOL_NAMES.length; i++) {
            if (EOL_NAMES[i].equalsIgnoreCase(arg)) {
                eol = i;
                prefs.edit().putInt("eol", eol).apply();
                info("Окончание строки: " + EOL_NAMES[i]);
                return;
            }
        }
        info("Окончание строки сейчас: " + EOL_NAMES[eol] + ". Варианты: lf, crlf, cr, none");
    }

    private void setHex(boolean on) {
        if (partial.length() > 0) commitPartial();
        utf8Carry = new byte[0];
        hex = on;
        prefs.edit().putBoolean("hex", hex).apply();
        info("Шестнадцатеричный вывод " + (hex ? "включён" : "выключен"));
    }

    private void sendHex(String arg) {
        String[] parts = arg.replace(",", " ").trim().split("\\s+");
        if (arg.trim().isEmpty()) {
            warn("Пример: :tx 01 02 FF");
            return;
        }
        byte[] out = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String h = parts[i].toLowerCase(Locale.ROOT);
            if (h.startsWith("0x")) h = h.substring(2);
            try {
                int v = Integer.parseInt(h, 16);
                if (v < 0 || v > 255) throw new NumberFormatException();
                out[i] = (byte) v;
            } catch (NumberFormatException e) {
                warn("Не байт: «" + parts[i] + "». Пример: :tx 01 02 FF");
                return;
            }
        }
        if (Boolean.FALSE.equals(meter.targetPowered())) {
            warn("МК без питания (нет напряжения на XP2.3) — не отправлено.");
            return;
        }
        if (!dataUp) {
            warn("Нет связи с мостом — не отправлено.");
            return;
        }
        bridge.send(out);
        info("Отправлено байт: " + out.length);
    }

    /** «:v …» — вольтметр. */
    private void voltmeter(String arg) {
        String[] p = arg.isEmpty() ? new String[0] : arg.split("\\s+");
        String sub = p.length > 0 ? p[0].toLowerCase(Locale.ROOT) : "";
        int ch = p.length > 1 ? Bridge.toInt(p[1], 0) : 0;
        boolean chOk = ch >= 1 && ch <= Meter.N;
        switch (sub) {
            case "reset":
                meter.resetMinMax();
                renderPanel();
                info("Мин/макс сброшены");
                return;
            case "mm":
                showMinMax = !showMinMax;
                prefs.edit().putBoolean("minmax", showMinMax).apply();
                renderPanel();
                return;
            case "alarm":
                alarm(p);
                return;
            case "name":
                if (!chOk || p.length < 3) break;
                remote("vname " + ch + " " + arg.split("\\s+", 3)[2]);
                return;
            case "cal":
                if (!chOk || p.length < 3) break;
                remote("vcalto " + ch + " " + p[2].replace(',', '.'));
                return;
            case "on":
            case "off":
                if (!chOk) break;
                remote("v" + sub + " " + ch);
                return;
            case "rate":
                if (p.length < 2) break;
                remote("vrate " + p[1]);
                return;
            default:
                break;
        }
        info(":v reset — сбросить мин/макс;   :v mm — показать/скрыть мин/макс");
        info(":v name 1 +5V — имя канала;   :v cal 1 4.98 — подогнать канал под мультиметр");
        info(":v on 3 / :v off 3 — включить/выключить канал;   :v rate 5 — отчётов в секунду");
        info(":v alarm 1 <4.75 >5.25 — пороги тревоги;   :v alarm 1 off — снять;   :v alarm — список");
    }

    private void alarm(String[] p) {
        if (p.length < 2) {
            boolean any = false;
            for (int i = 0; i < Meter.N; i++) {
                if (meter.alarmLo[i] == null && meter.alarmHi[i] == null) continue;
                any = true;
                info(meter.names[i] + ": " + (meter.alarmLo[i] != null ? "< " + Meter.f3(meter.alarmLo[i]) + " В  " : "")
                        + (meter.alarmHi[i] != null ? "> " + Meter.f3(meter.alarmHi[i]) + " В" : ""));
            }
            if (!any) info("Порогов тревоги нет. Пример: :v alarm 1 <4.75 >5.25");
            return;
        }
        int ch = Bridge.toInt(p[1], 0);
        if (ch < 1 || ch > Meter.N) {
            warn("Номер канала 1…4");
            return;
        }
        int i = ch - 1;
        if (p.length > 2 && p[2].equalsIgnoreCase("off")) {
            meter.alarmLo[i] = null;
            meter.alarmHi[i] = null;
            info("Пороги канала " + ch + " сняты");
            return;
        }
        for (int k = 2; k < p.length; k++) {
            String t = p[k];
            if (t.length() < 2 || (t.charAt(0) != '<' && t.charAt(0) != '>')) {
                warn("Порог пишется как <4.75 или >5.25");
                return;
            }
            Double v = Meter.num(t.substring(1));
            if (v == null) {
                warn("Не число: «" + t + "»");
                return;
            }
            if (t.charAt(0) == '<') meter.alarmLo[i] = v;
            else meter.alarmHi[i] = v;
        }
        info("Пороги канала " + ch + " заданы (действуют до закрытия приложения)");
    }

    private void showStatus(String r) {
        if (!r.startsWith("OK")) {
            error(r);
            return;
        }
        takeStatus(r);
        Map<String, String> kv = Bridge.parseKv(r);
        String rssi = v(kv, "rssi");
        info("Мост «" + v(kv, "name") + "»: " + v(kv, "ip") + ", режим " + v(kv, "mode")
                + (rssi.equals("0") || rssi.equals("?") ? "" : ", сигнал " + rssi + " дБм"));
        if (kv.containsKey("meters"))
            info("Подключено клиентов: поток данных " + v(kv, "client") + ", измерения " + v(kv, "meters"));
        info("UART: " + v(kv, "baud") + " бод; от МК принято " + v(kv, "rx") + " байт, в МК передано "
                + v(kv, "tx") + " байт; в буфере " + v(kv, "pending") + " байт, потеряно " + v(kv, "lost") + " байт");
        if (kv.containsKey("tvcc")) {
            String pw = "1".equals(kv.get("tpwr")) ? "связь включена" : "без питания, линии TX и RESET отключены";
            if ("0".equals(kv.get("tsense"))) pw = "контроль выключен (tsense off)";
            info("Питание МК: " + v(kv, "tvcc").replace('.', ',') + " В, " + pw);
        }
        info("АЦП: " + v(kv, "adc") + ", сбоев I2C " + v(kv, "adcerr"));
        if ("uart".equals(kv.get("auxmode"))) {
            info("AUX (XP2.6): приём UART " + v(kv, "auxbaud") + " бод; принято " + v(kv, "auxrx")
                    + " байт, ошибок кадра " + v(kv, "auxerr") + ", переполнений " + v(kv, "auxovf")
                    + "; клиентов " + v(kv, "auxcl"));
            if (aux.ld.frames > 0 || aux.ld.badFrames > 0)
                info("Радар LD2410: кадров " + aux.ld.frames + ", испорченных " + aux.ld.badFrames);
        } else if (kv.containsKey("aux")) {
            info("AUX (XP2.6): " + v(kv, "aux") + ("low".equals(kv.get("auxmode")) ? " — мост тянет к нулю" : " — отпущена"));
        }
        info("Работает " + uptime(Bridge.toInt(kv.get("uptime"), -1)) + "; свободно памяти " + v(kv, "heap") + " байт");
    }

    /** Имя и скорость моста — для строки под заголовком. */
    private void takeStatus(String r) {
        if (!r.startsWith("OK")) return;
        Map<String, String> kv = Bridge.parseKv(r);
        if (kv.containsKey("name")) {
            bridgeName = kv.get("name");
            prefs.edit().putString("name", bridgeName).apply();
        }
        bridgeBaud = Bridge.toInt(kv.get("baud"), bridgeBaud);
        updateSubtitle();
    }

    private static String v(Map<String, String> kv, String k) {
        String s = kv.get(k);
        return s == null ? "?" : s;
    }

    private static String uptime(int sec) {
        if (sec < 0) return "?";
        int d = sec / 86400, h = sec / 3600 % 24, m = sec / 60 % 60, s = sec % 60;
        return (d > 0 ? d + " д " : "") + String.format(Locale.US, "%02d:%02d:%02d", h, m, s);
    }

    private void showHelp() {
        info("Строка без «:» уходит в МК (с окончанием " + EOL_NAMES[eol] + "). Команды:");
        info(":find — найти мост;   :connect 192.168.1.50 — подключиться по адресу");
        info(":status — состояние моста;   :baud 57600 — скорость UART моста");
        info(":eol lf|crlf|cr|none — окончание строк;   :time — метки времени;   :hex — шестнадцатеричный вывод");
        info(":tx 01 02 FF — отправить байты;   :clear — очистить экран;   :share — поделиться журналом");
        info(":reset [мс] — сбросить МК (XP2.5);   :aux [0|1|pulse мс] — доп. линия XP2.6");
        info(":aux uart 256000 — XP2.6 становится вторым приёмником (TX другого устройства), :aux off — обратно");
        info(":auxview auto|text|hex|ld2410 [с]|off — как показывать второй поток (строки «AUX›»)");
        info(":v … — вольтметр (:v — подробнее);   :bridge <команда> — команда мосту (ssid, pass, name, tsense, tcal, help)");
        info("Долгое нажатие на ➤ — история строк. Остальное — в меню ⋮.");
    }

    // =================================================================== поиск и подключение

    private void discover(boolean auto) {
        if (discovering) return;
        discovering = true;
        updateSubtitle();
        info("Поиск моста в сети…");
        new Thread(() -> {
            List<Bridge.Found> found = Bridge.discover(getApplicationContext(), 1500);
            ui.post(() -> {
                discovering = false;
                onFound(found, auto);
                updateSubtitle();
            });
        }, "serbridge-find").start();
    }

    /** Диалоги нельзя показывать, если окно уже закрывается. */
    private boolean gone() {
        return isFinishing() || isDestroyed();
    }

    private void onFound(List<Bridge.Found> found, boolean auto) {
        if (found.isEmpty()) {
            warn("Мост не найден. Телефон должен быть в той же сети Wi-Fi, что и мост, "
                    + "или в сети самого моста SerBridge-XXXX (адрес 192.168.4.1).");
            if (auto || bridge.host() == null) showConnectDialog();
            return;
        }
        if (found.size() == 1) {
            connectTo(found.get(0));
            return;
        }
        if (gone()) return;
        final Bridge.Found[] arr = found.toArray(new Bridge.Found[0]);
        String[] items = new String[arr.length];
        for (int i = 0; i < arr.length; i++) items[i] = arr[i].toString();
        new AlertDialog.Builder(this)
                .setTitle("Найдено мостов: " + arr.length)
                .setItems(items, (d, which) -> connectTo(arr[which]))
                .show();
    }

    private void connectTo(Bridge.Found f) {
        bridgeName = f.name;
        bridgeBaud = f.baud;
        saveTarget(f.ip, f.data, f.ctrl, f.meter);
        info("Мост «" + f.name + "»: " + f.ip);
        bridge.setTarget(f.ip, f.data, f.ctrl, f.meter);
        updateSubtitle();
    }

    private void connectTo(String host) {
        bridgeName = null;
        bridgeBaud = 0;
        saveTarget(host, Bridge.DATA_PORT, Bridge.CTRL_PORT, Bridge.METER_PORT);
        info("Подключаюсь к " + host);
        bridge.setTarget(host, Bridge.DATA_PORT, Bridge.CTRL_PORT, Bridge.METER_PORT);
        updateSubtitle();
    }

    private void saveTarget(String host, int data, int ctrl, int meterPort) {
        dataUp = false;
        lastFail = null;
        meter.onDisconnected();
        prefs.edit().putString("host", host).putInt("data", data).putInt("ctrl", ctrl)
                .putInt("meter", meterPort).remove("name").apply();
    }

    private void showConnectDialog() {
        if (gone()) return;
        final EditText et = new EditText(this);
        String h = bridge.host();
        et.setText(h != null ? h : "192.168.4.1");
        et.setSelectAllOnFocus(true);
        et.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("Адрес моста")
                .setMessage("IP-адрес или имя, например 192.168.1.50. В сети самого моста — 192.168.4.1.")
                .setView(et)
                .setPositiveButton("Подключиться", (d, w) -> {
                    String s = et.getText().toString().trim();
                    if (!s.isEmpty()) connectTo(s);
                })
                .setNeutralButton("Искать", (d, w) -> discover(false))
                .setNegativeButton("Отмена", null)
                .show();
    }

    // =================================================================== меню

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, M_RESET, 0, "Сброс МК").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        menu.add(0, M_STATUS, 1, "Состояние моста");
        menu.add(0, M_FIND, 2, "Найти мост");
        menu.add(0, M_CONNECT, 3, "Адрес моста…");
        menu.add(0, M_BAUD, 4, "Скорость UART…");
        menu.add(0, M_EOL, 5, "Окончание строки…");
        menu.add(0, M_TIME, 6, "Метки времени").setCheckable(true);
        menu.add(0, M_HEX, 7, "HEX-вывод").setCheckable(true);
        menu.add(0, M_MINMAX, 8, "Мин/макс на панели").setCheckable(true);
        menu.add(0, M_SCREEN, 9, "Не гасить экран").setCheckable(true);
        menu.add(0, M_FONT, 10, "Размер шрифта…");
        menu.add(0, M_CLEAR, 11, "Очистить экран");
        menu.add(0, M_SHARE, 12, "Поделиться журналом");
        menu.add(0, M_HELP, 13, "Справка");
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(M_TIME).setChecked(timestamps);
        menu.findItem(M_HEX).setChecked(hex);
        menu.findItem(M_MINMAX).setChecked(showMinMax);
        menu.findItem(M_SCREEN).setChecked(keepScreen);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case M_RESET:
                remote("reset");
                return true;
            case M_STATUS:
                command("status");
                return true;
            case M_FIND:
                discover(false);
                return true;
            case M_CONNECT:
                showConnectDialog();
                return true;
            case M_BAUD:
                showBaudDialog();
                return true;
            case M_EOL:
                new AlertDialog.Builder(this)
                        .setTitle("Окончание отправляемых строк")
                        .setSingleChoiceItems(new String[]{"LF (\\n)", "CRLF (\\r\\n)", "CR (\\r)", "без окончания"}, eol,
                                (d, which) -> {
                                    setEol(EOL_NAMES[which]);
                                    d.dismiss();
                                })
                        .show();
                return true;
            case M_TIME:
                command("time");
                return true;
            case M_HEX:
                setHex(!hex);
                return true;
            case M_MINMAX:
                command("v mm");
                return true;
            case M_SCREEN:
                keepScreen = !keepScreen;
                prefs.edit().putBoolean("screen", keepScreen).apply();
                if (keepScreen) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                return true;
            case M_FONT:
                showFontDialog();
                return true;
            case M_CLEAR:
                clearTerminal();
                return true;
            case M_SHARE:
                shareLog();
                return true;
            case M_HELP:
                showHelp();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void showBaudDialog() {
        String[] items = new String[BAUDS.length];
        int sel = -1;
        for (int i = 0; i < BAUDS.length; i++) {
            items[i] = BAUDS[i] + " бод";
            if (BAUDS[i] == bridgeBaud) sel = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("Скорость UART моста")
                .setSingleChoiceItems(items, sel, (d, which) -> {
                    setBaud(String.valueOf(BAUDS[which]));
                    d.dismiss();
                })
                .show();
    }

    private void showFontDialog() {
        final int[] sizes = {10, 11, 12, 13, 14, 15, 16, 18, 20};
        String[] items = new String[sizes.length];
        int sel = -1;
        for (int i = 0; i < sizes.length; i++) {
            items[i] = sizes[i] + " sp";
            if (Math.round(fontSp) == sizes[i]) sel = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("Размер шрифта")
                .setSingleChoiceItems(items, sel, (d, which) -> {
                    fontSp = sizes[which];
                    prefs.edit().putFloat("font", fontSp).apply();
                    adapter.notifyDataSetChanged();
                    renderPanel();
                    d.dismiss();
                })
                .show();
    }

    private void shareLog() {
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, lines.size() - 3000);
        for (int i = from; i < lines.size(); i++) sb.append(lines.get(i)).append('\n');
        if (partial.length() > 0) sb.append(partial).append('\n');
        if (sb.length() > 400_000) sb.delete(0, sb.length() - 400_000);   // предел размера передачи
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "SerialBridge — журнал "
                + new SimpleDateFormat("dd.MM.yyyy HH:mm", Meter.RU).format(new Date()));
        i.putExtra(Intent.EXTRA_TEXT, sb.toString());
        startActivity(Intent.createChooser(i, "Поделиться журналом"));
    }

    // =================================================================== разное

    private static String stamp() {
        return Meter.stamp();
    }

    private static String clock() {
        return new SimpleDateFormat("[HH:mm:ss.SSS] ", Locale.US).format(new Date());
    }
}
