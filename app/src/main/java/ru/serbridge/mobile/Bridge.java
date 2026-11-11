package ru.serbridge.mobile;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Сетевая часть: те же порты, что у консоли Windows.
 *   2323/TCP — поток данных МК (прозрачный);
 *   2324/TCP — управление: строка-команда → строка-ответ «OK …» / «ERR …»;
 *   2325/UDP — поиск: «SERBRIDGE?» → «SERBRIDGE name=… data=… ctrl=… baud=…»;
 *   2326/TCP — измерения: строки H / V / E / T;
 *   2327/TCP — второй приёмник (XP2.6, «:aux uart <бод>»): байты как есть, только чтение.
 * Все методы Listener вызываются из сетевых потоков.
 */
public final class Bridge {

    public interface Listener {
        void onDataBytes(byte[] data);

        void onDataState(boolean connected, String why);

        void onMeterLine(String line);

        void onMeterState(boolean connected, String why);

        void onSendFailed(String why);

        void onAuxBytes(byte[] data);

        void onAuxState(boolean connected);
    }

    public interface Reply {
        void onReply(String reply);
    }

    /** Мост, ответивший на поиск. */
    public static final class Found {
        public final String ip;
        public final String name;
        public final int data, ctrl, meter, baud;

        Found(String ip, Map<String, String> kv) {
            this.ip = ip;
            this.name = kv.containsKey("name") ? kv.get("name") : "SerBridge";
            this.data = toInt(kv.get("data"), DATA_PORT);
            this.ctrl = toInt(kv.get("ctrl"), CTRL_PORT);
            this.meter = toInt(kv.get("meter"), METER_PORT);
            this.baud = toInt(kv.get("baud"), 0);
        }

        @Override
        public String toString() {
            return name + "  —  " + ip + (baud > 0 ? ",  " + baud + " бод" : "");
        }
    }

    public static final int DATA_PORT = 2323, CTRL_PORT = 2324, FIND_PORT = 2325, METER_PORT = 2326, AUX_PORT = 2327;
    private static final int K_DATA = 0, K_METER = 1, K_AUX = 2;
    private static final int METER_SILENCE_MS = 4000;   // мост шлёт строку хотя бы раз в секунду

    private final Listener listener;
    private final Object lock = new Object();
    // Отправка в МК и команды мосту — в разных потоках: долгая команда к недоступному мосту
    // (до 7 с ожидания) не должна задерживать строки, отправляемые в МК.
    private final ExecutorService sendIo = Executors.newSingleThreadExecutor();
    private final ExecutorService ctrlIo = Executors.newSingleThreadExecutor();

    private volatile String host;
    private volatile int dataPort = DATA_PORT, ctrlPort = CTRL_PORT, meterPort = METER_PORT;
    private volatile boolean running;
    private volatile int gen;                       // поколение потоков: после stop/start старые выходят
    // Открытые соединения (под lock). Сокет публикуется и закрывается под одной блокировкой
    // вместе с проверкой «поток ещё нужен»: иначе stop()/setTarget(), пришедшие, пока поток
    // подключался, могли бы его не закрыть, и он остался бы висеть лишним клиентом моста.
    private final Socket[] socks = new Socket[3];
    private final boolean[] kick = new boolean[3];  // под lock: «не ждать, переподключиться сразу»
    private Thread dataThread, meterThread, auxThread;

    public Bridge(Listener listener) {
        this.listener = listener;
    }

    public String host() {
        return host;
    }

    public boolean isDataConnected() {
        return sock(K_DATA) != null;
    }

    private Socket sock(int which) {
        synchronized (lock) {
            return socks[which];
        }
    }

    /** Опубликовать подключённый сокет; false — поток уже не нужен (остановлен или сменился мост). */
    private boolean publish(int which, Socket s, int g, String h) {
        synchronized (lock) {
            if (!alive(g) || !h.equals(host)) return false;
            socks[which] = s;
            return true;
        }
    }

    private void unpublish(int which, Socket s) {
        synchronized (lock) {
            if (socks[which] == s) socks[which] = null;
        }
    }

    /** Закрыть соединения (все или указанные) и разбудить потоки. */
    private void closeAll(int... which) {
        synchronized (lock) {
            if (which.length == 0) which = new int[]{K_DATA, K_METER, K_AUX};
            for (int w : which) {
                closeQuietly(socks[w]);
                kick[w] = true;
            }
            lock.notifyAll();
        }
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        final int g = ++gen;
        dataThread = new Thread(() -> dataLoop(g), "serbridge-data");
        meterThread = new Thread(() -> meterLoop(g), "serbridge-meter");
        auxThread = new Thread(() -> auxLoop(g), "serbridge-aux");
        dataThread.start();
        meterThread.start();
        auxThread.start();
    }

    /** Окончательно: остановить всё и освободить потоки (при закрытии приложения). */
    public void shutdown() {
        stop();
        sendIo.shutdownNow();
        ctrlIo.shutdownNow();
    }

    public synchronized void stop() {
        synchronized (lock) {
            running = false;
        }
        closeAll();
    }

