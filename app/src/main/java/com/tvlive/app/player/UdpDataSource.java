package com.tvlive.app.player;

import android.content.Context;
import android.net.Uri;
import android.net.wifi.WifiManager;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.upstream.BaseDataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.TransferListener;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.DatagramPacket;

/**
 * UDP 组播/单播 DataSource：支持 {@code udp://host:port}（如 IPTV 组播 udp://239.77.0.119:5146）。
 *
 * <p>ExoPlayer 原生不提供 UDP 组播 DataSource，这里用 {@link MulticastSocket} 加入组播组接收
 * MPEG-TS over UDP 数据报，作为字节流喂给 {@code ProgressiveMediaSource} + TsExtractor。
 *
 * <p>实现要点：
 * <ul>
 *   <li>加入组播组前申请 {@link WifiManager.MulticastLock}，避免 Wi-Fi 省电过滤组播包；</li>
 *   <li>每个 UDP 数据报（通常 1316 字节 = 7×188 TS 包）作为一段字节流返回；</li>
 *   <li>读取阻塞直到收到数据报或 SO_TIMEOUT（8 秒）触发 IOException；</li>
 *   <li>close() 释放组播锁 + 关闭 socket。</li>
 * </ul>
 */
public final class UdpDataSource extends BaseDataSource {

    /** UDP 数据报接收缓冲（最大 64KB，足以容纳 IPTV 一个 UDP 包） */
    private static final int MAX_PACKET_SIZE = 65535;
    private static final int SOCKET_TIMEOUT_MS = 8000;

    private final Context context;

    private MulticastSocket socket;
    private WifiManager.MulticastLock multicastLock;
    private Uri uri;

    private final byte[] packetBuffer = new byte[MAX_PACKET_SIZE];
    private int packetPos = 0;
    private int packetLen = 0;
    private boolean loggedFirstPacket = false;
    /** RTP 头长度（>0 表示流是 TS-over-RTP，需跳过 RTP 头输出 TS payload） */
    private int rtpHeaderLen = 0;
    /** 是否已检测过 RTP */
    private boolean rtpDetected = false;

    public UdpDataSource(Context context) {
        this(context, null);
    }

    public UdpDataSource(Context context, TransferListener listener) {
        super(false);
        this.context = context.getApplicationContext();
        if (listener != null) addTransferListener(listener);
    }

    @Override
    public long open(DataSpec dataSpec) throws java.io.IOException {
        uri = dataSpec.uri;
        String host = uri.getHost();
        int port = uri.getPort();
        if (host == null || port < 0) {
            throw new java.io.IOException("UDP 地址非法: " + uri);
        }
        // 申请组播锁，防止 Wi-Fi 省电模式丢弃组播包
        try {
            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                multicastLock = wm.createMulticastLock("TvLiveUdp");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
        } catch (Throwable t) {
            android.util.Log.w("UdpDataSource", "MulticastLock 获取失败: " + t);
        }
        socket = new MulticastSocket(port);
        socket.setReuseAddress(true);
        socket.setSoTimeout(SOCKET_TIMEOUT_MS);
        // 组播地址（224.0.0.0 ~ 239.255.255.255）加入组播组；单播直接绑定
        InetAddress addr = InetAddress.getByName(host);
        if (addr.isMulticastAddress()) {
            // 明确指定 Wi-Fi/活动网络接口加入组播组（Android 默认接口可能不对）
            java.net.NetworkInterface nif = findActiveInterface();
            if (nif != null) {
                socket.setNetworkInterface(nif);
                socket.joinGroup(new java.net.InetSocketAddress(addr, port), nif);
                android.util.Log.i("UdpDataSource", "已加入组播组 " + host + ":" + port
                        + " 接口=" + nif.getName());
            } else {
                socket.joinGroup(addr);
                android.util.Log.i("UdpDataSource", "已加入组播组(默认接口) " + host + ":" + port);
            }
        } else {
            socket.bind(new InetSocketAddress(port));
            socket.connect(addr, port);
            android.util.Log.i("UdpDataSource", "UDP 单播 " + host + ":" + port);
        }
        transferStarted(dataSpec);
        // 直播流长度未知
        return C.LENGTH_UNSET;
    }

