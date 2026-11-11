package ru.serbridge.mobile;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;

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
 *   2326/TCP — измерения: строки H / V / E / T.
 * Все методы Listener вызываются из сетевых потоков.
 */
public final class Bridge {

    public interface Listener {
        void onDataBytes(byte[] data);

        void onDataState(boolean connected, String why);

        void onMeterLine(String line);

        void onMeterState(boolean connected, String why);

        void onSendFailed(String why);
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

    public static final int DATA_PORT = 2323, CTRL_PORT = 2324, FIND_PORT = 2325, METER_PORT = 2326;
    private static final int METER_SILENCE_MS = 4000;   // мост шлёт строку хотя бы раз в секунду

    private final Listener listener;
    private final Object lock = new Object();
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private volatile String host;
    private volatile int dataPort = DATA_PORT, ctrlPort = CTRL_PORT, meterPort = METER_PORT;
    private volatile boolean running;
    private volatile int gen;                       // поколение потоков: после stop/start старые выходят
    private volatile Socket dataSock, meterSock;
    private boolean kickData, kickMeter;            // под lock: «не ждать, переподключиться сразу»
    private Thread dataThread, meterThread;

    public Bridge(Listener listener) {
        this.listener = listener;
    }

    public String host() {
        return host;
    }

    public boolean isDataConnected() {
        return dataSock != null;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        final int g = ++gen;
        dataThread = new Thread(() -> dataLoop(g), "serbridge-data");
        meterThread = new Thread(() -> meterLoop(g), "serbridge-meter");
        dataThread.start();
        meterThread.start();
    }

    public synchronized void stop() {
        running = false;
        closeQuietly(dataSock);
        closeQuietly(meterSock);
        synchronized (lock) {
            kickData = kickMeter = true;
            lock.notifyAll();
        }
    }

    /** Сменить мост (или задать впервые); текущие соединения рвутся, потоки подключаются заново. */
    public void setTarget(String newHost, int data, int ctrl, int meter) {
        host = newHost;
        dataPort = data;
        ctrlPort = ctrl;
        meterPort = meter;
        reconnect();
    }

    /** Разорвать соединения и сразу подключиться заново. */
    public void reconnect() {
        closeQuietly(dataSock);
        closeQuietly(meterSock);
        synchronized (lock) {
            kickData = kickMeter = true;
            lock.notifyAll();
        }
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
                pause(true, 1000);
                continue;
            }
            Socket s = new Socket();
            boolean wasUp = false;
            try {
                s.connect(new InetSocketAddress(h, dataPort), 3000);
                s.setKeepAlive(true);
                s.setTcpNoDelay(true);
                if (!alive(g) || !h.equals(host)) continue;
                dataSock = s;
                wasUp = true;
                listener.onDataState(true, null);
                InputStream in = s.getInputStream();
                int n;
                while ((n = in.read(buf)) > 0) listener.onDataBytes(Arrays.copyOf(buf, n));
                if (alive(g)) listener.onDataState(false, "мост закрыл соединение");
            } catch (IOException e) {
                if (alive(g)) listener.onDataState(false, wasUp ? "связь прервалась" : describe(e));
            } finally {
                dataSock = null;
                closeQuietly(s);
            }
            if (alive(g)) pause(true, 2000);
        }
    }

    // ------------------------------------------------------------------ поток измерений

    private void meterLoop(int g) {
        while (alive(g)) {
            String h = host;
            if (h == null) {
                pause(false, 1000);
                continue;
            }
            Socket s = new Socket();
            boolean gotLine = false;
            try {
                s.connect(new InetSocketAddress(h, meterPort), 3000);
                s.setKeepAlive(true);
                s.setSoTimeout(METER_SILENCE_MS);
                if (!alive(g) || !h.equals(host)) continue;
                meterSock = s;
                listener.onMeterState(true, null);
                InputStream in = s.getInputStream();
                ByteArrayOutputStream line = new ByteArrayOutputStream(128);
                int c;
                while ((c = in.read()) >= 0) {
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
                meterSock = null;
                closeQuietly(s);
            }
            if (alive(g)) pause(false, gotLine ? 300 : 2000);
        }
    }

    private void pause(boolean data, long ms) {
        synchronized (lock) {
            boolean kicked = data ? kickData : kickMeter;
            if (!kicked) {
                try {
                    lock.wait(ms);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            if (data) kickData = false;
            else kickMeter = false;
        }
    }

    // ------------------------------------------------------------------ отправка и команды

    public void send(final byte[] bytes) {
        io.execute(() -> {
            Socket s = dataSock;
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
        io.execute(() -> {
            if (h == null) {
                reply.onReply("ERR мост не выбран");
                return;
            }
            String answer;
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(h, port), 3000);
                s.setSoTimeout(4000);
                InputStream in = s.getInputStream();
                // Если мост здоровается при подключении — пропустить приветствие.
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                byte[] skip = new byte[256];
                while (in.available() > 0 && in.read(skip) > 0) {
                    // приветствие отброшено
                }
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