    /** Сменить мост (или задать впервые); текущие соединения рвутся, потоки подключаются заново. */
    public void setTarget(String newHost, int data, int ctrl, int meter) {
        synchronized (lock) {
            host = newHost;
            dataPort = data;
            ctrlPort = ctrl;
            meterPort = meter;
        }
        reconnect();
    }

    /** Разорвать соединения и сразу подключиться заново. */
    public void reconnect() {
        closeAll();
    }

    // ------------------------------------------------------------------ поток данных

    private boolean alive(int g) {
        return running && gen == g;
    }

    private void dataLoop(int g) {
        byte[] buf = new byte[4096];
        while (alive(g)) {
            String h = host;
            if (h == null) {
                pause(K_DATA, 1000);
                continue;
            }
            Socket s = new Socket();
            boolean wasUp = false;
            try {
                s.connect(new InetSocketAddress(h, dataPort), 3000);
                s.setKeepAlive(true);
                s.setTcpNoDelay(true);
                if (!publish(K_DATA, s, g, h)) continue;
                wasUp = true;
                listener.onDataState(true, null);
                InputStream in = s.getInputStream();
                int n;
                while ((n = in.read(buf)) > 0 && alive(g)) listener.onDataBytes(Arrays.copyOf(buf, n));
                if (alive(g)) listener.onDataState(false, "мост закрыл соединение");
            } catch (IOException e) {
                // Соединение закрыли мы сами при смене моста — сообщать не о чем
                if (alive(g) && h.equals(host)) listener.onDataState(false, wasUp ? "связь прервалась" : describe(e));
            } finally {
                unpublish(K_DATA, s);
                closeQuietly(s);
            }
            if (alive(g)) pause(K_DATA, 2000);
        }
    }

    // ------------------------------------------------------------------ поток измерений

    private void meterLoop(int g) {
        while (alive(g)) {
            String h = host;
            if (h == null) {
                pause(K_METER, 1000);
                continue;
            }
            Socket s = new Socket();
            boolean gotLine = false;
            try {
                s.connect(new InetSocketAddress(h, meterPort), 3000);
                s.setKeepAlive(true);
                s.setSoTimeout(METER_SILENCE_MS);
                if (!publish(K_METER, s, g, h)) continue;
                listener.onMeterState(true, null);
                InputStream in = new BufferedInputStream(s.getInputStream(), 2048);
                ByteArrayOutputStream line = new ByteArrayOutputStream(128);
                int c;
                while ((c = in.read()) >= 0 && alive(g)) {
                    if (c == '\n') {
                        gotLine = true;
                        listener.onMeterLine(new String(line.toByteArray(), StandardCharsets.UTF_8).trim());
                        line.reset();
                    } else if (line.size() < 4096) {
                        line.write(c);
                    }
                }
                if (alive(g)) listener.onMeterState(false, null);
            } catch (SocketTimeoutException e) {
                // Соединение «повисло» (keepalive этого не замечает) — рвём и подключаемся заново.
                if (alive(g)) listener.onMeterState(false, gotLine ? "поток измерений молчит 4 с — переподключаюсь" : null);
            } catch (IOException e) {
                if (alive(g)) listener.onMeterState(false, null);
            } finally {
                unpublish(K_METER, s);
                closeQuietly(s);
            }
            // Мост слал измерения и вдруг пропал (перезагрузка, обрыв Wi-Fi): соединение данных
            // в таком случае «висит» без признаков обрыва — Android заметит его лишь через часы.
            // Рвём и его, чтобы поток данных переподключился вместе с измерениями.
            if (gotLine && alive(g) && h.equals(host)) closeAll(K_DATA, K_AUX);
            if (alive(g)) pause(K_METER, gotLine ? 300 : 2000);
        }
    }

    // ------------------------------------------------------------------ второй приёмник (AUX)

    private void auxLoop(int g) {
        byte[] buf = new byte[2048];
        while (alive(g)) {
            String h = host;
            if (h == null) {
                pause(K_AUX, 1000);
                continue;
            }
            Socket s = new Socket();
            boolean wasUp = false;
            try {
                s.connect(new InetSocketAddress(h, AUX_PORT), 3000);
                s.setKeepAlive(true);
                s.setTcpNoDelay(true);
                if (!publish(K_AUX, s, g, h)) continue;
                wasUp = true;
                listener.onAuxState(true);
                InputStream in = s.getInputStream();
                int n;
                while ((n = in.read(buf)) > 0 && alive(g)) listener.onAuxBytes(Arrays.copyOf(buf, n));
            } catch (IOException e) {
                // старая прошивка без порта 2327 или нет связи — тихо пробуем позже
            } finally {
                unpublish(K_AUX, s);
                closeQuietly(s);
            }
            if (wasUp && alive(g)) listener.onAuxState(false);
            if (alive(g)) pause(K_AUX, wasUp ? 2000 : 5000);
        }
    }