    /** 查找活动网络接口（Wi-Fi 或以太网），用于组播加入正确接口。 */
    private java.net.NetworkInterface findActiveInterface() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifs = java.net.NetworkInterface.getNetworkInterfaces();
            java.net.NetworkInterface best = null;
            while (ifs != null && ifs.hasMoreElements()) {
                java.net.NetworkInterface nif = ifs.nextElement();
                if (nif == null || !nif.isUp() || nif.isLoopback()) continue;
                String name = nif.getName().toLowerCase();
                // 优先 wlan/eth，避免 p2p/lo/rmnet
                if (name.contains("wlan") || name.contains("eth") || name.contains("en0")) {
                    if (best == null) best = nif;
                    // wlan 优先于 eth
                    if (name.contains("wlan")) { best = nif; break; }
                }
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public int read(byte[] target, int offset, int length) throws java.io.IOException {
        if (length == 0) return 0;
        // 当前数据报读完，接收下一个
        if (packetPos >= packetLen) {
            DatagramPacket pkt = new DatagramPacket(packetBuffer, packetBuffer.length);
            long t0 = System.currentTimeMillis();
            socket.receive(pkt);  // 阻塞直到收到数据报或超时
            int dataLen = pkt.getLength();
            // 首次检测是否为 TS-over-RTP（IPTV 组播常见：12 字节 RTP 头 + TS payload）
            if (!rtpDetected) {
                rtpDetected = true;
                if (dataLen >= 2 && (packetBuffer[0] & 0xC0) == 0x80) {  // V=2（RTP）
                    int pt = packetBuffer[1] & 0x7F;
                    if (pt == 33) {  // PT=33 = MPEG-TS
                        rtpHeaderLen = 12;  // 基本 RTP 头
                        android.util.Log.i("UdpDataSource", "检测到 TS-over-RTP（PT=33）");
                    }
                }
            }
            // 去除 RTP 头（每包都要去，因为 RTP 头是每包的）
            int skip = 0;
            if (rtpHeaderLen > 0 && dataLen >= rtpHeaderLen) {
                // 动态计算本包 RTP 头长度（含 CSRC + extension）
                int cc = packetBuffer[0] & 0x0F;
                int hdr = 12 + cc * 4;
                if ((packetBuffer[0] & 0x10) != 0 && dataLen >= hdr + 4) {  // extension
                    int extLen = ((packetBuffer[hdr + 2] & 0xFF) << 8) | (packetBuffer[hdr + 3] & 0xFF);
                    hdr += 4 + extLen * 4;
                }
                skip = Math.min(hdr, dataLen);
            }
            packetPos = skip;
            packetLen = dataLen;
            if (!loggedFirstPacket) {
                loggedFirstPacket = true;
                android.util.Log.i("UdpDataSource", "首次收到数据报 " + dataLen
                        + " 字节，耗时 " + (System.currentTimeMillis() - t0) + "ms"
                        + (skip > 0 ? " RTP头跳过" + skip : ""));
            }
        }
        int n = Math.min(length, packetLen - packetPos);
        System.arraycopy(packetBuffer, packetPos, target, offset, n);
        packetPos += n;
        bytesTransferred(n);
        return n;
    }

    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public void close() {
        packetPos = 0;
        packetLen = 0;
        if (socket != null) {
            try {
                if (uri != null && uri.getHost() != null) {
                    InetAddress addr = InetAddress.getByName(uri.getHost());
                    if (addr.isMulticastAddress() && socket.isConnected() == false) {
                        socket.leaveGroup(addr);
                    }
                }
            } catch (Throwable t) { /* 忽略 */ }
            try { socket.close(); } catch (Throwable t) { /* 忽略 */ }
            socket = null;
        }
        if (multicastLock != null) {
            try { if (multicastLock.isHeld()) multicastLock.release(); } catch (Throwable t) { /* 忽略 */ }
            multicastLock = null;
        }
        transferEnded();
    }

    /** DataSource 工厂。 */
    public static final class Factory implements com.google.android.exoplayer2.upstream.DataSource.Factory {
        private final Context context;

        public Factory(Context context) {
            this.context = context.getApplicationContext();
        }

        @Override
        public UdpDataSource createDataSource() {
            return new UdpDataSource(context);
        }
    }
}