    private void pause(int which, long ms) {
        synchronized (lock) {
            if (!kick[which]) {
                try {
                    lock.wait(ms);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            kick[which] = false;
        }
    }

    // ------------------------------------------------------------------ отправка и команды

    public void send(final byte[] bytes) {
        sendIo.execute(() -> {
            Socket s = sock(K_DATA);
            if (s == null) {
                listener.onSendFailed("нет связи с мостом — не отправлено");
                return;
            }
            try {
                OutputStream out = s.getOutputStream();
                out.write(bytes);
                out.flush();
            } catch (IOException e) {
                listener.onSendFailed("ошибка отправки: " + describe(e));
            }
        });
    }

    /** Команда мосту через порт управления; ответ — одной строкой. */
    public void command(final String cmd, final Reply reply) {
        final String h = host;
        final int port = ctrlPort;
        ctrlIo.execute(() -> {
            if (h == null) {
                reply.onReply("ERR мост не выбран");
                return;
            }
            String answer;
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(h, port), 3000);
                s.setSoTimeout(4000);
                InputStream in = new BufferedInputStream(s.getInputStream(), 512);
                OutputStream out = s.getOutputStream();
                out.write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                ByteArrayOutputStream line = new ByteArrayOutputStream(256);
                int c;
                while ((c = in.read()) >= 0 && c != '\n') line.write(c);
                answer = new String(line.toByteArray(), StandardCharsets.UTF_8).trim();
                if (answer.isEmpty()) answer = "ERR пустой ответ моста";
            } catch (SocketTimeoutException e) {
                answer = "ERR мост не ответил";
            } catch (IOException e) {
                answer = "ERR " + describe(e);
            }
            reply.onReply(answer);
        });
    }

    // ------------------------------------------------------------------ поиск моста

    /** Поиск мостов широковещательным запросом. Вызывать не из главного потока. */
    public static List<Found> discover(Context ctx, int timeoutMs) {
        List<Found> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock ml = null;
        try {
            if (wm != null) {
                ml = wm.createMulticastLock("serbridge");
                ml.setReferenceCounted(false);
                ml.acquire();
            }
        } catch (RuntimeException ignored) {
            ml = null;
        }
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            s.setSoTimeout(250);
            List<InetAddress> targets = new ArrayList<>();
            targets.add(InetAddress.getByName("255.255.255.255"));
            InetAddress bc = subnetBroadcast(wm);
            if (bc != null) targets.add(bc);
            targets.add(InetAddress.getByName("192.168.4.1"));   // собственная точка доступа моста

            byte[] query = "SERBRIDGE?".getBytes(StandardCharsets.US_ASCII);
            byte[] buf = new byte[512];
            long end = System.currentTimeMillis() + timeoutMs;
            long nextSend = 0;
            int sends = 0;
            while (System.currentTimeMillis() < end) {
                long now = System.currentTimeMillis();
                if (sends < 3 && now >= nextSend) {
                    for (InetAddress t : targets) {
                        try {
                            s.send(new DatagramPacket(query, query.length, t, FIND_PORT));
                        } catch (IOException ignored) {
                            // нет маршрута к этому адресу — не страшно
                        }
                    }
                    sends++;
                    nextSend = now + 400;
                }
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                String text = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8).trim();
                if (!text.startsWith("SERBRIDGE") || text.startsWith("SERBRIDGE?")) continue;
                String ip = p.getAddress().getHostAddress();
                if (seen.add(ip)) found.add(new Found(ip, parseKv(text)));
            }
        } catch (IOException ignored) {
            // нет сети
        } finally {
            if (ml != null) {
                try {
                    ml.release();
                } catch (RuntimeException ignored) {
                    // уже отпущен
                }
            }
        }
        return found;
    }

    @SuppressWarnings("deprecation")
    private static InetAddress subnetBroadcast(WifiManager wm) {
        try {
            if (wm == null) return null;
            DhcpInfo d = wm.getDhcpInfo();
            if (d == null || d.ipAddress == 0) return null;
            int mask = d.netmask != 0 ? d.netmask : 0x00FFFFFF;   // порядок байт little-endian
            int bc = (d.ipAddress & mask) | ~mask;
            byte[] b = {(byte) bc, (byte) (bc >> 8), (byte) (bc >> 16), (byte) (bc >> 24)};
            return InetAddress.getByAddress(b);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ разное

    /** «OK name=X ip=Y …» → {name: X, ip: Y, …}; слова без «=» пропускаются. */
    public static Map<String, String> parseKv(String line) {
        Map<String, String> kv = new LinkedHashMap<>();
        for (String w : line.trim().split("\\s+")) {
            int eq = w.indexOf('=');
            if (eq > 0) kv.put(w.substring(0, eq), w.substring(eq + 1));
        }
        return kv;
    }

    static int toInt(String s, int def) {
        if (s == null) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static String describe(IOException e) {
        String m = e.getMessage();
        if (m == null) return e.getClass().getSimpleName();
        if (m.contains("ECONNREFUSED")) return "мост отказал в соединении";
        if (m.contains("EHOSTUNREACH") || m.contains("ENETUNREACH")) return "адрес недоступен";
        if (m.contains("failed to connect") || m.contains("timed out")) return "мост не отвечает";
        return m;
    }

    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
            // уже закрыт
        }
    }
}
